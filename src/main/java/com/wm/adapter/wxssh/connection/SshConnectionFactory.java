package com.wm.adapter.wxssh.connection;

import com.luisloja.wxssh.core.AuthMethod;
import com.luisloja.wxssh.core.HostKeyPolicy;
import com.luisloja.wxssh.core.SshAdapterException;
import com.luisloja.wxssh.core.SshConnectionConfig;
import com.wm.adapter.wxssh.WxSshAdapter;
import com.wm.adapter.wxssh.service.ExecCommandService;
import com.wm.adapter.wxssh.service.SftpGetService;
import com.wm.adapter.wxssh.service.SftpExistsService;
import com.wm.adapter.wxssh.service.SftpListService;
import com.wm.adapter.wxssh.service.SftpMkdirService;
import com.wm.adapter.wxssh.service.SftpDeleteService;
import com.wm.adapter.wxssh.service.SftpRmdirService;
import com.wm.adapter.wxssh.service.SftpRenameService;
import com.wm.adapter.wxssh.service.SftpSymlinkService;
import com.wm.adapter.wxssh.service.SftpChmodService;
import com.wm.adapter.wxssh.service.SftpChownService;
import com.wm.adapter.wxssh.service.SftpSetTimesService;
import com.wm.adapter.wxssh.service.SftpTruncateService;
import com.wm.adapter.wxssh.service.RunShellScriptService;
import com.wm.adapter.wxssh.service.ScpDownloadService;
import com.wm.adapter.wxssh.service.ScpUploadService;
import com.wm.adapter.wxssh.service.StartSubsystemService;
import com.wm.adapter.wxssh.service.SftpPutService;
import com.wm.adapter.wxssh.service.SftpReadLinkService;
import com.wm.adapter.wxssh.service.SftpRealPathService;
import com.wm.adapter.wxssh.service.SftpStatService;
import com.wm.adk.connection.WmConnectionRequestInfo;
import com.wm.adk.connection.WmManagedConnection;
import com.wm.adk.connection.WmManagedConnectionFactory;
import com.wm.adk.error.AdapterException;
import com.wm.adk.info.ResourceAdapterMetadataInfo;
import com.wm.adk.metadata.WmDescriptor;
import java.util.Locale;
import javax.security.auth.Subject;

/**
 * Managed connection factory for SSH connections configured in webMethods Integration Server.
 * <p>
 * Why: Defines the connection configuration parameters (host, port, credentials, host key policies)
 * displayed in Software AG / IBM Designer and WmART administration pages, and instantiates
 * {@link SshManagedConnection} instances for the connection pool.
 * <p>
 * Side effects: Instantiates pooled managed connection objects; no direct network I/O in the factory.
 */
public class SshConnectionFactory extends WmManagedConnectionFactory {

    private String host = "";
    private String port = "22";
    private String username = "";
    private String authMethod = "PASSWORD";
    private String password = "";
    private String privateKeyPath = "";
    private String privateKeyPem = "";
    private String keyPassphrase = "";
    private String hostKeyPolicy = "ACCEPT_ANY";
    private String knownHostsPath = "";
    private String pinnedFingerprint = "";
    private String connectTimeoutMs = "30000";
    private String readTimeoutMs = "30000";
    private String keepAliveSeconds = "0";
    private String compression = "false";
    private String maxConcurrentChannels = "8";

    public SshConnectionFactory() {
        super();
    }

    @Override
    public WmManagedConnection createManagedConnectionObjectV2(Subject subject, WmConnectionRequestInfo cri)
            throws AdapterException {
        SshManagedConnection conn = new SshManagedConnection();
        conn.setFactory(this);
        return conn;
    }

    @Override
    public void fillResourceAdapterMetadataInfo(ResourceAdapterMetadataInfo info, Locale locale)
            throws AdapterException {
        WxSshAdapter adapter = WxSshAdapter.getInstance();
        adapter.fillResourceAdapterMetadataInfo(info, locale);
        info.setAdapterName(adapter.getAdapterName());
        info.setAdapterVersion(adapter.getAdapterVersion());

        info.addServiceTemplate(ExecCommandService.class.getName());
        info.addServiceTemplate(SftpListService.class.getName());
        info.addServiceTemplate(SftpGetService.class.getName());
        info.addServiceTemplate(SftpPutService.class.getName());
        info.addServiceTemplate(SftpStatService.class.getName());
        info.addServiceTemplate(SftpExistsService.class.getName());
        info.addServiceTemplate(SftpRealPathService.class.getName());
        info.addServiceTemplate(SftpReadLinkService.class.getName());
        info.addServiceTemplate(SftpMkdirService.class.getName());
        info.addServiceTemplate(SftpDeleteService.class.getName());
        info.addServiceTemplate(SftpRmdirService.class.getName());
        info.addServiceTemplate(SftpRenameService.class.getName());
        info.addServiceTemplate(SftpSymlinkService.class.getName());
        info.addServiceTemplate(SftpChmodService.class.getName());
        info.addServiceTemplate(SftpChownService.class.getName());
        info.addServiceTemplate(SftpSetTimesService.class.getName());
        info.addServiceTemplate(SftpTruncateService.class.getName());
        info.addServiceTemplate(RunShellScriptService.class.getName());
        info.addServiceTemplate(StartSubsystemService.class.getName());
        info.addServiceTemplate(ScpUploadService.class.getName());
        info.addServiceTemplate(ScpDownloadService.class.getName());
    }

    @Override
    public void fillWmDescriptor(WmDescriptor descriptor, Locale locale) throws AdapterException {
        descriptor.createGroup("Server", new String[]{
            "host", "port", "connectTimeoutMs", "readTimeoutMs", "keepAliveSeconds", "compression", "maxConcurrentChannels"
        });
        descriptor.createGroup("Authentication", new String[]{
            "authMethod", "username", "password", "privateKeyPath", "privateKeyPem", "keyPassphrase"
        });
        descriptor.createGroup("Trust", new String[]{
            "hostKeyPolicy", "knownHostsPath", "pinnedFingerprint"
        });

        descriptor.setRequired("host", true);
        descriptor.setRequired("port", true);
        descriptor.setRequired("authMethod", true);
        descriptor.setRequired("username", true);
        descriptor.setRequired("hostKeyPolicy", true);

        descriptor.setPassword("password", true);
        descriptor.setPassword("keyPassphrase", true);

        descriptor.setValidValues("authMethod", new String[]{
            "PASSWORD", "PUBLIC_KEY", "KEYBOARD_INTERACTIVE"
        });
        descriptor.setValidValues("hostKeyPolicy", new String[]{
            "ACCEPT_ANY", "KNOWN_HOSTS", "PINNED_FINGERPRINT"
        });
        descriptor.setValidValues("compression", new String[]{"false", "true"});

        WxSshAdapter adapter = WxSshAdapter.getInstance();
        if (adapter.getAdapterResourceBundleManager() != null) {
            descriptor.setDescriptions(adapter.getAdapterResourceBundleManager(), locale);
        }
    }

    @Override
    public int queryTransactionSupportLevel() {
        return WmManagedConnectionFactory.NO_TRANSACTION_SUPPORT;
    }

    /**
     * Builds an immutable {@link SshConnectionConfig} object from this factory's current configuration.
     * <p>
     * Why it throws: a bad value used to be replaced by a default without any message, and for
     * {@code hostKeyPolicy} the default was {@code ACCEPT_ANY}, so a typo could switch host-key checking off.
     * Every field is now parsed strictly by {@link ConnectionSettings}; a blank optional number still means its
     * default, but a blank {@code authMethod} or {@code hostKeyPolicy} is refused.
     * <p>
     * Side effects: none.
     *
     * @return the configured {@link SshConnectionConfig}.
     * @throws SshAdapterException {@code INVALID_CONFIG} naming the field and the allowed values
     */
    public SshConnectionConfig toSshConnectionConfig() throws SshAdapterException {
        int parsedPort = ConnectionSettings.parseInt("port", port, 22, 1, 65535);
        int parsedConnectTimeout = ConnectionSettings.parseInt("connectTimeoutMs", connectTimeoutMs, 30000, 0, Integer.MAX_VALUE);
        int parsedReadTimeout = ConnectionSettings.parseInt("readTimeoutMs", readTimeoutMs, 30000, 0, Integer.MAX_VALUE);
        int parsedKeepAlive = ConnectionSettings.parseInt("keepAliveSeconds", keepAliveSeconds, 0, 0, Integer.MAX_VALUE);
        int parsedMaxChannels = ConnectionSettings.parseInt("maxConcurrentChannels", maxConcurrentChannels,
                SshConnectionConfig.DEFAULT_MAX_CONCURRENT_CHANNELS, 1, Integer.MAX_VALUE);
        boolean parsedCompression = ConnectionSettings.parseFlag("compression", compression);
        AuthMethod parsedAuth = ConnectionSettings.parseAuthMethod(authMethod);
        HostKeyPolicy parsedPolicy = ConnectionSettings.parseHostKeyPolicy(hostKeyPolicy);

        SshConnectionConfig.Builder b = SshConnectionConfig.builder()
                .host(host != null ? host.trim() : "")
                .port(parsedPort)
                .username(username != null ? username.trim() : "")
                .authMethod(parsedAuth)
                .password(password)
                .privateKeyPath(privateKeyPath != null ? privateKeyPath.trim() : null)
                .privateKeyPem(privateKeyPem != null && !privateKeyPem.isBlank() ? privateKeyPem : null)
                .keyPassphrase(keyPassphrase != null && !keyPassphrase.isEmpty() ? keyPassphrase : null)
                .hostKeyPolicy(parsedPolicy)
                .knownHostsPath(knownHostsPath != null ? knownHostsPath.trim() : null)
                .connectTimeoutMs(parsedConnectTimeout)
                .socketTimeoutMs(parsedReadTimeout)
                .keepAliveSeconds(parsedKeepAlive)
                .compression(parsedCompression)
                .maxConcurrentChannels(parsedMaxChannels);

        if (pinnedFingerprint != null && !pinnedFingerprint.isBlank()) {
            b.addPinnedFingerprint(pinnedFingerprint.trim());
        }

        return b.build();
    }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public String getPort() { return port; }
    public void setPort(String port) { this.port = port; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getAuthMethod() { return authMethod; }
    public void setAuthMethod(String authMethod) { this.authMethod = authMethod; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getPrivateKeyPath() { return privateKeyPath; }
    public void setPrivateKeyPath(String privateKeyPath) { this.privateKeyPath = privateKeyPath; }

    public String getPrivateKeyPem() { return privateKeyPem; }
    public void setPrivateKeyPem(String privateKeyPem) { this.privateKeyPem = privateKeyPem; }

    public String getKeyPassphrase() { return keyPassphrase; }
    public void setKeyPassphrase(String keyPassphrase) { this.keyPassphrase = keyPassphrase; }

    public String getHostKeyPolicy() { return hostKeyPolicy; }
    public void setHostKeyPolicy(String hostKeyPolicy) { this.hostKeyPolicy = hostKeyPolicy; }

    public String getKnownHostsPath() { return knownHostsPath; }
    public void setKnownHostsPath(String knownHostsPath) { this.knownHostsPath = knownHostsPath; }

    public String getPinnedFingerprint() { return pinnedFingerprint; }
    public void setPinnedFingerprint(String pinnedFingerprint) { this.pinnedFingerprint = pinnedFingerprint; }

    public String getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(String connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

    public String getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(String readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }

    public String getKeepAliveSeconds() { return keepAliveSeconds; }
    public void setKeepAliveSeconds(String keepAliveSeconds) { this.keepAliveSeconds = keepAliveSeconds; }

    public String getMaxConcurrentChannels() { return maxConcurrentChannels; }
    public void setMaxConcurrentChannels(String maxConcurrentChannels) { this.maxConcurrentChannels = maxConcurrentChannels; }

    public String getCompression() { return compression; }
    public void setCompression(String compression) { this.compression = compression; }
}
