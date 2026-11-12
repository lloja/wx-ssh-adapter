package com.luisloja.wxssh.core;

/**
 * The single checked exception type thrown by the façade.
 *
 * <p>Why a single type: the ADK layer translates exactly one exception class into the adapter's own exception
 * (and, optionally, into {@code errorCode}/{@code errorMessage} output fields), keeping SSHJ types out of
 * Integration Server's class-loading and pipeline concerns.
 */
public class SshAdapterException extends Exception {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final String sftpStatus;

    /**
     * Creates an exception without an SFTP status.
     *
     * @param code    the error category; never null
     * @param message human-readable description; must not contain secrets
     * @param cause   underlying exception, may be null
     */
    public SshAdapterException(ErrorCode code, String message, Throwable cause) {
        this(code, message, cause, null);
    }

    /**
     * Creates an exception that carries the SFTP protocol status name (for example {@code NO_SUCH_FILE}).
     *
     * @param code       the error category; never null
     * @param message    human-readable description; must not contain secrets
     * @param cause      underlying exception, may be null
     * @param sftpStatus SFTP status name, or null when not applicable
     */
    public SshAdapterException(ErrorCode code, String message, Throwable cause, String sftpStatus) {
        super(message, cause);
        this.code = code;
        this.sftpStatus = sftpStatus;
    }

    /** @return the stable error category */
    public ErrorCode getCode() {
        return code;
    }

    /** @return the SFTP status name when {@link #getCode()} is {@link ErrorCode#SFTP_STATUS}, otherwise null */
    public String getSftpStatus() {
        return sftpStatus;
    }
}
