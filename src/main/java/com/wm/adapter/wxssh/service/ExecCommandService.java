package com.wm.adapter.wxssh.service;

import com.luisloja.wxssh.core.ExecRequest;
import com.luisloja.wxssh.core.ExecResult;
import com.luisloja.wxssh.core.SshAdapterException;
import com.luisloja.wxssh.core.SshSession;
import com.wm.adk.cci.record.WmRecord;
import com.wm.adk.connection.WmManagedConnection;
import com.wm.adk.metadata.WmTemplateDescriptor;
import com.wm.data.IDataCursor;
import com.wm.data.IDataUtil;
import jakarta.resource.ResourceException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Adapter service template for executing commands on a remote SSH host.
 * <p>
 * Why: Allows webMethods Integration Server flow and Java services to invoke commands via SSH and capture
 * stdout, stderr, exit status, and termination signals.
 * <p>
 * Settings precedence: {@code command}, {@code timeoutSeconds} and {@code allocatePty} are declared as input
 * fields of the service signature. A value supplied in the pipeline overrides the value configured in the
 * service's "Command Settings" panel, so the panel can be left empty when the flow provides everything. See
 * {@link ExecSettings} for the exact rules.
 * <p>
 * Side effects: Opens an SSH execution channel on the remote host, starts remote process execution,
 * streams stdin (if provided), drains stdout/stderr, and waits for process completion or timeout.
 */
public class ExecCommandService extends SshAdapterService {

    /** Pipeline field names of the three overridable settings; also the names shown in the input signature. */
    static final String INPUT_COMMAND = "command";
    static final String INPUT_TIMEOUT = "timeoutSeconds";
    static final String INPUT_PTY = "allocatePty";

    /**
     * Pipeline field carrying the working directory in both directions: as input it is the directory the command
     * starts in, as output it is the directory the shell was in when the command finished. Using one name lets a
     * flow map it straight back into the next call.
     */
    static final String WORKING_DIRECTORY = "workingDirectory";

    private String defaultCommand = "";
    private String timeoutSeconds = "30";
    private String allocatePty = "false";

    public ExecCommandService() {
        super();
    }

    @Override
    public WmRecord execute(WmManagedConnection conn, WmRecord inputRecord) throws ResourceException {
        SshSession session = getSession(conn);

        IDataCursor inCursor = inputRecord != null ? inputRecord.getCursor() : null;
        String inCommand = null;
        String inTimeout = null;
        String inPty = null;
        String inWorkingDirectory = null;
        String stdinStr = null;
        byte[] stdinBytes = null;

        if (inCursor != null) {
            inCommand = text(inCursor, INPUT_COMMAND);
            inTimeout = text(inCursor, INPUT_TIMEOUT);
            inPty = text(inCursor, INPUT_PTY);
            inWorkingDirectory = text(inCursor, WORKING_DIRECTORY);
            stdinStr = IDataUtil.getString(inCursor, "stdin");
            Object bytesObj = IDataUtil.get(inCursor, "stdinBytes");
            if (bytesObj instanceof byte[] b) {
                stdinBytes = b;
            }
            inCursor.destroy();
        }

        String cmd = ExecSettings.resolveCommand(inCommand, defaultCommand);
        int timeoutSec = ExecSettings.resolveTimeoutSeconds(inTimeout, timeoutSeconds);
        boolean pty = ExecSettings.resolveAllocatePty(inPty, allocatePty);

        if (stdinBytes == null && stdinStr != null) {
            stdinBytes = stdinStr.getBytes(StandardCharsets.UTF_8);
        }

        long timeoutMs = timeoutSec > 0 ? timeoutSec * 1000L : 0L;

        // A blank command is passed through untouched so the SSH layer still rejects it with "command is required".
        boolean wrap = cmd != null && !cmd.isBlank();
        String marker = ExecWrapper.newMarker();
        String remoteCommand = wrap ? ExecWrapper.wrap(cmd, inWorkingDirectory, marker) : cmd;
        ExecRequest request = new ExecRequest(remoteCommand, stdinBytes, timeoutMs, pty, null);

        ExecResult result;
        try {
            result = session.exec(request);
        } catch (SshAdapterException ex) {
            throw mapException(ex);
        }

        String stdout = result.stdout();
        String finalDirectory = null;
        if (wrap) {
            ExecWrapper.Parsed parsed = ExecWrapper.parse(stdout, marker);
            stdout = parsed.stdout();
            finalDirectory = parsed.workingDirectory();
        }

        WmRecord outputRecord = WmRecord.create();
        IDataCursor outCursor = outputRecord.getCursor();
        if (result.exitStatus() != null) {
            IDataUtil.put(outCursor, "exitStatus", result.exitStatus());
        }
        if (result.exitSignal() != null) {
            IDataUtil.put(outCursor, "exitSignal", result.exitSignal());
        }
        // Only set when the shell reported it. When it did not (timeout, kill, failed cd, output over the cap,
        // a shell that is not POSIX) the field is left out, so a flow that maps workingDirectory in and out keeps
        // the value it already had instead of being told something wrong.
        if (finalDirectory != null) {
            IDataUtil.put(outCursor, WORKING_DIRECTORY, finalDirectory);
        }
        IDataUtil.put(outCursor, "stdout", stdout);
        IDataUtil.put(outCursor, "stderr", result.stderr());
        IDataUtil.put(outCursor, "timedOut", result.timedOut());
        IDataUtil.put(outCursor, "truncated", result.truncated());
        outCursor.destroy();

        return outputRecord;
    }

    @Override
    public void fillWmTemplateDescriptor(WmTemplateDescriptor descriptor, Locale locale)
            throws ResourceException {
        super.fillWmTemplateDescriptor(descriptor, locale);

        // The six *InputField / *InputFieldType properties are hidden helpers (see declareInputField). They must be
        // members of this group: a hidden property that belongs to no group lands in a default group named after
        // the service, and Designer then shows that group as a blank tab ("ExecCommand").
        descriptor.createGroup("Command Settings", new String[]{
            "defaultCommand", "timeoutSeconds", "allocatePty",
            "commandInputField", "commandInputFieldType",
            "timeoutInputField", "timeoutInputFieldType",
            "ptyInputField", "ptyInputFieldType",
            "workingDirectoryInputField", "workingDirectoryInputFieldType",
            "workingDirectoryOutputField", "workingDirectoryOutputFieldType"
        });

        descriptor.setMultiline("defaultCommand", true);
        // Do not call descriptor.setValidValues(...) here: on a service template the ADK treats the value list as
        // the NAME of a resource domain that the connection must supply, so Designer fails with ART.114.244.
        // allocatePty stays a plain string ("true"/"false") parsed with Boolean.parseBoolean.

        // Declare the three settings as input fields of the service signature, the way the JDBC adapter's
        // DynamicSQL does: a hidden read-only property holds the pipeline field name and is bound to the
        // INPUT_FIELD_NAMES domain, with a second hidden property naming its type.
        declareInputField(descriptor, "commandInputField", "commandInputFieldType");
        declareInputField(descriptor, "timeoutInputField", "timeoutInputFieldType");
        declareInputField(descriptor, "ptyInputField", "ptyInputFieldType");
        declareInputField(descriptor, "workingDirectoryInputField", "workingDirectoryInputFieldType");
        declareOutputField(descriptor, "workingDirectoryOutputField", "workingDirectoryOutputFieldType");
    }

    /** Read-only: the pipeline field name for the command input. Discovered by the ADK as a bean property. */
    public String getCommandInputField() { return INPUT_COMMAND; }
    public String getCommandInputFieldType() { return STRING_TYPE; }

    public String getTimeoutInputField() { return INPUT_TIMEOUT; }
    public String getTimeoutInputFieldType() { return STRING_TYPE; }

    public String getPtyInputField() { return INPUT_PTY; }
    public String getPtyInputFieldType() { return STRING_TYPE; }

    public String getWorkingDirectoryInputField() { return WORKING_DIRECTORY; }
    public String getWorkingDirectoryInputFieldType() { return STRING_TYPE; }

    public String getWorkingDirectoryOutputField() { return WORKING_DIRECTORY; }
    public String getWorkingDirectoryOutputFieldType() { return STRING_TYPE; }

    public String getDefaultCommand() { return defaultCommand; }
    public void setDefaultCommand(String defaultCommand) { this.defaultCommand = defaultCommand; }

    public String getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(String timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public String getAllocatePty() { return allocatePty; }
    public void setAllocatePty(String allocatePty) { this.allocatePty = allocatePty; }
}
