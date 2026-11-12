package com.wm.adapter.wxssh;

import com.wm.adapter.wxssh.connection.SshConnectionFactory;
import com.wm.adk.WmAdapter;
import com.wm.adk.error.AdapterException;
import com.wm.adapter.wxssh.notification.RemoteDirectoryPollNotification;
import com.wm.adk.info.AdapterTypeInfo;
import java.util.Locale;

/**
 * Main adapter runtime descriptor and lifecycle controller for WxSshAdapter.
 * <p>
 * Why: Extends {@link WmAdapter} to register connection factories, service interaction specs,
 * and metadata with the webMethods Adapter Runtime (WmART).
 * <p>
 * Side effects: Registers singleton instance with WmART upon package startup; no persistent disk
 * or network I/O in this class directly.
 */
public class WxSshAdapter extends WmAdapter {

    private static volatile WxSshAdapter _instance;

    public static final String ADAPTER_NAME = "WxSshAdapter";
    public static final String RESOURCE_BUNDLE_NAME = "com.wm.adapter.wxssh.WxSshAdapterResourceBundle";
    public static final int MAJOR_CODE = 20;

    /**
     * Constructs a new WxSshAdapter instance and triggers internal ADK initialization.
     *
     * @throws AdapterException if the resource bundle or internal ADK structures fail to initialize.
     */
    public WxSshAdapter() throws AdapterException {
        super();
    }

    /**
     * Returns the singleton instance of WxSshAdapter.
     *
     * @return the singleton instance.
     * @throws AdapterException if instantiation fails.
     */
    public static WxSshAdapter getInstance() throws AdapterException {
        if (_instance == null) {
            synchronized (WxSshAdapter.class) {
                if (_instance == null) {
                    _instance = new WxSshAdapter();
                }
            }
        }
        return _instance;
    }

    @Override
    public void initialize() throws AdapterException {
        // Initialization tasks during adapter load
    }

    @Override
    public String getAdapterName() {
        return ADAPTER_NAME;
    }

    @Override
    public String getAdapterVersion() {
        return "1.0";
    }

    @Override
    public String getAdapterJCASpecVersion() {
        return "1.0";
    }

    @Override
    public String getAdapterResourceBundleName() {
        return RESOURCE_BUNDLE_NAME;
    }

    @Override
    public int getAdapterMajorCode() {
        return MAJOR_CODE;
    }

    @Override
    public void fillAdapterTypeInfo(AdapterTypeInfo info, Locale locale) {
        info.addConnectionFactory(SshConnectionFactory.class.getName());
        info.addNotificationType(RemoteDirectoryPollNotification.class.getName());
    }
}
