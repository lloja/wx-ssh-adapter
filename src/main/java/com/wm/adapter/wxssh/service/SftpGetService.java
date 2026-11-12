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
 * Adapter service template for downloading files from a remote SFTP server.
 * <p>
 * Why: Allows Integration Server services to download remote files directly into memory (as string content or
 * byte arrays) or streamed straight to a local file on the Integration Server filesystem.
 * <p>
 * Settings precedence: {@code remotePath}, {@code localFilePath} and {@code charset} are declared as input fields
 * of the service signature. A non-blank value supplied in the pipeline overrides the matching value in the
 * service's "SFTP Get Settings" panel; a blank or missing input leaves the panel value in effect. See
 * {@link SftpSettings} for the exact rules.
 * <p>
 * Side effects: Opens an SFTP channel, reads remote file data over the network, and writes to local disk if
 * localFilePath is provided.
 */
public class SftpGetService extends SshAdapterService {

    /** Pipeline field names of the overridable settings; also the names shown in the input signature. */
    static final String INPUT_REMOTE_PATH = "remotePath";
    static final String INPUT_LOCAL_FILE_PATH = "localFilePath";
    static final String INPUT_CHARSET = "charset";

    private String defaultRemotePath = "";
    private String defaultLocalPath = "";
    private String charset = "UTF-8";

    public SftpGetService() {
        super();
    }

    @Override
    public WmRecord execute(WmManagedConnection conn, WmRecord inputRecord) throws ResourceException {
        SshSession session = getSession(conn);

        IDataCursor inCursor = inputRecord != null ? inputRecord.getCursor() : null;
        String inRemote = null;
        String inLocal = null;
        String inCharset = null;

        if (inCursor != null) {
            inRemote = text(inCursor, INPUT_REMOTE_PATH);
            inLocal = text(inCursor, INPUT_LOCAL_FILE_PATH);
            inCharset = text(inCursor, INPUT_CHARSET);
            inCursor.destroy();
        }

        String remote = SftpSettings.resolveText(inRemote, defaultRemotePath);
        String local = SftpSettings.resolveText(inLocal, defaultLocalPath);
        String enc = SftpSettings.resolveCharset(inCharset, charset);

        if (remote == null || remote.isBlank()) {
            throw adapterError("remotePath is required");
        }

        Charset c;
        try {
            c = Charset.forName(enc);
        } catch (Exception ex) {
            c = StandardCharsets.UTF_8;
        }

        WmRecord outputRecord = WmRecord.create();
        IDataCursor outCursor = outputRecord.getCursor();

        try {
            if (local != null && !local.isBlank()) {
                session.sftpGetToFile(remote, local);
                File f = new File(local);
                IDataUtil.put(outCursor, "localFilePath", f.getAbsolutePath());
                IDataUtil.put(outCursor, "bytesRead", f.length());
            } else {
                byte[] data = session.sftpGet(remote);
                IDataUtil.put(outCursor, "bytes", data);
                IDataUtil.put(outCursor, "content", new String(data, c));
                IDataUtil.put(outCursor, "bytesRead", (long) data.length);
            }
        } catch (SshAdapterException ex) {
            throw mapException(ex);
        } finally {
            outCursor.destroy();
        }

        return outputRecord;
    }

    @Override
    public void fillWmTemplateDescriptor(WmTemplateDescriptor descriptor, Locale locale)
            throws ResourceException {
        super.fillWmTemplateDescriptor(descriptor, locale);

        // The six *InputField / *InputFieldType properties are hidden helpers (see declareInputField in the base
        // class). They must be members of this group: a hidden property that belongs to no group lands in a
        // default group named after the service, and Designer then shows that group as a blank tab ("SftpGet").
        descriptor.createGroup("SFTP Get Settings", new String[]{
            "defaultRemotePath", "defaultLocalPath", "charset",
            "remotePathInputField", "remotePathInputFieldType",
            "localFilePathInputField", "localFilePathInputFieldType",
            "charsetInputField", "charsetInputFieldType"
        });

        declareInputField(descriptor, "remotePathInputField", "remotePathInputFieldType");
        declareInputField(descriptor, "localFilePathInputField", "localFilePathInputFieldType");
        declareInputField(descriptor, "charsetInputField", "charsetInputFieldType");
    }

    /** Read-only: the pipeline field names of the declared inputs. Discovered by the ADK as bean properties. */
    public String getRemotePathInputField() { return INPUT_REMOTE_PATH; }
    public String getRemotePathInputFieldType() { return STRING_TYPE; }

    public String getLocalFilePathInputField() { return INPUT_LOCAL_FILE_PATH; }
    public String getLocalFilePathInputFieldType() { return STRING_TYPE; }

    public String getCharsetInputField() { return INPUT_CHARSET; }
    public String getCharsetInputFieldType() { return STRING_TYPE; }

    public String getDefaultRemotePath() { return defaultRemotePath; }
    public void setDefaultRemotePath(String defaultRemotePath) { this.defaultRemotePath = defaultRemotePath; }

    public String getDefaultLocalPath() { return defaultLocalPath; }
    public void setDefaultLocalPath(String defaultLocalPath) { this.defaultLocalPath = defaultLocalPath; }

    public String getCharset() { return charset; }
    public void setCharset(String charset) { this.charset = charset; }
}
