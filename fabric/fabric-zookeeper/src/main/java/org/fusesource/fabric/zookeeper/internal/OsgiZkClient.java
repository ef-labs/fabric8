package org.fusesource.fabric.zookeeper.internal;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.fusesource.fabric.zookeeper.IZKClient;
import org.linkedin.util.clock.Clock;
import org.linkedin.util.clock.SystemClock;
import org.linkedin.util.clock.Timespan;
import org.linkedin.util.concurrent.ConcurrentUtils;
import org.linkedin.zookeeper.client.AbstractZKClient;
import org.linkedin.zookeeper.client.ChrootedZKClient;
import org.linkedin.zookeeper.client.IZooKeeper;
import org.linkedin.zookeeper.client.LifecycleListener;
import org.linkedin.zookeeper.client.ZKChildren;
import org.linkedin.zookeeper.client.ZKData;
import org.linkedin.zookeeper.client.ZooKeeperFactory;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.slf4j.Logger;

public class OsgiZkClient extends AbstractZKClient implements Watcher, ManagedService, IZKClient {

    public static final String PID = "org.fusesource.fabric.zookeeper";
    
    public static final Logger log = org.slf4j.LoggerFactory.getLogger(OsgiZkClient.class.getName());

    public static enum State {
        NONE,
        CONNECTING,
        CONNECTED,
        RECONNECTING
    }

    private ConfigurationAdmin configurationAdmin;
    private BundleContext bundleContext;
    private ServiceRegistration managedServiceRegistration;
    private ServiceRegistration zkClientRegistration;

    private final Clock _clock = SystemClock.instance();
    private final List<LifecycleListener> _listeners = new CopyOnWriteArrayList<LifecycleListener>();

    private final Object _lock = new Object();
    private volatile State _state = State.NONE;
    
    private final StateChangeDispatcher _stateChangeDispatcher = new StateChangeDispatcher();

    private ZooKeeperFactory _factory;
    private IZooKeeper _zk;
    private Timespan _reconnectTimeout = Timespan.parse("20s");

    private ExpiredSessionRecovery _expiredSessionRecovery = null;


    private class StateChangeDispatcher extends Thread {
        private final AtomicBoolean _running = new AtomicBoolean(true);
        private final BlockingQueue<Boolean> _events = new LinkedBlockingQueue<Boolean>();

        private StateChangeDispatcher() {
            super("ZooKeeper state change dispatcher thread");
        }

        @Override
        public void run() {
            Map<LifecycleListener, Boolean> history = new IdentityHashMap<LifecycleListener, Boolean>();
            log.info("Starting StateChangeDispatcher");
            while (_running.get()) {
                Boolean isConnectedEvent;
                try {
                    isConnectedEvent = _events.take();
                } catch (InterruptedException e) {
                    continue;
                }
                if (!_running.get() || isConnectedEvent == null) {
                    continue;
                }
                Map<LifecycleListener, Boolean> newHistory = new IdentityHashMap<LifecycleListener, Boolean>();
                for (LifecycleListener listener : _listeners) {
                    Boolean previousEvent = history.get(listener);
                    // we propagate the event only if it was not already sent
                    if (previousEvent == null || previousEvent != isConnectedEvent) {
                        try {
                            if (isConnectedEvent) {
                                listener.onConnected();
                            } else {
                                listener.onDisconnected();
                            }
                        } catch (Throwable e) {
                            log.warn("Exception while executing listener (ignored)", e);
                        }
                    }
                    newHistory.put(listener, isConnectedEvent);
                }
                // we save which event each listener has seen last
                // we don't update the map in place because we need to get rid of unregistered listeners
                history = newHistory;
            }
            log.info("StateChangeDispatcher terminated.");
        }

        public void end() {
            _running.set(false);
            _events.add(false);
        }

        public void addEvent(OsgiZkClient.State oldState, OsgiZkClient.State newState) {
            log.debug("addEvent: {} => {}", oldState, newState);
            if (newState == OsgiZkClient.State.CONNECTED) {
                _events.add(true);
            } else if (oldState == OsgiZkClient.State.CONNECTED) {
                _events.add(false);
            }
        }
    }

    private class ExpiredSessionRecovery extends Thread {
        private ExpiredSessionRecovery() {
            super("ZooKeeper expired session recovery thread");
        }

        @Override
        public void run() {
            log.info("Entering recovery mode");
            synchronized(_lock) {
                try {
                    int count = 0;
                    while (_state == OsgiZkClient.State.NONE) {
                        try {
                            count++;
                            log.warn("Recovery mode: trying to reconnect to zookeeper [" + count + "]");
                            OsgiZkClient.this.start();
                        } catch (Throwable e) {
                            log.warn("Recovery mode: reconnect attempt failed [" + count + "]... waiting for " + _reconnectTimeout, e);
                            try {
                                _lock.wait(_reconnectTimeout.getDurationInMilliseconds());
                            } catch(InterruptedException e1) {
                                throw new RuntimeException("Recovery mode: wait interrupted... bailing out", e1);
                            }
                        }
                    }
                } finally {
                    _expiredSessionRecovery = null;
                    log.info("Exiting recovery mode.");
                }
            }
        }
    }

    public OsgiZkClient() {
        super(null);
    }

    public void setConfigurationAdmin(ConfigurationAdmin configurationAdmin) {
        this.configurationAdmin = configurationAdmin;
    }

    public void setBundleContext(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    public void init() throws Exception {
        _stateChangeDispatcher.setDaemon(true);
        _stateChangeDispatcher.start();

        Hashtable ht = new Hashtable();
        zkClientRegistration = bundleContext.registerService(
                new String[] { IZKClient.class.getName(), org.linkedin.zookeeper.client.IZKClient.class.getName() },
                this, ht);
        ht = new Hashtable();
        ht.put(Constants.SERVICE_PID, PID);
        managedServiceRegistration = bundleContext.registerService(ManagedService.class.getName(), this, ht);

        updated(getDefaultProperties());
    }
    
    private Dictionary getDefaultProperties() {
        try {
            Configuration c = configurationAdmin != null ? configurationAdmin.getConfiguration(PID, null) : null;
            return c != null ? c.getProperties() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public void destroy() {
        if (managedServiceRegistration != null) {
            managedServiceRegistration.unregister();
        }
        if (zkClientRegistration != null) {
            zkClientRegistration.unregister();
        }
        if (_stateChangeDispatcher != null) {
            _stateChangeDispatcher.end();
        }
        synchronized(_lock) {
            if (_zk != null) {
                try {
                    changeState(State.NONE);
                    _zk.close();
                    _zk = null;
                } catch(Exception e) {
                    log.debug("ignored exception", e);
                }
            }
        }
    }

    public void updated(Dictionary properties) throws ConfigurationException {
        synchronized (_lock) {
            String url = System.getProperty("zookeeper.url");
            Timespan timeout = new Timespan(10, Timespan.TimeUnit.SECOND);
            if (properties != null) {
                if (properties.get("zookeeper.url") != null) {
                    url = (String) properties.get("zookeeper.url");
                }
                if (properties.get("zookeeper.timeout") != null) {
                    timeout = Timespan.parse((String) properties.get("zookeeper.timeout"));
                }
            }
            if (_factory == null && url == null
                    || _factory != null && url != null && _factory.getConnectString().equals(url)) {
                // No configuration changes at all
                return;
            }
            if (_state != State.NONE) {
                changeState(url != null ? State.RECONNECTING : State.NONE);
                try {
                    _zk.close();
                } catch (Throwable t) {
                }
                _zk = null;
                _factory = null;
            }
            if (url != null) {
                _factory = new ZooKeeperFactory(url, timeout, this);
                tryStart();
            }
            zkClientRegistration.setProperties(properties);
        }
    }

    protected void tryStart() {
        synchronized (_lock) {
            try {
                start();
            } catch (Throwable e) {
                log.warn("Error while restarting:", e);
                if (_expiredSessionRecovery == null) {
                    _expiredSessionRecovery = new ExpiredSessionRecovery();
                    _expiredSessionRecovery.setDaemon(true);
                    _expiredSessionRecovery.start();
                }
            }
        }
    }

    public void start() {
        synchronized (_lock) {
            changeState(State.CONNECTING);
            _zk = _factory.createZooKeeper(this);
        }
    }

    @Override
    public void process(WatchedEvent event) {
        if (event.getState() != null) {
            log.debug("event: {}", event.getState());
            synchronized (_lock) {
                switch(event.getState())
                {
                    case SyncConnected:
                        changeState(State.CONNECTED);
                        break;
    
                    case Disconnected:
                        if(_state != State.NONE) {
                            changeState(State.RECONNECTING);
                        }
                        break;
    
                    case Expired:
                        // when expired, the zookeeper object is invalid and we need to recreate a new one
                        _zk = null;
                        log.warn("Expiration detected: trying to restart...");
                        tryStart();
                        break;
                    default:
                        log.warn("unprocessed event state: {}", event.getState());
                }
            }
        }
    }

    @Override
    protected IZooKeeper getZk() {
        State state = _state;
        if (state == State.NONE) {
            throw new IllegalStateException("ZooKeeper client has not been configured yet. You need to either create an ensemble or join one.");
        } else if (state != State.CONNECTED) {
            try {
                waitForConnected();
            } catch (Exception e) {
                throw new IllegalStateException("Error waiting for ZooKeeper connection", e);
            }
        }
        IZooKeeper zk = _zk;
        if (zk == null) {
            throw new IllegalStateException("No ZooKeeper connection available");
        }
        return zk;
    }

    public void waitForConnected(Timespan timeout) throws InterruptedException, TimeoutException {
        waitForState(State.CONNECTED, timeout);
    }

    public void waitForConnected() throws InterruptedException, TimeoutException {
        waitForConnected(null);
    }

    public void waitForState(State state, Timespan timeout) throws TimeoutException, InterruptedException {
        long endTime = timeout == null ? 0 : timeout.futureTimeMillis(_clock);
        if (_state != state) {
            synchronized (_lock) {
                while (_state != state) {
                    ConcurrentUtils.awaitUntil(_clock, _lock, endTime);
                }
            }
        }
    }

    private void changeState(State newState) {
        synchronized (_lock) {
            State oldState = _state;
            if (oldState != newState) {
                _stateChangeDispatcher.addEvent(oldState, newState);
                _state = newState;
                _lock.notifyAll();
            }
        }
    }

    @Override
    public void registerListener(LifecycleListener listener) {
        if (listener == null) {
            throw new NullPointerException("listener is null");
        }
        if (!_listeners.contains(listener)) {
            _listeners.add(listener);

        }
        if (_state == State.CONNECTED) {
            _stateChangeDispatcher.addEvent(null, State.CONNECTED);
        }
    }

    @Override
    public void removeListener(LifecycleListener listener) {
        if (listener == null) {
            throw new NullPointerException("listener is null");
        }
        _listeners.remove(listener);
    }

    @Override
    public org.linkedin.zookeeper.client.IZKClient chroot(String path) {
        return new ChrootedZKClient(this, adjustPath(path));
    }

    @Override
    public boolean isConnected() {
        return _state == State.CONNECTED;
    }
    
    public boolean isConfigured() {
        return _state != State.NONE;
    }
    
    @Override
    public String getConnectString() {
        return _factory.getConnectString();
    }

    @Override
    public Stat exists(final String path) throws InterruptedException, KeeperException {
        KeeperException recoverableException = null;

       while(true) {
            recoverableException = null;
            try {
                return super.exists(path);
            } catch (KeeperException.ConnectionLossException ex) {
                // Retry
            }
        }
    }

    @Override
    public List<String> getChildren(String path) throws InterruptedException, KeeperException {
        while (true) {
            try {
                return super.getChildren(path);
            } catch (KeeperException.ConnectionLossException ex) {
                // Rerty
            }
        }
    }

    /**
     * @return both children and stat in one object
     */
    @Override
    public ZKChildren getZKChildren(String path, Watcher watcher) throws KeeperException, InterruptedException {
       while (true) {
            try {
                waitForConnected();
                return super.getZKChildren(path, watcher);
            } catch (KeeperException.ConnectionLossException ex) {
                // Retry
            } catch (TimeoutException e) {
                // Not reachable
            }
        }
    }

    /**
     * Returns all the children (recursively) of the provided path. Note that like {@link #getChildren(String)}
     * the result is relative to <code>path</code>.
     */
    @Override
    public List<String> getAllChildren(String path) throws InterruptedException, KeeperException {
        while (true) {
            try {
                return super.getAllChildren(path);
            } catch (KeeperException.ConnectionLossException ex) {
                // Retry
            }
        }

    }

    @Override
    public void create(String path, String data, List<ACL> acl, CreateMode createMode) throws InterruptedException, KeeperException {
        boolean retry = false;
        while (true) {
            try {
                //We want on the first attempt to propagate an Exception, but ignore if its actually a retry.
                if (!retry || super.exists(path) == null) {
                    super.create(path, data, acl, createMode);
                }
                return;
            } catch (KeeperException.ConnectionLossException ex) {
                // Retry
                retry = true;
            }
        }
    }

    @Override
    public void createBytesNode(String path, byte[] data, List<ACL> acl, CreateMode createMode) throws InterruptedException, KeeperException {
        while (true) {
            try {
                super.createBytesNode(path, data, acl, createMode);
                return;
            } catch (KeeperException.ConnectionLossException ex) {
                // Retry
            }
        }
    }

    /**
     * Creates the parents if they don't exist
     */
    @Override
    public void createWithParents(String path, String data, List<ACL> acl, CreateMode createMode) throws InterruptedException, KeeperException {
        while (true) {
            try {
                super.createWithParents(path, data, acl, createMode);
                return;
            } catch (KeeperException.ConnectionLossException ex) {
                // Retry
            }
        }
    }

    /**
     * Creates the parents if they don't exist
     */
    @Override
    public void createBytesNodeWithParents(String path, byte[] data, List<ACL> acl, CreateMode createMode) throws InterruptedException, KeeperException {
        while (true) {
            try {
                super.createBytesNodeWithParents(path, data, acl, createMode);
                return;
            } catch (KeeperException.ConnectionLossException ex) {
                // Retry
            }
        }
    }

    @Override
    public byte[] getData(String path) throws InterruptedException, KeeperException {
        while (true) {
            try {
                return super.getData(path);
            } catch (KeeperException.ConnectionLossException ex) {
                // Retry
            }
        }
    }

    @Override
    public String getStringData(String path) throws InterruptedException, KeeperException {
        while (true) {
            try {
                return super.getStringData(path);
            } catch (KeeperException.ConnectionLossException ex) {
                // Retry
            }
        }
    }

    /**
     * Returns both the data as a string as well as the stat
     */
    @Override
    public ZKData<String> getZKStringData(String path) throws InterruptedException, KeeperException {
        while (true) {
            try {
                return super.getZKStringData(path);
            } catch (KeeperException.ConnectionLossException ex) {
                // Retry
            }
        }
    }

    /**
     * Returns both the data as a string as well as the stat (and sets a watcher if not null)
     */
    @Override
    public ZKData<String> getZKStringData(String path, Watcher watcher) throws InterruptedException, KeeperException {
        while (true) {
            try {
                return super.getZKStringData(path, watcher);
            } catch (KeeperException.ConnectionLossException ex) {
                // Retry
            }
        }
    }

    /**
     * Returns both the data as a byte[] as well as the stat
     */
    @Override
    public ZKData<byte[]> getZKByteData(String path) throws InterruptedException, KeeperException {
        while (true) {
            try {
                return super.getZKByteData(path);
            } catch (KeeperException.ConnectionLossException ex) {
                // Retry
            }
        }
    }

    /**
     * Returns both the data as a byte[] as well as the stat (and sets a watcher if not null)
     */
    @Override
    public ZKData<byte[]> getZKByteData(String path, Watcher watcher) throws InterruptedException, KeeperException {
        while (true) {
            try {
                return super.getZKByteData(path, watcher);
            } catch (KeeperException.ConnectionLossException ex) {
                // Retry
            }
        }
    }

    @Override
    public Stat setData(String path, String data) throws InterruptedException, KeeperException {
        while (true) {
            try {
                return super.setData(path, data);
            } catch (KeeperException.ConnectionLossException ex) {
                // Retry
            }
        }
    }

    @Override
    public Stat setByteData(String path, byte[] data) throws InterruptedException, KeeperException {
        while (true) {
            try {
                return super.setByteData(path, data);
            } catch (KeeperException.ConnectionLossException ex) {
                // Retry
            }
        }
    }

    /**
     * Tries to create first and if the node exists, then does a setData.
     *
     * @return <code>null</code> if create worked, otherwise the result of setData
     */
    @Override
    public Stat createOrSetWithParents(String path, String data, List<ACL> acl, CreateMode createMode) throws InterruptedException, KeeperException {
        while (true) {
            try {
                return super.createOrSetWithParents(path, data, acl, createMode);
            } catch (KeeperException.ConnectionLossException ex) {
                // Retry
            }
        }
    }

    @Override
    public void delete(String path) throws InterruptedException, KeeperException {
        boolean retry = false;
        while (true) {
            try {
                //We want on the first attempt to propagate an Exception, but ignore if its actually a retry.
                if (!retry || super.exists(path) != null ) {
                    super.delete(path);
                }
                return;
            } catch (KeeperException.ConnectionLossException ex) {
                // Retry
                retry = true;
            }
        }
    }

    /**
     * delete all the children if they exist
     */
    @Override
    public void deleteWithChildren(String path) throws InterruptedException, KeeperException {
        while (true) {
            try {
                super.deleteWithChildren(path);
                return;
            } catch (KeeperException.ConnectionLossException ex) {
                // Retry
            }
        }
    }

    @Override
    public Stat createOrSetByteWithParents(String path, byte[] data, List<ACL> acl, CreateMode createMode) throws InterruptedException, KeeperException {
        while (true) {
            try {
                if (exists(path) != null) {
                    return setByteData(path, data);
                }
                try {
                    createBytesNodeWithParents(path, data, acl, createMode);
                    return null;
                } catch(KeeperException.NodeExistsException e) {
                    // this should not happen very often (race condition)
                    return setByteData(path, data);
                }
            } catch (KeeperException.ConnectionLossException ex) {
                // Retry
            }
        }
    }

}
