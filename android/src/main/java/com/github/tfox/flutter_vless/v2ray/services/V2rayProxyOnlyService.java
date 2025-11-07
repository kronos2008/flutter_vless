package com.github.tfox.flutter_vless.v2ray.services;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.github.tfox.flutter_vless.v2ray.core.V2rayCoreManager;
import com.github.tfox.flutter_vless.v2ray.interfaces.V2rayServicesListener;
import com.github.tfox.flutter_vless.v2ray.utils.AppConfigs;
import com.github.tfox.flutter_vless.v2ray.utils.V2rayConfig;

/**
 * V2rayProxyOnlyService is a background Android Service implementation that manages
 * the lifecycle of the V2Ray core when running in proxy-only mode (without VPN tunnel).
 * <p>
 * This service implements {@link V2rayServicesListener} to communicate with
 * {@link V2rayCoreManager} for starting, stopping, and monitoring the V2Ray process.
 */
public class V2rayProxyOnlyService extends Service implements V2rayServicesListener {

    /**
     * Called by the Android system when the service is first created.
     * Initializes the {@link V2rayCoreManager} listener for this service instance.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        V2rayCoreManager.getInstance().setUpListener(this);
    }

    /**
     * Called every time the service is explicitly started using {@link android.content.Context#startService(Intent)}.
     * Handles start, stop, and delay measurement commands for the V2Ray core.
     *
     * @param intent  The intent containing the service command and optional V2Ray configuration.
     * @param flags   Additional data about the start request.
     * @param startId A unique integer representing this specific request to start.
     * @return One of the {@link Service} start mode constants, determining what happens if the service is killed.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Handle null intent case - can happen when service is restarted by system
        if (intent == null) {
            Log.w("V2rayProxyOnlyService", "onStartCommand called with null intent, stopping service");
            this.onDestroy();
            return START_NOT_STICKY;
        }

        AppConfigs.V2RAY_SERVICE_COMMANDS startCommand = (AppConfigs.V2RAY_SERVICE_COMMANDS) intent
                .getSerializableExtra("COMMAND");
        
        // Handle null command case
        if (startCommand == null) {
            Log.w("V2rayProxyOnlyService", "No command found in intent, stopping service");
            this.onDestroy();
            return START_NOT_STICKY;
        }

        // Start the V2Ray core with the provided configuration
        if (startCommand.equals(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE)) {
            V2rayConfig v2rayConfig = (V2rayConfig) intent.getSerializableExtra("V2RAY_CONFIG");
            if (v2rayConfig == null) {
                Log.w("V2rayProxyOnlyService", "V2RAY_CONFIG is null, cannot start service");
                this.onDestroy();
                return START_NOT_STICKY;
            }
            if (V2rayCoreManager.getInstance().isV2rayCoreRunning()) {
                V2rayCoreManager.getInstance().stopCore();
            }
            if (V2rayCoreManager.getInstance().startCore(v2rayConfig)) {
                Log.i("V2rayProxyOnlyService", "onStartCommand success => v2ray core started.");
            } else {
                Log.e("V2rayProxyOnlyService", "Failed to start v2ray core");
                this.onDestroy();
                return START_NOT_STICKY;
            }

        // Stop the V2Ray core and clear configuration
        } else if (startCommand.equals(AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE)) {
            V2rayCoreManager.getInstance().stopCore();
            AppConfigs.V2RAY_CONFIG = null;

        // Measure current connection delay and broadcast result
        } else if (startCommand.equals(AppConfigs.V2RAY_SERVICE_COMMANDS.MEASURE_DELAY)) {
            new Thread(() -> {
                try {
                    Intent sendB = new Intent("CONNECTED_V2RAY_SERVER_DELAY");
                    sendB.putExtra("DELAY", String.valueOf(V2rayCoreManager.getInstance().getConnectedV2rayServerDelay()));
                    sendBroadcast(sendB);
                } catch (Exception e) {
                    Log.w("V2rayProxyOnlyService", "Failed to send delay broadcast", e);
                }
            }, "MEASURE_CONNECTED_V2RAY_SERVER_DELAY").start();

        // Handle unrecognized commands
        } else {
            Log.w("V2rayProxyOnlyService", "Unknown command received, stopping service");
            this.onDestroy();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    /**
     * Called by the Android system when the service is being destroyed.
     * Used for cleanup operations.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    /**
     * This service is not designed to support binding.
     *
     * @param intent The binding intent.
     * @return Always returns null since binding is not supported.
     */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Protects a socket descriptor from being captured by the VPN.
     * In proxy-only mode, all sockets are allowed, so this always returns true.
     *
     * @param socket The socket descriptor.
     * @return Always true.
     */
    @Override
    public boolean onProtect(int socket) {
        return true;
    }

    /**
     * Returns the current {@link Service} instance associated with this listener.
     *
     * @return The active service instance.
     */
    @Override
    public Service getService() {
        return this;
    }

    /**
     * Called when V2Ray core requests to start the service.
     * No action is needed since Android manages the service lifecycle.
     */
    @Override
    public void startService() {
        // ignore
    }

    /**
     * Called when V2Ray core requests to stop the service.
     * Stops the service safely while handling possible exceptions.
     */
    @Override
    public void stopService() {
        try {
            stopSelf();
        } catch (Exception e) {
            // ignore
        }
    }
}
