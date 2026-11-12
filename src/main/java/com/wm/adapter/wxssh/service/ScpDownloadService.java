package com.wm.adapter.wxssh.service;

import com.luisloja.wxssh.core.SshAdapterException;
import com.luisloja.wxssh.core.SshSession;
import com.wm.adk.cci.record.WmRecord;
import com.wm.adk.connection.WmManagedConnection;
import com.wm.adk.metadata.WmTemplateDescriptor;
import com.wm.data.IDataCursor;
import com.wm.data.IDataUtil;
import jakarta.resource.ResourceException;
import java.io.File;
import java.nio.charset.Charset;
import java.util.Locale;

/**
 * Adapter service template that downloads a remote file or directory tree with SCP, to a local path or into memory.
 * <p>
 * Why: some servers offer SCP but no SFTP. Prefer SftpGet when SFTP is available.
 * <p>
 * <b>Directory behaviour (SCP, not this adapter):</b> a remote directory is only copied when {@code recursive} is
 * true (default false, like ScpUpload); otherwise the server's {@code scp} refuses it with "not a regular file"
 * and nothing is created locally. A tree can only go to a local path. When the local destination does not exist it
 * becomes the copy; when it already exists as a directory, the remote directory is created inside it under its own
 * name. A directory cannot be loaded into memory and is refused. Local files get the remote modification time.
 * <p>
 * Destination selection: with {@code localFilePath} the data is written there and nothing is held in memory.
 * Without it the file is returned as {@code bytes} and {@code content} (decoded with {@code charset}), bounded by
 * the connection's in-memory limit; a larger file fails with {@code LIMIT_EXCEEDED} and the file-based form must be
 * used. Settings precedence: {@code remotePath}, {@code localFilePath}, {@code recursive} and {@code charset} are declared as input
 * fields; a non-blank pipeline value overrides the matching value in the "SCP Download Settings" panel.
 * <p>
 * Output: {@code localFilePath} and, for a single file, {@code bytesRead}; or {@code bytes}, {@code content} and
 * {@code bytesRead} for an in-memory download.
 * <p>
 * Side effects: opens one exec channel on the active SSH session, which runs {@code scp -f} on the server;
 * creates or overwrites local files.
 */
public class ScpDownloadService extends SshAdapterService {

    static final String INPUT_REMOTE_PATH = "remotePath";
    static final String INPUT_LOCAL_FILE_PATH = "localFilePath";
    static final String INPUT_RECURSIVE = "recursive";
    static final String INPUT_CHARSET = "charset";

    private String defaultRemotePath = "";
    private String defaultLocalPath = "";
    private String recursive = "false";
    private String charset = "UTF-8";

    public ScpDownloadService() {
        super();
    }

    @Override
    public WmRecord execute(WmManagedConnection conn, WmRecord inputRecord) throws ResourceException {
        SshSession session = getSession(conn);

        IDataCursor in = inputRecord != null ? inputRecord.getCursor() : null;
        String inRemote = null;
        String inLocal = null;
        String inRecursive = null;
        String inCharset = null;
        if (in != null) {
            inRemote = text(in, INPUT_REMOTE_PATH);
            inLocal = text(in, INPUT_LOCAL_FILE_PATH);
            inRecursive = text(in, INPUT_RECURSIVE);
            inCharset = text(in, INPUT_CHARSET);
            in.destroy();
        }

        String remote = required(INPUT_REMOTE_PATH, SftpSettings.resolveText(inRemote, defaultRemotePath));
        String local = SftpSettings.resolveText(inLocal, defaultLocalPath);
        boolean rec;
        Charset cs;
        try {
            rec = SftpSettings.resolveBoolean(inRecursive, recursive, false);
            cs = ShellSettings.resolveCharset(inCharset, charset);
        } catch (IllegalArgumentException ex) {
            throw invalidInput(ex);
        }

        WmRecord out = WmRecord.create();
        IDataCursor oc = out.getCursor();
        try {
            if (local != null && !local.isBlank()) {
                session.scpDownload(remote, local, rec);
                File f = new File(local);
                IDataUtil.put(oc, "localFilePath", f.getAbsolutePath());
                if (f.isFile()) {
                    IDataUtil.put(oc, "bytesRead", f.length());
                }
            } else {
                byte[] data = session.scpDownloadBytes(remote);
                IDataUtil.put(oc, "bytes", data);
                IDataUtil.put(oc, "content", new String(data, cs));
                IDataUtil.put(oc, "bytesRead", (long) data.length);
            }
        } catch (SshAdapterException ex) {
            throw mapException(ex);
        } finally {
            oc.destroy();
        }
        return out;
    }

    @Override
    public void fillWmTemplateDescriptor(WmTemplateDescriptor descriptor, Locale locale)
            throws ResourceException {
        super.fillWmTemplateDescriptor(descriptor, locale);

        // The *InputField / *InputFieldType properties are hidden helpers (see declareInputField in the base
        // class). They must be members of this group, or Designer shows a blank tab named after the service.
        descriptor.createGroup("SCP Download Settings", new String[]{
            "defaultRemotePath", "defaultLocalPath", "recursive", "charset",
            "remotePathInputField", "remotePathInputFieldType",
            "localFilePathInputField", "localFilePathInputFieldType",
            "recursiveInputField", "recursiveInputFieldType",
            "charsetInputField", "charsetInputFieldType",
            "bytesOutputField", "bytesOutputFieldType",
            "contentOutputField", "contentOutputFieldType"
        });

        declareInputField(descriptor, "remotePathInputField", "remotePathInputFieldType");
        declareInputField(descriptor, "localFilePathInputField", "localFilePathInputFieldType");
        declareInputField(descriptor, "recursiveInputField", "recursiveInputFieldType");
        declareInputField(descriptor, "charsetInputField", "charsetInputFieldType");
        declareOutputField(descriptor, "bytesOutputField", "bytesOutputFieldType");
        declareOutputField(descriptor, "contentOutputField", "contentOutputFieldType");
    }

    /** Read-only: the pipeline field names of the declared inputs and outputs, found by the ADK as properties. */
    public String getRemotePathInputField() { return INPUT_REMOTE_PATH; }
    public String getRemotePathInputFieldType() { return STRING_TYPE; }

    public String getLocalFilePathInputField() { return INPUT_LOCAL_FILE_PATH; }
    public String getLocalFilePathInputFieldType() { return STRING_TYPE; }

    public String getRecursiveInputField() { return INPUT_RECURSIVE; }
    public String getRecursiveInputFieldType() { return STRING_TYPE; }

    public String getCharsetInputField() { return INPUT_CHARSET; }
    public String getCharsetInputFieldType() { return STRING_TYPE; }

    public String getBytesOutputField() { return "bytes"; }
    public String getBytesOutputFieldType() { return OBJECT_TYPE; }

    public String getContentOutputField() { return "content"; }
    public String getContentOutputFieldType() { return STRING_TYPE; }

    public String getDefaultRemotePath() { return defaultRemotePath; }
    public void setDefaultRemotePath(String defaultRemotePath) { this.defaultRemotePath = defaultRemotePath; }

    public String getDefaultLocalPath() { return defaultLocalPath; }
    public void setDefaultLocalPath(String defaultLocalPath) { this.defaultLocalPath = defaultLocalPath; }

    public String getRecursive() { return recursive; }
    public void setRecursive(String recursive) { this.recursive = recursive; }

    public String getCharset() { return charset; }
    public void setCharset(String charset) { this.charset = charset; }
}
