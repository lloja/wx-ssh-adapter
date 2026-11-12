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
 * Adapter service template that sets the access and/or modification time of a remote path over SFTP.
 * <p>
 * Why: after an upload, a flow can give the remote file the timestamp of its source, or touch a marker file.
 * <p>
 * Settings precedence: {@code remotePath}, {@code modifiedSec} and {@code accessedSec} are declared as input
 * fields of the service signature. A non-blank value supplied in the pipeline overrides the matching value in
 * the "SFTP SetTimes Settings" panel. {@code remotePath} is required. At least one time must be given; a blank
 * one is left unchanged (the service reads the current value and writes it back, because SFTP version 3 sets both
 * in one request). Times are whole seconds since 1970-01-01 UTC, the same unit {@code SftpStat} reports in
 * {@code modifiedSec} and {@code accessedSec}. See {@link SftpSettings#resolveLong}.
 * <p>
 * Output: {@code success}, always true; every failure is thrown.
 * <p>
 * Side effects: opens an SFTP channel on the active SSH session; one stat when only one time is given, then one
 * set-attributes request that changes timestamps on the server.
 */
public class SftpSetTimesService extends SshAdapterService {

    static final String INPUT_REMOTE_PATH = "remotePath";
    static final String INPUT_MODIFIED_SEC = "modifiedSec";
    static final String INPUT_ACCESSED_SEC = "accessedSec";

    private String defaultRemotePath = "";
    private String defaultModifiedSec = "";
    private String defaultAccessedSec = "";

    public SftpSetTimesService() {
        super();
    }

    @Override
    public WmRecord execute(WmManagedConnection conn, WmRecord inputRecord) throws ResourceException {
        SshSession session = getSession(conn);

        IDataCursor in = inputRecord != null ? inputRecord.getCursor() : null;
        String inPath = null;
        String inModified = null;
        String inAccessed = null;
        if (in != null) {
            inPath = text(in, INPUT_REMOTE_PATH);
            inModified = text(in, INPUT_MODIFIED_SEC);
            inAccessed = text(in, INPUT_ACCESSED_SEC);
            in.destroy();
        }

        String path = required(INPUT_REMOTE_PATH, SftpSettings.resolveText(inPath, defaultRemotePath));
        Long modified;
        Long accessed;
        try {
            modified = SftpSettings.resolveLong(INPUT_MODIFIED_SEC, inModified, defaultModifiedSec, 0, Long.MAX_VALUE);
            accessed = SftpSettings.resolveLong(INPUT_ACCESSED_SEC, inAccessed, defaultAccessedSec, 0, Long.MAX_VALUE);
        } catch (IllegalArgumentException ex) {
            throw invalidInput(ex);
        }

        try {
            session.sftpSetTimes(path, accessed, modified);
        } catch (SshAdapterException ex) {
            throw mapException(ex);
        }
        return successRecord();
    }

    @Override
    public void fillWmTemplateDescriptor(WmTemplateDescriptor descriptor, Locale locale)
            throws ResourceException {
        super.fillWmTemplateDescriptor(descriptor, locale);

        descriptor.createGroup("SFTP SetTimes Settings", new String[]{
            "defaultRemotePath", "defaultModifiedSec", "defaultAccessedSec",
            "remotePathInputField", "remotePathInputFieldType",
            "modifiedSecInputField", "modifiedSecInputFieldType",
            "accessedSecInputField", "accessedSecInputFieldType"
        });

        declareInputField(descriptor, "remotePathInputField", "remotePathInputFieldType");
        declareInputField(descriptor, "modifiedSecInputField", "modifiedSecInputFieldType");
        declareInputField(descriptor, "accessedSecInputField", "accessedSecInputFieldType");
    }

    /** Read-only: the pipeline field names of the declared inputs. Discovered by the ADK as bean properties. */
    public String getRemotePathInputField() { return INPUT_REMOTE_PATH; }
    public String getRemotePathInputFieldType() { return STRING_TYPE; }

    public String getModifiedSecInputField() { return INPUT_MODIFIED_SEC; }
    public String getModifiedSecInputFieldType() { return STRING_TYPE; }

    public String getAccessedSecInputField() { return INPUT_ACCESSED_SEC; }
    public String getAccessedSecInputFieldType() { return STRING_TYPE; }

    public String getDefaultRemotePath() { return defaultRemotePath; }
    public void setDefaultRemotePath(String defaultRemotePath) { this.defaultRemotePath = defaultRemotePath; }

    public String getDefaultModifiedSec() { return defaultModifiedSec; }
    public void setDefaultModifiedSec(String defaultModifiedSec) { this.defaultModifiedSec = defaultModifiedSec; }

    public String getDefaultAccessedSec() { return defaultAccessedSec; }
    public void setDefaultAccessedSec(String defaultAccessedSec) { this.defaultAccessedSec = defaultAccessedSec; }
}
