package com.wm.adapter.wxssh.connection;

import com.luisloja.wxssh.core.SshAdapterException;
import com.luisloja.wxssh.core.SshConnectionConfig;
import com.luisloja.wxssh.core.SshSession;
import com.wm.adapter.wxssh.WxSshAdapter;
import com.wm.adk.connection.WmConnectionRequestInfo;
import com.wm.adk.connection.WmManagedConnection;
import com.wm.adk.error.AdapterConnectionException;
import com.wm.adk.error.AdapterException;
import com.wm.adk.i18n.AdapterResourceBundleManager;
import com.wm.adk.metadata.ResourceDomainValues;
import com.wm.adk.metadata.WmAdapterAccess;
import javax.security.auth.Subject;

/**
 * Physical managed connection instance for an SSH session within the webMethods Adapter Runtime pool.
 * <p>
 * Why: Wraps a live {@link SshSession} (transport over SSHJ), providing connection lifecycle management
 * (initialization, liveness checks, destruction) to WmART.
 * <p>
 * Side effects: Opens an SSH transport socket, executes cryptographic key exchange and authentication during
 * initialization, and closes socket/channels on destroy.
 */
public class SshManagedConnection extends WmManagedConnection {

    private SshSession sshSession;

    public SshManagedConnection() {
        super();
    }

    @Override
    protected boolean initializationRequired() {
        return true;
    }

    @Override
    protected void initializeConnectionV2(Subject subject, WmConnectionRequestInfo cri) throws AdapterException {
        connect();
    }

    /**
     * Opens the SSH session from the current connection settings.
     * <p>
     * Why: shared by the first connect and by {@link #getOrReconnectSession()}.
     * <p>
     * Side effects: network connect, key exchange and authentication; replaces {@link #sshSession}.
     */
    private void connect() throws AdapterException {
        SshConnectionFactory factory = (SshConnectionFactory) getFactory();
        if (factory == null) {
            throw new AdapterException("SshManagedConnection factory is null", null, null);
        }

        try {
            SshConnectionConfig config = factory.toSshConnectionConfig();
            config.validate();
            this.sshSession = SshSession.connect(config);
        } catch (SshAdapterException ex) {
            throw connectionError(ex.getCode() + ": " + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw connectionError("CONNECT_ERROR: " + ex.getMessage(), ex);
        }
    }

    /**
     * Builds the exception that reports a failed connect, with its text intact.
     * <p>
     * Why: {@code AdapterConnectionException} dereferences its resource bundle manager while it is being built, so
     * the previous {@code null} manager turned every failed connect (wrong password, host down) into a
     * {@code NullPointerException} that hid the real reason. The text is passed as argument {@code {0}} of bundle
     * key {@code 1000}, the same way as {@code SshAdapterService.adapterError}.
     * <p>
     * Side effects: loads the adapter resource bundle on first use.
     *
     * @param text  complete error text; must not contain secrets
     * @param cause the underlying failure
     * @return the exception to throw
     */
    private static AdapterConnectionException connectionError(String text, Throwable cause) {
        return new AdapterConnectionException(bundleManager(), Integer.toString(WxSshAdapter.MAJOR_CODE), "1000",
                new String[]{text}, null, cause);
    }

    private static volatile AdapterResourceBundleManager bundles;

    private static AdapterResourceBundleManager bundleManager() {
        AdapterResourceBundleManager m = bundles;
        if (m == null) {
            try {
                m = new AdapterResourceBundleManager(WxSshAdapter.RESOURCE_BUNDLE_NAME,
                        SshManagedConnection.class.getClassLoader());
            } catch (AdapterException e) {
                throw new IllegalStateException("adapter resource bundle cannot be loaded", e);
            }
            bundles = m;
        }
        return m;
    }

    /**
     * Returns an open session, reconnecting first when the old one has died.
     * <p>
     * Why: the Adapter Runtime does not call {@link #isAlive()} before it hands a pooled connection to a service,
     * and it only drops a pooled connection when the service throws a <em>fatal</em>
     * {@code AdapterConnectionException}. After the SSH server restarted, every call therefore failed with
     * "SSH session is disconnected" until the connection was disabled and enabled by hand. Reconnecting here makes
     * the pooled connection heal itself on the next call.
     * <p>
     * Side effects: when the session is closed or missing, closes it (ignoring errors) and opens a new one, which
     * is network I/O with the connection settings as they are now. If that fails, the failure is thrown as a
     * <em>fatal</em> {@link AdapterConnectionException} so the runtime discards this pooled connection.
     *
     * @return an open session, never null
     * @throws AdapterException when the session is dead and a new one cannot be opened
     */
    public synchronized SshSession getOrReconnectSession() throws AdapterException {
        if (sshSession != null && sshSession.isOpen()) {
            return sshSession;
        }
        destroyConnection();
        try {
            connect();
        } catch (AdapterConnectionException ex) {
            ex.setFatal(true);
            throw ex;
        }
        return sshSession;
    }

    @Override
    protected void destroyConnection() throws AdapterException {
        if (sshSession != null) {
            try {
                sshSession.close();
            } catch (Exception ignored) {
            } finally {
                sshSession = null;
            }
        }
    }

    @Override
    public boolean isAlive() throws AdapterConnectionException {
        return sshSession != null && sshSession.isOpen();
    }

    @Override
    public void registerResourceDomain(WmAdapterAccess access) throws AdapterException {
        // No dynamic design-time resource domains required for basic SSH connection
    }

    @Override
    public ResourceDomainValues[] adapterResourceDomainLookup(String fieldName, String query, String[][] params)
            throws AdapterException {
        return new ResourceDomainValues[0];
    }

    @Override
    public Boolean adapterCheckValue(String fieldName, String query, String[][] params, String value)
            throws AdapterException {
        return Boolean.TRUE;
    }

    /**
     * Returns the active underlying {@link SshSession}.
     *
     * @return the active SSH session.
     */
    public SshSession getSshSession() {
        return sshSession;
    }
}
