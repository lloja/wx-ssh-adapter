package com.wm.adapter.wxssh.service;

import com.luisloja.wxssh.core.SshAdapterException;
import com.luisloja.wxssh.core.SshSession;
import com.wm.adk.cci.record.WmRecord;
import com.wm.adk.connection.WmManagedConnection;
import com.wm.adk.error.AdapterException;
import com.wm.adk.metadata.WmTemplateDescriptor;
import com.wm.data.IDataCursor;
import com.wm.data.IDataUtil;
import jakarta.resource.ResourceException;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Adapter service template for uploading files to a remote SFTP server.
 * <p>
 * Why: Allows Integration Server services to upload string content, byte arrays, or local files to a remote
 * destination with optional atomic staging (.part file rename) support.
 * <p>
 * Settings precedence: {@code remotePath}, {@code localFilePath}, {@code atomic} and {@code charset} are declared
 * as input fields of the service signature. A non-blank value supplied in the pipeline overrides the matching
 * value in the service's "SFTP Put Settings" panel; a blank or missing input leaves the panel value in effect.
 * See {@link SftpSettings} for the exact rules.
 * <p>
 * Side effects: Opens an SFTP channel, transfers data over the network, and creates/replaces remote files.
 */
public class SftpPutService extends SshAdapterService {

    /** Pipeline field names of the overridable settings; also the names shown in the input signature. */
    static final String INPUT_REMOTE_PATH = "remotePath";
    static final String INPUT_LOCAL_FILE_PATH = "localFilePath";
    static final String INPUT_ATOMIC = "atomic";
    static final String INPUT_CHARSET = "charset";

    private String defaultRemotePath = "";
    private String defaultLocalPath = "";
    private String atomic = "true";
    private String charset = "UTF-8";

    public SftpPutService() {
        super();
    }

    @Override
    public WmRecord execute(WmManagedConnection conn, WmRecord inputRecord) throws ResourceException {
        SshSession session = getSession(conn);

        IDataCursor inCursor = inputRecord != null ? inputRecord.getCursor() : null;
        String inRemote = null;
        String inLocal = null;
        String inAtomic = null;
        String inCharset = null;
        String text = null;
        byte[] payloadBytes = null;

        if (inCursor != null) {
            inRemote = text(inCursor, INPUT_REMOTE_PATH);
            inLocal = text(inCursor, INPUT_LOCAL_FILE_PATH);
            inAtomic = text(inCursor, INPUT_ATOMIC);
            inCharset = text(inCursor, INPUT_CHARSET);
            text = IDataUtil.getString(inCursor, "content");
            Object bObj = IDataUtil.get(inCursor, "bytes");
            if (bObj instanceof byte[] b) {
                payloadBytes = b;
            }
            inCursor.destroy();
        }

        String remote = SftpSettings.resolveText(inRemote, defaultRemotePath);
        String local = SftpSettings.resolveText(inLocal, defaultLocalPath);
        String enc = SftpSettings.resolveCharset(inCharset, charset);
        boolean isAtomic = SftpSettings.resolveBoolean(inAtomic, atomic);

        if (remote == null || remote.isBlank()) {
            throw adapterError("remotePath is required");
        }

        Charset c;
        try {
            c = Charset.forName(enc);
        } catch (Exception ex) {
            c = StandardCharsets.UTF_8;
        }

        long bytesWritten = 0L;
        try {
            if (local != null && !local.isBlank()) {
                File f = new File(local);
                if (!f.exists()) {
                    throw adapterError("Local file does not exist: " + local);
                }
                session.sftpPutFromFile(local, remote, isAtomic);
                bytesWritten = f.length();
            } else if (payloadBytes != null) {
                session.sftpPut(payloadBytes, remote, isAtomic);
                bytesWritten = payloadBytes.length;
            } else if (text != null) {
                byte[] raw = text.getBytes(c);
                session.sftpPut(raw, remote, isAtomic);
                bytesWritten = raw.length;
            } else {
                throw adapterError("One of localFilePath, content, or bytes must be provided for SftpPut");
            }
        } catch (SshAdapterException ex) {
            throw mapException(ex);
        }

        WmRecord outputRecord = WmRecord.create();
        IDataCursor outCursor = outputRecord.getCursor();
        IDataUtil.put(outCursor, "remotePath", remote);
        IDataUtil.put(outCursor, "bytesWritten", bytesWritten);
        outCursor.destroy();

        return outputRecord;
    }

    @Override
    public void fillWmTemplateDescriptor(WmTemplateDescriptor descriptor, Locale locale)
            throws ResourceException {
        super.fillWmTemplateDescriptor(descriptor, locale);

        // The eight *InputField / *InputFieldType properties are hidden helpers (see declareInputField in the base
        // class). They must be members of this group: a hidden property that belongs to no group lands in a
        // default group named after the service, and Designer then shows that group as a blank tab ("SftpPut").
        descriptor.createGroup("SFTP Put Settings", new String[]{
            "defaultRemotePath", "defaultLocalPath", "atomic", "charset",
            "remotePathInputField", "remotePathInputFieldType",
            "localFilePathInputField", "localFilePathInputFieldType",
            "atomicInputField", "atomicInputFieldType",
            "charsetInputField", "charsetInputFieldType"
        });
        // No descriptor.setValidValues(...): on a service template the value list becomes a resource-domain name
        // that the connection cannot resolve (ART.114.244). "atomic" stays a plain "true"/"false" string.

        declareInputField(descriptor, "remotePathInputField", "remotePathInputFieldType");
        declareInputField(descriptor, "localFilePathInputField", "localFilePathInputFieldType");
        declareInputField(descriptor, "atomicInputField", "atomicInputFieldType");
        declareInputField(descriptor, "charsetInputField", "charsetInputFieldType");
    }

    /** Read-only: the pipeline field names of the declared inputs. Discovered by the ADK as bean properties. */
    public String getRemotePathInputField() { return INPUT_REMOTE_PATH; }
    public String getRemotePathInputFieldType() { return STRING_TYPE; }

    public String getLocalFilePathInputField() { return INPUT_LOCAL_FILE_PATH; }
    public String getLocalFilePathInputFieldType() { return STRING_TYPE; }

    public String getAtomicInputField() { return INPUT_ATOMIC; }
    public String getAtomicInputFieldType() { return STRING_TYPE; }

    public String getCharsetInputField() { return INPUT_CHARSET; }
    public String getCharsetInputFieldType() { return STRING_TYPE; }

    public String getDefaultRemotePath() { return defaultRemotePath; }
    public void setDefaultRemotePath(String defaultRemotePath) { this.defaultRemotePath = defaultRemotePath; }

    public String getDefaultLocalPath() { return defaultLocalPath; }
    public void setDefaultLocalPath(String defaultLocalPath) { this.defaultLocalPath = defaultLocalPath; }

    public String getAtomic() { return atomic; }
    public void setAtomic(String atomic) { this.atomic = atomic; }

    public String getCharset() { return charset; }
    public void setCharset(String charset) { this.charset = charset; }
}
