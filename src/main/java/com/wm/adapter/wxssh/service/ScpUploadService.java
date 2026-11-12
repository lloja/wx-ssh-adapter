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
 * Adapter service template that uploads a local file, a directory tree, or content from the pipeline with SCP.
 * <p>
 * Why: some servers offer SCP but no SFTP (embedded devices, restricted accounts). Prefer SftpPut when SFTP is
 * available: it can write atomically and creates no surprises with directories.
 * <p>
 * <b>Directory behaviour (SCP, not this adapter):</b> uploading a directory to a destination that does not exist
 * creates it and copies the directory's <em>contents</em> into it; uploading to a destination that already exists
 * creates the directory <em>inside</em> it under its own name. Running the same upload twice therefore gives
 * {@code dest/a.txt} the first time and {@code dest/src/a.txt} the second. A directory is refused unless
 * {@code recursive} is true. SCP never creates missing parent folders, and an existing file is replaced.
 * <p>
 * Source selection (first match wins): {@code localFilePath} (file, or directory when {@code recursive}),
 * {@code bytes}, {@code content} (encoded with {@code charset}). Settings precedence: {@code remotePath},
 * {@code localFilePath}, {@code recursive} and {@code charset} are declared as input fields; a non-blank pipeline
 * value overrides the matching value in the "SCP Upload Settings" panel. See {@link SftpSettings}.
 * <p>
 * Output: {@code remotePath} and, for a single file or content, {@code bytesWritten}.
 * <p>
 * Side effects: opens one exec channel on the active SSH session, which runs {@code scp -t} on the server;
 * creates or replaces remote files; reads local files.
 */
public class ScpUploadService extends SshAdapterService {

    static final String INPUT_REMOTE_PATH = "remotePath";
    static final String INPUT_LOCAL_FILE_PATH = "localFilePath";
    static final String INPUT_RECURSIVE = "recursive";
    static final String INPUT_CHARSET = "charset";
    static final String INPUT_BYTES = "bytes";
    static final String INPUT_CONTENT = "content";

    private String defaultRemotePath = "";
    private String defaultLocalPath = "";
    private String recursive = "false";
    private String charset = "UTF-8";

    public ScpUploadService() {
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
        String content = null;
        byte[] bytes = null;
        if (in != null) {
            inRemote = text(in, INPUT_REMOTE_PATH);
            inLocal = text(in, INPUT_LOCAL_FILE_PATH);
            inRecursive = text(in, INPUT_RECURSIVE);
            inCharset = text(in, INPUT_CHARSET);
            content = IDataUtil.getString(in, INPUT_CONTENT);
            if (IDataUtil.get(in, INPUT_BYTES) instanceof byte[] b) {
                bytes = b;
            }
            in.destroy();
        }

        String remote = required(INPUT_REMOTE_PATH, SftpSettings.resolveText(inRemote, defaultRemotePath));
        String local = SftpSettings.resolveText(inLocal, defaultLocalPath);
        boolean rec = SftpSettings.resolveBoolean(inRecursive, recursive, false);
        Charset cs;
        try {
            cs = ShellSettings.resolveCharset(inCharset, charset);
        } catch (IllegalArgumentException ex) {
            throw invalidInput(ex);
        }

        Long written = null;
        try {
            if (local != null && !local.isBlank()) {
                File f = new File(local);
                if (!f.exists()) {
                    throw adapterError("Local path does not exist: " + local);
                }
                session.scpUpload(local, remote, rec);
                if (f.isFile()) {
                    written = f.length();
                }
            } else if (bytes != null) {
                session.scpUploadBytes(bytes, remote);
                written = (long) bytes.length;
            } else if (content != null) {
                byte[] raw = content.getBytes(cs);
                session.scpUploadBytes(raw, remote);
                written = (long) raw.length;
            } else {
                throw adapterError("One of localFilePath, bytes, or content must be provided for ScpUpload");
            }
        } catch (SshAdapterException ex) {
            throw mapException(ex);
        }

        WmRecord out = WmRecord.create();
        IDataCursor oc = out.getCursor();
        IDataUtil.put(oc, "remotePath", remote);
        if (written != null) {
            IDataUtil.put(oc, "bytesWritten", written);
        }
        oc.destroy();
        return out;
    }

    @Override
    public void fillWmTemplateDescriptor(WmTemplateDescriptor descriptor, Locale locale)
            throws ResourceException {
        super.fillWmTemplateDescriptor(descriptor, locale);

        // The *InputField / *InputFieldType properties are hidden helpers (see declareInputField in the base
        // class). They must be members of this group, or Designer shows a blank tab named after the service.
        descriptor.createGroup("SCP Upload Settings", new String[]{
            "defaultRemotePath", "defaultLocalPath", "recursive", "charset",
            "remotePathInputField", "remotePathInputFieldType",
            "localFilePathInputField", "localFilePathInputFieldType",
            "recursiveInputField", "recursiveInputFieldType",
            "charsetInputField", "charsetInputFieldType",
            "contentInputField", "contentInputFieldType",
            "bytesInputField", "bytesInputFieldType",
            "remotePathOutputField", "remotePathOutputFieldType",
            "bytesWrittenOutputField", "bytesWrittenOutputFieldType"
        });

        declareInputField(descriptor, "remotePathInputField", "remotePathInputFieldType");
        declareInputField(descriptor, "localFilePathInputField", "localFilePathInputFieldType");
        declareInputField(descriptor, "recursiveInputField", "recursiveInputFieldType");
        declareInputField(descriptor, "charsetInputField", "charsetInputFieldType");
        declareInputField(descriptor, "contentInputField", "contentInputFieldType");
        declareInputField(descriptor, "bytesInputField", "bytesInputFieldType");
        declareOutputField(descriptor, "remotePathOutputField", "remotePathOutputFieldType");
        declareOutputField(descriptor, "bytesWrittenOutputField", "bytesWrittenOutputFieldType");
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

    public String getContentInputField() { return INPUT_CONTENT; }
    public String getContentInputFieldType() { return STRING_TYPE; }

    public String getBytesInputField() { return INPUT_BYTES; }
    public String getBytesInputFieldType() { return OBJECT_TYPE; }

    public String getRemotePathOutputField() { return "remotePath"; }
    public String getRemotePathOutputFieldType() { return STRING_TYPE; }

    public String getBytesWrittenOutputField() { return "bytesWritten"; }
    public String getBytesWrittenOutputFieldType() { return "java.lang.Long"; }

    public String getDefaultRemotePath() { return defaultRemotePath; }
    public void setDefaultRemotePath(String defaultRemotePath) { this.defaultRemotePath = defaultRemotePath; }

    public String getDefaultLocalPath() { return defaultLocalPath; }
    public void setDefaultLocalPath(String defaultLocalPath) { this.defaultLocalPath = defaultLocalPath; }

    public String getRecursive() { return recursive; }
    public void setRecursive(String recursive) { this.recursive = recursive; }

    public String getCharset() { return charset; }
    public void setCharset(String charset) { this.charset = charset; }
}
