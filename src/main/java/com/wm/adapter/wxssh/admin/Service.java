package com.wm.adapter.wxssh.admin;

import com.wm.adapter.wxssh.WxSshAdapter;
import com.wm.adk.admin.AdapterAdmin;
import com.luisloja.wxssh.core.HostKeyProbe;
import com.luisloja.wxssh.core.KnownHostsFile;
import com.luisloja.wxssh.core.SshAdapterException;
import com.wm.app.b2b.server.ServiceException;
import com.wm.data.IData;
import com.wm.data.IDataCursor;
import com.wm.data.IDataUtil;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Package startup and shutdown administration services for WxSshAdapter.
 * <p>
 * Why: Invoked by Integration Server during package load and unload (configured in manifest.v3)
 * to register and unregister the adapter type with WmART.
 * <p>
 * Side effects: Registers or unregisters the {@link WxSshAdapter} singleton in the WmART runtime registry.
 */
public class Service {

    public Service() {
    }

    /**
     * Registers WxSshAdapter with WmART.
     *
     * @param pipeline the Integration Server execution pipeline (unused).
     * @throws ServiceException if registration fails.
     */
    public static void registerAdapter(IData pipeline) throws ServiceException {
        try {
            AdapterAdmin.registerAdapter(WxSshAdapter.getInstance());
        } catch (Exception ex) {
            throw new ServiceException(ex.getMessage());
        }
    }

    /**
     * Unregisters WxSshAdapter from WmART.
     *
     * @param pipeline the Integration Server execution pipeline (unused).
     * @throws ServiceException if unregistration fails.
     */
    public static void unregisterAdapter(IData pipeline) throws ServiceException {
        try {
            AdapterAdmin.unregisterAdapter(WxSshAdapter.getInstance());
        } catch (Exception ex) {
            throw new ServiceException(ex.getMessage());
        }
    }

    /**
     * Reads the host key an SSH server presents, without authenticating and without trusting it.
     * <p>
     * Why: the {@code KNOWN_HOSTS} policy needs the server's key in a file, and an operator needs a way to look at
     * the fingerprint and compare it with one obtained out of band (from the server administrator) before it is
     * trusted. This is a plain Integration Server service rather than an adapter service because it has to work
     * before any connection exists.
     * <p>
     * Side effects: opens one TCP connection to {@code host:port}, runs the SSH key exchange only, and closes it.
     * Nothing is written anywhere. Input: {@code host} (required), {@code port} (default 22),
     * {@code timeoutSeconds} (default 10). Output: {@code algorithm}, {@code fingerprint} (SHA256:...) and
     * {@code knownHostsEntry} (the line {@link #addToKnownHosts} would write).
     *
     * @param pipeline the Integration Server pipeline.
     * @throws ServiceException if the input is invalid or the server cannot be reached.
     */
    public static void getHostKeyFingerprint(IData pipeline) throws ServiceException {
        IDataCursor c = pipeline.getCursor();
        try {
            String host = text(c, "host");
            if (host == null) {
                throw new ServiceException("host is required");
            }
            int port = intValue(c, "port", 22);
            int timeoutSeconds = intValue(c, "timeoutSeconds", 10);
            if (timeoutSeconds < 1) {
                throw new ServiceException("timeoutSeconds must be at least 1 but was " + timeoutSeconds);
            }
            HostKeyProbe.Result r = HostKeyProbe.probe(host, port, timeoutSeconds * 1000);
            IDataUtil.put(c, "algorithm", r.algorithm());
            IDataUtil.put(c, "fingerprint", r.fingerprint());
            IDataUtil.put(c, "knownHostsEntry", r.knownHostsLine(host, port));
        } catch (SshAdapterException ex) {
            throw new ServiceException(ex.getCode() + ": " + ex.getMessage());
        } finally {
            c.destroy();
        }
    }

    /**
     * Records a server's host key in a {@code known_hosts} file, but only if it is the key the operator approved.
     * <p>
     * Why: trust-on-first-use is only safe when a human compares the fingerprint. Taking the approved fingerprint
     * as a required input makes the approval explicit: the key is fetched again, and if it no longer matches (a
     * man-in-the-middle appeared between the two calls, or the operator typed the wrong value) nothing is written.
     * <p>
     * Side effects: opens one TCP connection to {@code host:port} for the key exchange; creates the file and its
     * parent folders if missing and appends one line. An existing identical entry is left alone; a different key of
     * the same algorithm for the same host is refused and never overwritten. Input: {@code host},
     * {@code port} (default 22), {@code knownHostsPath}, {@code expectedFingerprint} (all but port required),
     * {@code timeoutSeconds} (default 10). Output: {@code added} ("true"/"false"), {@code entry},
     * {@code knownHostsPath}.
     *
     * @param pipeline the Integration Server pipeline.
     * @throws ServiceException if the input is invalid, the fingerprint does not match, a conflicting entry exists
     *                          or the file cannot be written.
     */
    public static void addToKnownHosts(IData pipeline) throws ServiceException {
        IDataCursor c = pipeline.getCursor();
        try {
            String host = text(c, "host");
            String path = text(c, "knownHostsPath");
            String expected = text(c, "expectedFingerprint");
            if (host == null || path == null || expected == null) {
                throw new ServiceException("host, knownHostsPath and expectedFingerprint are required");
            }
            if (!expected.matches("SHA256:[A-Za-z0-9+/]+={0,2}")) {
                throw new ServiceException("expectedFingerprint must be the fingerprint only, in the form SHA256:..."
                        + " (the 'fingerprint' output of getHostKeyFingerprint), not the known_hosts entry or another value");
            }
            int port = intValue(c, "port", 22);
            int timeoutSeconds = intValue(c, "timeoutSeconds", 10);
            if (timeoutSeconds < 1) {
                throw new ServiceException("timeoutSeconds must be at least 1 but was " + timeoutSeconds);
            }
            HostKeyProbe.Result r = HostKeyProbe.probe(host, port, timeoutSeconds * 1000);
            if (!r.fingerprint().equals(expected)) {
                throw new ServiceException("HOST_KEY_REJECTED: the server presented " + r.fingerprint()
                        + " but " + expected + " was approved; nothing was written");
            }
            Path file = Paths.get(path);
            KnownHostsFile.AddResult added = KnownHostsFile.add(file, host, port, r);
            IDataUtil.put(c, "added", Boolean.toString(added.added()));
            IDataUtil.put(c, "entry", added.line());
            IDataUtil.put(c, "knownHostsPath", file.toAbsolutePath().toString());
        } catch (SshAdapterException ex) {
            throw new ServiceException(ex.getCode() + ": " + ex.getMessage());
        } catch (java.nio.file.InvalidPathException ex) {
            throw new ServiceException("knownHostsPath is not a valid path: " + ex.getMessage());
        } finally {
            c.destroy();
        }
    }

    /** Returns the trimmed string input {@code key}, or null when absent or blank. */
    private static String text(IDataCursor c, String key) {
        Object v = IDataUtil.get(c, key);
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** Returns the integer input {@code key}, or {@code dflt} when absent; a non-number is a ServiceException. */
    private static int intValue(IDataCursor c, String key, int dflt) throws ServiceException {
        String s = text(c, key);
        if (s == null) {
            return dflt;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            throw new ServiceException(key + " must be a whole number but was '" + s + "'");
        }
    }
}