package com.wm.adapter.wxssh.service;

import com.luisloja.wxssh.core.RemoteEntry;
import com.luisloja.wxssh.core.SshAdapterException;
import com.luisloja.wxssh.core.SshSession;
import com.wm.adapter.wxssh.WxSshAdapter;
import com.wm.adapter.wxssh.connection.SshManagedConnection;
import com.wm.adk.cci.interaction.WmAdapterService;
import com.wm.adk.cci.record.WmRecord;
import com.wm.adk.connection.WmManagedConnection;
import com.wm.adk.error.AdapterException;
import com.wm.adk.i18n.AdapterResourceBundleManager;
import com.wm.adk.metadata.WmTemplateDescriptor;
import com.wm.data.IData;
import com.wm.data.IDataCursor;
import com.wm.data.IDataFactory;
import com.wm.data.IDataUtil;
import jakarta.resource.ResourceException;
import java.util.Locale;

/**
 * Base abstract class for all WxSshAdapter service interaction templates.
 * <p>
 * Why: Provides common connection retrieval, exception unwrapping, and resource bundle registration
 * for all adapter services extending {@link WmAdapterService}.
 * <p>
 * Side effects: Pulls connection from pool and delegates execution to subclass templates.
 */
public abstract class SshAdapterService extends WmAdapterService {

    /** Resource bundle key whose text is just {@code {0}}; see {@link #adapterError(String)}. */
    private static final String ERROR_KEY = "1000";

    public SshAdapterService() {
        super();
    }

    /**
     * Resolves the active {@link SshSession} from the provided managed connection, reconnecting if the old
     * session has died (see {@link SshManagedConnection#getOrReconnectSession()}).
     *
     * @param conn the managed connection from the WmART connection pool.
     * @return the active {@link SshSession}.
     * @throws AdapterException if the connection is of the wrong type, or the session is dead and cannot be reopened.
     */
    protected SshSession getSession(WmManagedConnection conn) throws AdapterException {
        if (conn instanceof SshManagedConnection sshConn) {
            return sshConn.getOrReconnectSession();
        }
        throw adapterError("Expected SshManagedConnection but received "
                + (conn != null ? conn.getClass().getName() : "null"));
    }

    /**
     * Translates an internal {@link SshAdapterException} into an ADK {@link AdapterException}.
     *
     * @param ex the caught SSH adapter exception.
     * @return the mapped ADK AdapterException.
     */
    protected AdapterException mapException(SshAdapterException ex) {
        return adapterError(ex.getCode() + ": " + ex.getMessage());
    }

    /**
     * Builds an {@link AdapterException} whose text reaches the caller of the service.
     * <p>
     * Why: the {@code AdapterException(String, IData, Throwable)} constructor leaves
     * {@code getAdapterServiceException()} null, and the runtime hands exactly that object to the service-level
     * error. The result is the bare "[ART.117.4002] Unable to invoke adapter service" with no reason, in Designer
     * and in the server log. The constructor used here takes a resource bundle key; key {@code 1000} is
     * {@code "{0}"} and the real text is passed as the argument, so quotes and braces in a path or server message are
     * never read as a message pattern.
     * <p>
     * Side effects: none beyond building the exception. The cause is deliberately not attached: the text already
     * carries the error code and the server message, and the runtime would append the cause message a second time.
     *
     * @param text complete, human readable error text; must not contain secrets.
     * @return the exception to throw.
     */
    protected static AdapterException adapterError(String text) {
        try {
            AdapterResourceBundleManager bundles = WxSshAdapter.getInstance().getAdapterResourceBundleManager();
            if (bundles != null) {
                return new AdapterException(bundles, Integer.toString(WxSshAdapter.MAJOR_CODE), ERROR_KEY,
                        new String[] {text}, null, null);
            }
        } catch (AdapterException | RuntimeException e) {
            // fall through to the plain form below; the text is still reported, only the wrapper is poorer
        }
        return new AdapterException(text, null, null);
    }

    /**
     * Fails with a message naming the field when a mandatory input is missing.
     * <p>
     * Why: the manage services change or delete data, so a blank path must stop the call here, with the field
     * name in the message, instead of reaching the server as an empty or default path.
     *
     * @param field pipeline field name, used in the message.
     * @param value the resolved value (input, else panel).
     * @return {@code value}, unchanged, when it has content.
     * @throws AdapterException when {@code value} is null or blank.
     */
    protected static String required(String field, String value) throws AdapterException {
        if (value == null || value.isBlank()) {
            throw adapterError(field + " is required");
        }
        return value;
    }

    /**
     * Turns a rejected setting (a typo in a number, an out of range id) into an adapter error.
     * <p>
     * Why: {@link SftpSettings} reports bad values with {@link IllegalArgumentException} so it needs no ADK
     * types; the service layer converts it, keeping the message that names the field.
     *
     * @param ex the parsing failure.
     * @return the adapter exception to throw.
     */
    protected static AdapterException invalidInput(IllegalArgumentException ex) {
        return adapterError("INVALID_CONFIG: " + ex.getMessage());
    }

    /**
     * Builds the output of a manage service that has nothing to report except that it worked.
     * <p>
     * Why: every failure is thrown, so {@code success} is always true when a flow sees the record; it is present
     * so a flow can map a stable field and so Designer shows an output signature.
     *
     * @return a new record holding {@code success=true}; the caller owns it and may add fields.
     */
    protected static WmRecord successRecord() {
        WmRecord out = WmRecord.create();
        IDataCursor c = out.getCursor();
        IDataUtil.put(c, "success", true);
        c.destroy();
        return out;
    }
    /** Type reported to the ADK for every declared string input field (the same form the JDBC adapter uses). */
    protected static final String STRING_TYPE = "java.lang.String";

    /**
     * Type reported for a field that carries a byte array. Designer has no separate byte-array type name in the
     * descriptor API; the JDBC adapter names its generic value type {@code java.lang.Object}, so that is used here.
     * Not yet confirmed in Designer (see design section 6.2).
     */
    protected static final String OBJECT_TYPE = "java.lang.Object";

    /**
     * Reads a pipeline value as text, whatever its runtime type.
     * <p>
     * Why: a flow may map an Integer or Boolean into a field that Designer shows as a string; {@code toString()}
     * keeps those working where {@code IDataUtil.getString} would return null.
     * <p>
     * Side effects: none; the cursor position is not changed.
     *
     * @param cursor an open cursor on the input record; the caller destroys it.
     * @param key    pipeline field name.
     * @return the value's text, or null when the field is absent or null.
     */
    protected static String text(IDataCursor cursor, String key) {
        Object value = IDataUtil.get(cursor, key);
        return value == null ? null : value.toString();
    }

    /**
     * Declares one field of the service input signature, the way the JDBC adapter's DynamicSQL does.
     * <p>
     * Why: a hidden read-only bean property holds the pipeline field name and is bound to the
     * {@code INPUT_FIELD_NAMES} resource domain, with a second hidden property naming its type. Both properties
     * must also be listed in the service's {@code createGroup(...)} call, otherwise Designer shows a blank extra
     * tab named after the service.
     * <p>
     * Side effects: marks both properties hidden and registers the domain binding on the descriptor.
     *
     * @param descriptor   the descriptor being filled in {@code fillWmTemplateDescriptor}.
     * @param nameProperty bean property whose getter returns the pipeline field name.
     * @param typeProperty bean property whose getter returns the field type.
     * @throws ResourceException if the ADK rejects the binding.
     */
    protected static void declareInputField(WmTemplateDescriptor descriptor, String nameProperty,
                                            String typeProperty) throws ResourceException {
        descriptor.setHidden(nameProperty);
        descriptor.setHidden(typeProperty);
        descriptor.setResourceDomain(nameProperty, WmTemplateDescriptor.INPUT_FIELD_NAMES,
                new String[]{typeProperty});
    }

    /**
     * Output counterpart of {@link #declareInputField}: same pattern, bound to the {@code OUTPUT_FIELD_NAMES}
     * domain. Both properties must also be listed in the service's {@code createGroup(...)} call.
     *
     * @param descriptor   the descriptor being filled in {@code fillWmTemplateDescriptor}.
     * @param nameProperty bean property whose getter returns the pipeline field name.
     * @param typeProperty bean property whose getter returns the field type.
     * @throws ResourceException if the ADK rejects the binding.
     */
    protected static void declareOutputField(WmTemplateDescriptor descriptor, String nameProperty,
                                             String typeProperty) throws ResourceException {
        descriptor.setHidden(nameProperty);
        descriptor.setHidden(typeProperty);
        descriptor.setResourceDomain(nameProperty, WmTemplateDescriptor.OUTPUT_FIELD_NAMES,
                new String[]{typeProperty});
    }

    /**
     * Converts a remote file entry into a pipeline document.
     * <p>
     * Why: the attribute services (SftpStat, and any later one) must present a file the same way, so the field
     * names live in one place. SftpList builds its own, shorter document and is deliberately not changed here.
     * <p>
     * Side effects: allocates a new {@link IData}; the caller owns it.
     *
     * @param e the entry to convert, not null.
     * @return a document with {@code name, path, size, modifiedSec, accessedSec, permissions, uid, gid, type,
     *         isDirectory, isRegularFile, isSymlink}. {@code uid} and {@code gid} are numeric ids: SFTP v3
     *         carries no owner names.
     */
    protected static IData entryData(RemoteEntry e) {
        IData doc = IDataFactory.create();
        IDataCursor c = doc.getCursor();
        IDataUtil.put(c, "name", e.name());
        IDataUtil.put(c, "path", e.path());
        IDataUtil.put(c, "size", e.size());
        IDataUtil.put(c, "modifiedSec", e.modifiedSec());
        IDataUtil.put(c, "accessedSec", e.accessedSec());
        IDataUtil.put(c, "permissions", e.permissions());
        IDataUtil.put(c, "uid", e.uid());
        IDataUtil.put(c, "gid", e.gid());
        IDataUtil.put(c, "type", e.type().name());
        IDataUtil.put(c, "isDirectory", e.type() == RemoteEntry.Type.DIRECTORY);
        IDataUtil.put(c, "isRegularFile", e.type() == RemoteEntry.Type.FILE);
        IDataUtil.put(c, "isSymlink", e.type() == RemoteEntry.Type.SYMLINK);
        c.destroy();
        return doc;
    }

    @Override
    public void fillWmTemplateDescriptor(WmTemplateDescriptor descriptor, Locale locale)
            throws ResourceException {
        WxSshAdapter adapter = WxSshAdapter.getInstance();
        if (adapter.getAdapterResourceBundleManager() != null) {
            descriptor.setDescriptions(adapter.getAdapterResourceBundleManager(), locale);
        }
    }
}
