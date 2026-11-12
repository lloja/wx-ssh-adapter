package com.wm.adapter.wxssh.service;

import com.luisloja.wxssh.core.SshAdapterException;
import com.luisloja.wxssh.core.SshSession;
import com.luisloja.wxssh.core.SubsystemRequest;
import com.luisloja.wxssh.core.SubsystemResult;
import com.wm.adk.cci.record.WmRecord;
import com.wm.adk.connection.WmManagedConnection;
import com.wm.adk.metadata.WmTemplateDescriptor;
import com.wm.data.IDataCursor;
import com.wm.data.IDataUtil;
import jakarta.resource.ResourceException;
import java.nio.charset.Charset;
import java.util.Locale;

/**
 * Adapter service template that starts a named SSH subsystem (for example {@code netconf}), sends it one request
 * and returns its answer.
 * <p>
 * Why: some protocols are not commands but subsystems of the SSH connection, which ExecCommand cannot reach. This
 * service covers the simple shape "one request, one answer"; a conversation of several turns on one channel is
 * not supported.
 * <p>
 * Two ways to find the end of the answer. Without {@code expect} the input is followed by an end-of-input signal and
 * the answer is read until the server closes the channel. With {@code expect} (a regular expression, for example
 * {@code \]\]&gt;\]\]&gt;} for NETCONF 1.0) the channel stays open until the pattern shows up in the answer. The
 * pattern is matched against the bytes one-to-one (ISO-8859-1), so write it with ASCII characters.
 * <p>
 * Settings precedence: {@code subsystem}, {@code input}, {@code expect}, {@code timeoutSeconds} and
 * {@code charset} are declared as input fields; {@code inputBytes} (byte array) wins over
 * {@code input}. A non-blank pipeline value overrides the "Subsystem Settings" panel.
 * <p>
 * Output: {@code output} (decoded with the character set), {@code outputBytes}, {@code matched},
 * {@code closedByServer}, {@code timedOut}, {@code truncated} and, when the server reported one,
 * {@code exitStatus}. A timeout or reaching the memory limit is reported in these fields, not thrown. A subsystem
 * the server does not offer is thrown as {@code CHANNEL_FAILED}.
 * <p>
 * Side effects: opens one session channel on the active SSH session, starts the subsystem on the server and
 * sends it the input; the channel is closed before the service returns.
 */
public class StartSubsystemService extends SshAdapterService {

    static final String INPUT_SUBSYSTEM = "subsystem";
    static final String INPUT_INPUT = "input";
    static final String INPUT_INPUT_BYTES = "inputBytes";
    static final String OUTPUT_BYTES = "outputBytes";
    static final String INPUT_EXPECT = "expect";
    static final String INPUT_TIMEOUT = "timeoutSeconds";
    static final String INPUT_CHARSET = "charset";

    private String defaultSubsystem = "";
    private String defaultInput = "";
    private String expect = "";
    private String timeoutSeconds = "30";
    private String charset = "UTF-8";

    public StartSubsystemService() {
        super();
    }

    @Override
    public WmRecord execute(WmManagedConnection conn, WmRecord inputRecord) throws ResourceException {
        SshSession session = getSession(conn);

        IDataCursor in = inputRecord != null ? inputRecord.getCursor() : null;
        String inSubsystem = null;
        String inInput = null;
        byte[] inBytes = null;
        String inExpect = null;
        String inTimeout = null;
        String inCharset = null;
        if (in != null) {
            inSubsystem = text(in, INPUT_SUBSYSTEM);
            inInput = text(in, INPUT_INPUT);
            Object bytesObj = IDataUtil.get(in, INPUT_INPUT_BYTES);
            if (bytesObj instanceof byte[] b) {
                inBytes = b;
            }
            inExpect = text(in, INPUT_EXPECT);
            inTimeout = text(in, INPUT_TIMEOUT);
            inCharset = text(in, INPUT_CHARSET);
            in.destroy();
        }

        String name = required(INPUT_SUBSYSTEM, SftpSettings.resolveText(inSubsystem, defaultSubsystem)).trim();
        Charset cs;
        try {
            cs = ShellSettings.resolveCharset(inCharset, charset);
        } catch (IllegalArgumentException ex) {
            throw invalidInput(ex);
        }
        byte[] data = inBytes;
        if (data == null) {
            String s = SftpSettings.resolveText(inInput, defaultInput);
            data = s == null ? new byte[0] : s.getBytes(cs);
        }
        String marker = SftpSettings.resolveText(inExpect, expect);
        long timeoutMs = ShellSettings.toMillis(ExecSettings.resolveTimeoutSeconds(inTimeout, timeoutSeconds));

        SubsystemResult result;
        try {
            result = session.runSubsystem(new SubsystemRequest(name, data, marker, timeoutMs));
        } catch (SshAdapterException ex) {
            throw mapException(ex);
        }

        WmRecord out = WmRecord.create();
        IDataCursor oc = out.getCursor();
        IDataUtil.put(oc, "output", new String(result.output(), cs));
        IDataUtil.put(oc, OUTPUT_BYTES, result.output());
        IDataUtil.put(oc, "matched", result.matched());
        IDataUtil.put(oc, "closedByServer", result.closedByServer());
        IDataUtil.put(oc, "timedOut", result.timedOut());
        IDataUtil.put(oc, "truncated", result.truncated());
        if (result.exitStatus() != null) {
            IDataUtil.put(oc, "exitStatus", result.exitStatus());
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
        descriptor.createGroup("Subsystem Settings", new String[]{
            "defaultSubsystem", "defaultInput", "expect", "timeoutSeconds", "charset",
            "subsystemInputField", "subsystemInputFieldType",
            "inputInputField", "inputInputFieldType",
            "inputBytesInputField", "inputBytesInputFieldType",
           "expectInputField", "expectInputFieldType",
            "timeoutInputField", "timeoutInputFieldType",
            "charsetInputField", "charsetInputFieldType",
            "outputBytesOutputField", "outputBytesOutputFieldType"
        });

        descriptor.setMultiline("defaultInput", true);

        declareInputField(descriptor, "subsystemInputField", "subsystemInputFieldType");
        declareInputField(descriptor, "inputInputField", "inputInputFieldType");
        declareInputField(descriptor, "inputBytesInputField", "inputBytesInputFieldType");
       declareInputField(descriptor, "expectInputField", "expectInputFieldType");
        declareInputField(descriptor, "timeoutInputField", "timeoutInputFieldType");
        declareInputField(descriptor, "charsetInputField", "charsetInputFieldType");
        declareOutputField(descriptor, "outputBytesOutputField", "outputBytesOutputFieldType");
    }

    /** Read-only: the pipeline field names of the declared inputs. Discovered by the ADK as bean properties. */
    public String getSubsystemInputField() { return INPUT_SUBSYSTEM; }
    public String getSubsystemInputFieldType() { return STRING_TYPE; }

    public String getInputInputField() { return INPUT_INPUT; }
    public String getInputInputFieldType() { return STRING_TYPE; }

    public String getInputBytesInputField() { return INPUT_INPUT_BYTES; }
    public String getInputBytesInputFieldType() { return OBJECT_TYPE; }

    public String getOutputBytesOutputField() { return OUTPUT_BYTES; }
    public String getOutputBytesOutputFieldType() { return OBJECT_TYPE; }

    public String getExpectInputField() { return INPUT_EXPECT; }
    public String getExpectInputFieldType() { return STRING_TYPE; }

    public String getTimeoutInputField() { return INPUT_TIMEOUT; }
    public String getTimeoutInputFieldType() { return STRING_TYPE; }

    public String getCharsetInputField() { return INPUT_CHARSET; }
    public String getCharsetInputFieldType() { return STRING_TYPE; }

    public String getDefaultSubsystem() { return defaultSubsystem; }
    public void setDefaultSubsystem(String defaultSubsystem) { this.defaultSubsystem = defaultSubsystem; }

    public String getDefaultInput() { return defaultInput; }
    public void setDefaultInput(String defaultInput) { this.defaultInput = defaultInput; }

    public String getExpect() { return expect; }
    public void setExpect(String expect) { this.expect = expect; }

    public String getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(String timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public String getCharset() { return charset; }
    public void setCharset(String charset) { this.charset = charset; }
}
