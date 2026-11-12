package com.wm.adapter.wxssh.service;

import com.luisloja.wxssh.core.ShellRequest;
import com.luisloja.wxssh.core.ShellResult;
import com.luisloja.wxssh.core.ShellStep;
import com.luisloja.wxssh.core.SshAdapterException;
import com.luisloja.wxssh.core.SshSession;
import com.wm.adk.cci.record.WmRecord;
import com.wm.adk.connection.WmManagedConnection;
import com.wm.adk.metadata.WmTemplateDescriptor;
import com.wm.data.IData;
import com.wm.data.IDataCursor;
import com.wm.data.IDataFactory;
import com.wm.data.IDataUtil;
import jakarta.resource.ResourceException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;

/**
 * Adapter service template that drives an interactive remote shell: it sends the lines of a script one at a time
 * and waits for the shell prompt after each.
 * <p>
 * Why: some targets (network devices, restricted shells, legacy hosts) offer no usable command execution, only a
 * login shell, and state such as the working directory must survive between lines. This is the "last resort" next
 * to ExecCommand and is inherently fragile: it depends on the prompt being recognisable by a regular expression
 * and on how the remote terminal echoes input. Expect the first run against a new target to need a tuned prompt
 * pattern.
 * <p>
 * Settings precedence: {@code script}, {@code promptPattern}, {@code timeoutSeconds}, {@code allocatePty},
 * {@code waitForFirstPrompt}, {@code lineEnding} and {@code charset} are declared as input fields. A non-blank
 * value in the pipeline overrides the matching value in the "Shell Script Settings" panel. See
 * {@link ShellSettings}. {@code timeoutSeconds} is the longest wait for a prompt at each step, not for the whole
 * script; 0 waits without a deadline.
 * <p>
 * Output: {@code transcript} (everything received, including echo and prompts), {@code steps} (one document per
 * line sent: {@code input}, {@code output}, {@code matched}), {@code stderr}, {@code completed},
 * {@code timedOut}, {@code closedByServer} and {@code truncated}. A timeout, a shell that ends, or reaching the
 * memory limit stops the script and is reported in these fields, not thrown, so the output received so far is not
 * lost. Wrong settings (bad regular expression, unknown line ending) are thrown.
 * <p>
 * Side effects: opens one session channel on the active SSH session, starts a remote login shell (with a
 * pseudo-terminal by default) and runs every script line in it with the login's rights; closing the channel ends
 * that shell. The script text is never written to a log.
 */
public class RunShellScriptService extends SshAdapterService {

    static final String INPUT_SCRIPT = "script";
    static final String INPUT_PROMPT = "promptPattern";
    static final String INPUT_TIMEOUT = "timeoutSeconds";
    static final String INPUT_PTY = "allocatePty";
    static final String INPUT_FIRST_PROMPT = "waitForFirstPrompt";
    static final String INPUT_LINE_ENDING = "lineEnding";
    static final String INPUT_CHARSET = "charset";

    private String defaultScript = "";
    private String promptPattern = ShellSettings.DEFAULT_PROMPT_PATTERN;
    private String timeoutSeconds = "30";
    private String allocatePty = "true";
    private String waitForFirstPrompt = "true";
    private String lineEnding = "LF";
    private String charset = "UTF-8";

    public RunShellScriptService() {
        super();
    }

    @Override
    public WmRecord execute(WmManagedConnection conn, WmRecord inputRecord) throws ResourceException {
        SshSession session = getSession(conn);

        IDataCursor in = inputRecord != null ? inputRecord.getCursor() : null;
        String inScript = null;
        String inPrompt = null;
        String inTimeout = null;
        String inPty = null;
        String inFirst = null;
        String inEol = null;
        String inCharset = null;
        if (in != null) {
            inScript = text(in, INPUT_SCRIPT);
            inPrompt = text(in, INPUT_PROMPT);
            inTimeout = text(in, INPUT_TIMEOUT);
            inPty = text(in, INPUT_PTY);
            inFirst = text(in, INPUT_FIRST_PROMPT);
            inEol = text(in, INPUT_LINE_ENDING);
            inCharset = text(in, INPUT_CHARSET);
            in.destroy();
        }

        List<String> lines = ShellSettings.splitScript(SftpSettings.resolveText(inScript, defaultScript));
        if (lines.isEmpty()) {
            throw adapterError(INPUT_SCRIPT + " is required");
        }
        String prompt = required(INPUT_PROMPT, SftpSettings.resolveText(inPrompt, promptPattern));
        String eol;
        Charset cs;
        try {
            eol = ShellSettings.resolveLineEnding(inEol, lineEnding);
            cs = ShellSettings.resolveCharset(inCharset, charset);
        } catch (IllegalArgumentException ex) {
            throw invalidInput(ex);
        }
        long stepTimeoutMs = ShellSettings.toMillis(ExecSettings.resolveTimeoutSeconds(inTimeout, timeoutSeconds));
        boolean pty = SftpSettings.resolveBoolean(inPty, allocatePty, true);
        boolean first = SftpSettings.resolveBoolean(inFirst, waitForFirstPrompt, true);

        ShellResult result;
        try {
            result = session.runShell(new ShellRequest(lines, prompt, stepTimeoutMs, pty, first, eol, cs));
        } catch (SshAdapterException ex) {
            throw mapException(ex);
        }

        IData[] steps = new IData[result.steps().size()];
        for (int i = 0; i < steps.length; i++) {
            ShellStep step = result.steps().get(i);
            IData doc = IDataFactory.create();
            IDataCursor c = doc.getCursor();
            IDataUtil.put(c, "input", step.input());
            IDataUtil.put(c, "output", step.output());
            IDataUtil.put(c, "matched", step.matched());
            c.destroy();
            steps[i] = doc;
        }

        WmRecord out = WmRecord.create();
        IDataCursor oc = out.getCursor();
        IDataUtil.put(oc, "transcript", result.transcript());
        IDataUtil.put(oc, "steps", steps);
        IDataUtil.put(oc, "stderr", result.stderr());
        IDataUtil.put(oc, "completed", result.completed());
        IDataUtil.put(oc, "timedOut", result.timedOut());
        IDataUtil.put(oc, "closedByServer", result.closedByServer());
        IDataUtil.put(oc, "truncated", result.truncated());
        oc.destroy();
        return out;
    }

    @Override
    public void fillWmTemplateDescriptor(WmTemplateDescriptor descriptor, Locale locale)
            throws ResourceException {
        super.fillWmTemplateDescriptor(descriptor, locale);

        // The *InputField / *InputFieldType properties are hidden helpers (see declareInputField in the base
        // class). They must be members of this group, or Designer shows a blank tab named after the service.
        descriptor.createGroup("Shell Script Settings", new String[]{
            "defaultScript", "promptPattern", "timeoutSeconds", "allocatePty", "waitForFirstPrompt",
            "lineEnding", "charset",
            "scriptInputField", "scriptInputFieldType",
            "promptPatternInputField", "promptPatternInputFieldType",
            "timeoutInputField", "timeoutInputFieldType",
            "ptyInputField", "ptyInputFieldType",
            "waitForFirstPromptInputField", "waitForFirstPromptInputFieldType",
            "lineEndingInputField", "lineEndingInputFieldType",
            "charsetInputField", "charsetInputFieldType"
        });

        descriptor.setMultiline("defaultScript", true);
        // No descriptor.setValidValues(...) here: on a service template it names a resource domain (ART.114.244).

        declareInputField(descriptor, "scriptInputField", "scriptInputFieldType");
        declareInputField(descriptor, "promptPatternInputField", "promptPatternInputFieldType");
        declareInputField(descriptor, "timeoutInputField", "timeoutInputFieldType");
        declareInputField(descriptor, "ptyInputField", "ptyInputFieldType");
        declareInputField(descriptor, "waitForFirstPromptInputField", "waitForFirstPromptInputFieldType");
        declareInputField(descriptor, "lineEndingInputField", "lineEndingInputFieldType");
        declareInputField(descriptor, "charsetInputField", "charsetInputFieldType");
    }

    /** Read-only: the pipeline field names of the declared inputs. Discovered by the ADK as bean properties. */
    public String getScriptInputField() { return INPUT_SCRIPT; }
    public String getScriptInputFieldType() { return STRING_TYPE; }

    public String getPromptPatternInputField() { return INPUT_PROMPT; }
    public String getPromptPatternInputFieldType() { return STRING_TYPE; }

    public String getTimeoutInputField() { return INPUT_TIMEOUT; }
    public String getTimeoutInputFieldType() { return STRING_TYPE; }

    public String getPtyInputField() { return INPUT_PTY; }
    public String getPtyInputFieldType() { return STRING_TYPE; }

    public String getWaitForFirstPromptInputField() { return INPUT_FIRST_PROMPT; }
    public String getWaitForFirstPromptInputFieldType() { return STRING_TYPE; }

    public String getLineEndingInputField() { return INPUT_LINE_ENDING; }
    public String getLineEndingInputFieldType() { return STRING_TYPE; }

    public String getCharsetInputField() { return INPUT_CHARSET; }
    public String getCharsetInputFieldType() { return STRING_TYPE; }

    public String getDefaultScript() { return defaultScript; }
    public void setDefaultScript(String defaultScript) { this.defaultScript = defaultScript; }

    public String getPromptPattern() { return promptPattern; }
    public void setPromptPattern(String promptPattern) { this.promptPattern = promptPattern; }

    public String getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(String timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public String getAllocatePty() { return allocatePty; }
    public void setAllocatePty(String allocatePty) { this.allocatePty = allocatePty; }

    public String getWaitForFirstPrompt() { return waitForFirstPrompt; }
    public void setWaitForFirstPrompt(String waitForFirstPrompt) { this.waitForFirstPrompt = waitForFirstPrompt; }

    public String getLineEnding() { return lineEnding; }
    public void setLineEnding(String lineEnding) { this.lineEnding = lineEnding; }

    public String getCharset() { return charset; }
    public void setCharset(String charset) { this.charset = charset; }
}
