package com.wm.adapter.wxssh.service;

import com.luisloja.wxssh.core.SshAdapterException;
import com.luisloja.wxssh.core.SshSession;
import com.wm.adk.cci.record.WmRecord;
import com.wm.adk.connection.WmManagedConnection;
import com.wm.adk.metadata.WmTemplateDescriptor;
import com.wm.data.IDataCursor;
import jakarta.resource.ResourceException;
import java.util.Locale;

/**
 * Adapter service template that sets the size of a remote file over SFTP, cutting it or extending it with zeros.
 * <p>
 * Why: empties a file in place (size 0) while keeping its owner, mode and links, which deleting and re-creating
 * it would not.
 * <p>
 * Settings precedence: {@code remotePath} and {@code size} are declared as input fields of the service
 * signature. A non-blank value supplied in the pipeline overrides the matching value in the "SFTP Truncate
 * Settings" panel. <b>Both are required</b>: a missing size is never read as 0, because that would empty a file
 * by accident. Send {@code 0} on purpose to empty it. See {@link SftpSettings#resolveLong}.
 * <p>
 * Output: {@code success}, always true; every failure is thrown.
 * <p>
 * Side effects: opens an SFTP channel on the active SSH session and changes the content of a file on the server.
 */
public class SftpTruncateService extends SshAdapterService {

    static final String INPUT_REMOTE_PATH = "remotePath";
    static final String INPUT_SIZE = "size";

    private String defaultRemotePath = "";
    private String defaultSize = "";

    public SftpTruncateService() {
        super();
    }

    @Override
    public WmRecord execute(WmManagedConnection conn, WmRecord inputRecord) throws ResourceException {
        SshSession session = getSession(conn);

        IDataCursor in = inputRecord != null ? inputRecord.getCursor() : null;
        String inPath = null;
        String inSize = null;
        if (in != null) {
            inPath = text(in, INPUT_REMOTE_PATH);
            inSize = text(in, INPUT_SIZE);
            in.destroy();
        }

        String path = required(INPUT_REMOTE_PATH, SftpSettings.resolveText(inPath, defaultRemotePath));
        Long size;
        try {
            size = SftpSettings.resolveLong(INPUT_SIZE, inSize, defaultSize, 0, Long.MAX_VALUE);
        } catch (IllegalArgumentException ex) {
            throw invalidInput(ex);
        }
        if (size == null) {
            throw adapterError(INPUT_SIZE + " is required (use 0 to empty the file)");
        }

        try {
            session.sftpTruncate(path, size);
        } catch (SshAdapterException ex) {
            throw mapException(ex);
        }
        return successRecord();
    }

    @Override
    public void fillWmTemplateDescriptor(WmTemplateDescriptor descriptor, Locale locale)
            throws ResourceException {
        super.fillWmTemplateDescriptor(descriptor, locale);

        descriptor.createGroup("SFTP Truncate Settings", new String[]{
            "defaultRemotePath", "defaultSize",
            "remotePathInputField", "remotePathInputFieldType",
            "sizeInputField", "sizeInputFieldType"
        });

        declareInputField(descriptor, "remotePathInputField", "remotePathInputFieldType");
        declareInputField(descriptor, "sizeInputField", "sizeInputFieldType");
    }

    /** Read-only: the pipeline field names of the declared inputs. Discovered by the ADK as bean properties. */
    public String getRemotePathInputField() { return INPUT_REMOTE_PATH; }
    public String getRemotePathInputFieldType() { return STRING_TYPE; }

    public String getSizeInputField() { return INPUT_SIZE; }
    public String getSizeInputFieldType() { return STRING_TYPE; }

    public String getDefaultRemotePath() { return defaultRemotePath; }
    public void setDefaultRemotePath(String defaultRemotePath) { this.defaultRemotePath = defaultRemotePath; }

    public String getDefaultSize() { return defaultSize; }
    public void setDefaultSize(String defaultSize) { this.defaultSize = defaultSize; }
}
