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
 * Adapter service template that renames or moves a remote path over SFTP.
 * <p>
 * Why: the usual "move to /done after processing" step, with an explicit decision about an existing target
 * instead of a silent replacement.
 * <p>
 * Settings precedence: {@code fromPath}, {@code toPath}, {@code overwrite} and {@code createParents} are declared as input fields of
 * the service signature. A non-blank value supplied in the pipeline overrides the matching value in the "SFTP
 * Rename Settings" panel. Both paths are required. {@code overwrite} defaults to <b>false</b>: an existing
 * target makes the call fail. {@code createParents} defaults to <b>false</b>: SFTP rename never creates folders, so
 * a missing target folder makes the call fail with a message naming that folder; true creates the folder chain
 * first. See {@link SftpSettings}.
 * <p>
 * Output: {@code success}, always true; every failure is thrown. With {@code overwrite} true a target that is a
 * directory is never replaced; see {@link SshSession#sftpRename(String, String, boolean)} for when the
 * replacement is atomic and when it is delete-then-rename.
 * <p>
 * Side effects: opens an SFTP channel on the active SSH session and moves a path on the server; with
 * {@code overwrite} it may delete the existing target.
 */
public class SftpRenameService extends SshAdapterService {

    static final String INPUT_FROM_PATH = "fromPath";
    static final String INPUT_TO_PATH = "toPath";
    static final String INPUT_OVERWRITE = "overwrite";
    static final String INPUT_CREATE_PARENTS = "createParents";

    private String defaultFromPath = "";
    private String defaultToPath = "";
    private String overwrite = "false";
    private String createParents = "false";

    public SftpRenameService() {
        super();
    }

    @Override
    public WmRecord execute(WmManagedConnection conn, WmRecord inputRecord) throws ResourceException {
        SshSession session = getSession(conn);

        IDataCursor in = inputRecord != null ? inputRecord.getCursor() : null;
        String inFrom = null;
        String inTo = null;
        String inOverwrite = null;
        String inCreateParents = null;
        if (in != null) {
            inFrom = text(in, INPUT_FROM_PATH);
            inTo = text(in, INPUT_TO_PATH);
            inOverwrite = text(in, INPUT_OVERWRITE);
            inCreateParents = text(in, INPUT_CREATE_PARENTS);
            in.destroy();
        }

        String from = required(INPUT_FROM_PATH, SftpSettings.resolveText(inFrom, defaultFromPath));
        String to = required(INPUT_TO_PATH, SftpSettings.resolveText(inTo, defaultToPath));
        boolean replace = SftpSettings.resolveBoolean(inOverwrite, overwrite, false);
        boolean mkParents = SftpSettings.resolveBoolean(inCreateParents, createParents, false);

        try {
            session.sftpRename(from, to, replace, mkParents);
        } catch (SshAdapterException ex) {
            throw mapException(ex);
        }
        return successRecord();
    }

    @Override
    public void fillWmTemplateDescriptor(WmTemplateDescriptor descriptor, Locale locale)
            throws ResourceException {
        super.fillWmTemplateDescriptor(descriptor, locale);

        descriptor.createGroup("SFTP Rename Settings", new String[]{
            "defaultFromPath", "defaultToPath", "overwrite", "createParents",
            "fromPathInputField", "fromPathInputFieldType",
            "toPathInputField", "toPathInputFieldType",
            "overwriteInputField", "overwriteInputFieldType",
            "createParentsInputField", "createParentsInputFieldType"
        });

        declareInputField(descriptor, "fromPathInputField", "fromPathInputFieldType");
        declareInputField(descriptor, "toPathInputField", "toPathInputFieldType");
        declareInputField(descriptor, "overwriteInputField", "overwriteInputFieldType");
        declareInputField(descriptor, "createParentsInputField", "createParentsInputFieldType");
    }

    /** Read-only: the pipeline field names of the declared inputs. Discovered by the ADK as bean properties. */
    public String getFromPathInputField() { return INPUT_FROM_PATH; }
    public String getFromPathInputFieldType() { return STRING_TYPE; }

    public String getToPathInputField() { return INPUT_TO_PATH; }
    public String getToPathInputFieldType() { return STRING_TYPE; }

    public String getOverwriteInputField() { return INPUT_OVERWRITE; }
    public String getOverwriteInputFieldType() { return STRING_TYPE; }

    public String getCreateParentsInputField() { return INPUT_CREATE_PARENTS; }
    public String getCreateParentsInputFieldType() { return STRING_TYPE; }

    public String getDefaultFromPath() { return defaultFromPath; }
    public void setDefaultFromPath(String defaultFromPath) { this.defaultFromPath = defaultFromPath; }

    public String getDefaultToPath() { return defaultToPath; }
    public void setDefaultToPath(String defaultToPath) { this.defaultToPath = defaultToPath; }

    public String getOverwrite() { return overwrite; }
    public void setOverwrite(String overwrite) { this.overwrite = overwrite; }

    public String getCreateParents() { return createParents; }
    public void setCreateParents(String createParents) { this.createParents = createParents; }
}
