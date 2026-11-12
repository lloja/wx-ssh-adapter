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
 * Adapter service template that creates a symbolic link on the server over SFTP.
 * <p>
 * Why: lets a flow publish a "current" pointer (for example {@code current -> release-42}) without copying data.
 * <p>
 * Settings precedence: {@code linkPath} and {@code target} are declared as input fields of the service
 * signature. A non-blank value supplied in the pipeline overrides the matching value in the "SFTP Symlink
 * Settings" panel. Both are required. {@code target} is stored exactly as given and may be relative to the
 * link's directory. See {@link SftpSettings}.
 * <p>
 * Output: {@code success}, always true; every failure is thrown, including an existing {@code linkPath}. The
 * target is not checked, so a dangling link can be created. Not every server allows symbolic links.
 * <p>
 * Side effects: opens an SFTP channel on the active SSH session and creates a link on the server.
 */
public class SftpSymlinkService extends SshAdapterService {

    static final String INPUT_LINK_PATH = "linkPath";
    static final String INPUT_TARGET = "target";

    private String defaultLinkPath = "";
    private String defaultTarget = "";

    public SftpSymlinkService() {
        super();
    }

    @Override
    public WmRecord execute(WmManagedConnection conn, WmRecord inputRecord) throws ResourceException {
        SshSession session = getSession(conn);

        IDataCursor in = inputRecord != null ? inputRecord.getCursor() : null;
        String inLink = null;
        String inTarget = null;
        if (in != null) {
            inLink = text(in, INPUT_LINK_PATH);
            inTarget = text(in, INPUT_TARGET);
            in.destroy();
        }

        String link = required(INPUT_LINK_PATH, SftpSettings.resolveText(inLink, defaultLinkPath));
        String target = required(INPUT_TARGET, SftpSettings.resolveText(inTarget, defaultTarget));

        try {
            session.sftpSymlink(link, target);
        } catch (SshAdapterException ex) {
            throw mapException(ex);
        }
        return successRecord();
    }

    @Override
    public void fillWmTemplateDescriptor(WmTemplateDescriptor descriptor, Locale locale)
            throws ResourceException {
        super.fillWmTemplateDescriptor(descriptor, locale);

        descriptor.createGroup("SFTP Symlink Settings", new String[]{
            "defaultLinkPath", "defaultTarget",
            "linkPathInputField", "linkPathInputFieldType",
            "targetInputField", "targetInputFieldType"
        });

        declareInputField(descriptor, "linkPathInputField", "linkPathInputFieldType");
        declareInputField(descriptor, "targetInputField", "targetInputFieldType");
    }

    /** Read-only: the pipeline field names of the declared inputs. Discovered by the ADK as bean properties. */
    public String getLinkPathInputField() { return INPUT_LINK_PATH; }
    public String getLinkPathInputFieldType() { return STRING_TYPE; }

    public String getTargetInputField() { return INPUT_TARGET; }
    public String getTargetInputFieldType() { return STRING_TYPE; }

    public String getDefaultLinkPath() { return defaultLinkPath; }
    public void setDefaultLinkPath(String defaultLinkPath) { this.defaultLinkPath = defaultLinkPath; }

    public String getDefaultTarget() { return defaultTarget; }
    public void setDefaultTarget(String defaultTarget) { this.defaultTarget = defaultTarget; }
}
