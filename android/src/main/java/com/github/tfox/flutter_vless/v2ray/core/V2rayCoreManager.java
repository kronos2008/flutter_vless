package com.github.tfox.flutter_vless.v2ray.core;

import static com.github.tfox.flutter_vless.v2ray.utils.Utilities.getUserAssetsPath;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.CountDownTimer;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.github.tfox.flutter_vless.v2ray.interfaces.V2rayServicesListener;
import com.github.tfox.flutter_vless.v2ray.services.V2rayProxyOnlyService;
import com.github.tfox.flutter_vless.v2ray.services.V2rayVPNService;
import com.github.tfox.flutter_vless.v2ray.utils.AppConfigs;
import com.github.tfox.flutter_vless.v2ray.utils.Utilities;
import com.github.tfox.flutter_vless.v2ray.utils.V2rayConfig;

import org.json.JSONObject;

import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;
import libv2ray.V2RayProtector;

/**
 * Singleton manager responsible for initializing, controlling, and monitoring
 * the lifecycle of the V2Ray core within the Android environment.
 * <p>
 * This class bridges between the Java Android layer and the native V2Ray core
 * through the libv2ray JNI interface.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Initialize and manage V2Ray core environment</li>
 *   <li>Start/stop core processes</li>
 *   <li>Broadcast connection and traffic info</li>
 *   <li>Display persistent foreground notifications</li>
 *   <li>Measure latency and traffic statistics</li>
 * </ul>
 */
public final class V2rayCoreManager {

    /** Foreground notification ID for V2Ray service. */
    private static final int NOTIFICATION_ID = 1;

    /** Singleton instance (thread-safe double-checked locking). */
    private volatile static V2rayCoreManager INSTANCE;

    /** Reference to bound Android service implementing {@link V2rayServicesListener}. */
    public V2rayServicesListener v2rayServicesListener = null;

    /** Native V2Ray core controller interface. */
    private CoreController coreController;

    /** Current connection state of the V2Ray core. */
    public AppConfigs.V2RAY_STATES V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_DISCONNECTED;

    /** Flag to ensure libv2ray core environment is initialized once. */
    private boolean isLibV2rayCoreInitialized = false;

    /** Timer to track connection duration and periodically update traffic stats. */
    private CountDownTimer countDownTimer;

    /** Elapsed connection time in seconds. */
    private int seconds;

    /** Accumulated traffic and speeds. */
    private long totalDownload, totalUpload, uploadSpeed, downloadSpeed;

    /**
     * Returns singleton instance of {@link V2rayCoreManager}.
     * Thread-safe lazy initialization.
     */
    public static V2rayCoreManager getInstance() {
        if (INSTANCE == null) {
            synchronized (V2rayCoreManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new V2rayCoreManager();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Creates and starts a timer that updates connection duration and traffic statistics
     * every second and broadcasts them via {@code V2RAY_CONNECTION_INFO}.
     *
     * @param context Application context
     * @param enable_traffic_statics Whether to enable bandwidth and traffic measurement
     */
    private void makeDurationTimer(final Context context, final boolean enable_traffic_statics) {
        countDownTimer = new CountDownTimer(100000, 1000) {
            @RequiresApi(api = Build.VERSION_CODES.M)
            public void onTick(long millisUntilFinished) {
                seconds++;

                // Query traffic stats if enabled
                if (enable_traffic_statics) {
                    downloadSpeed = (coreController != null ? coreController.queryStats("block", "downlink") : 0)
                            + (coreController != null ? coreController.queryStats("proxy", "downlink") : 0);
                    uploadSpeed = (coreController != null ? coreController.queryStats("block", "uplink") : 0)
                            + (coreController != null ? coreController.queryStats("proxy", "uplink") : 0);
                    totalDownload += downloadSpeed;
                    totalUpload += uploadSpeed;
                }

                // Broadcast connection info every second
                Intent connection_info_intent = new Intent("V2RAY_CONNECTION_INFO");
                connection_info_intent.putExtra("STATE", V2rayCoreManager.getInstance().V2RAY_STATE);
                connection_info_intent.putExtra("DURATION", String.valueOf(seconds));
                connection_info_intent.putExtra("UPLOAD_SPEED", uploadSpeed);
                connection_info_intent.putExtra("DOWNLOAD_SPEED", downloadSpeed);
                connection_info_intent.putExtra("UPLOAD_TRAFFIC", totalUpload);
                connection_info_intent.putExtra("DOWNLOAD_TRAFFIC", totalDownload);
                try {
                    context.sendBroadcast(connection_info_intent);
                } catch (Exception e) {
                    Log.w("V2rayCoreManager", "Failed to send connection info broadcast", e);
                }
            }

            public void onFinish() {
                countDownTimer.cancel();
                // Restart timer if core is still active
                if (V2rayCoreManager.getInstance().isV2rayCoreRunning())
                    makeDurationTimer(context, enable_traffic_statics);
            }
        }.start();
    }

    /**
     * Initializes libv2ray core environment, binds callback handlers, and sets up
     * the V2Ray service listener.
     *
     * @param targetService Service instance implementing {@link V2rayServicesListener}
     */
    public void setUpListener(Service targetService) {
        try {
            v2rayServicesListener = (V2rayServicesListener) targetService;
            Libv2ray.initCoreEnv(getUserAssetsPath(targetService.getApplicationContext()), "");

            // Register protector for Android VPN sockets (used in Go layer)
            Libv2ray.useProtector(new V2RayProtector() {
                @Override
                public boolean protect(long fd) {
                    if (v2rayServicesListener != null) {
                        return v2rayServicesListener.onProtect((int) fd);
                    }
                    return true;
                }
            });

            // Initialize core controller with callback handler (startup/shutdown)
            coreController = Libv2ray.newCoreController(new CoreCallbackHandler() {
                @Override
                public long onEmitStatus(long p0, String p1) {
                    Log.d(V2rayCoreManager.class.getSimpleName(), "onEmitStatus => " + p0 + ": " + p1);
                    return 0;
                }

                @Override
                public long shutdown() {
                    Log.d(V2rayCoreManager.class.getSimpleName(), "CoreCallbackHandler.shutdown()");
                    if (v2rayServicesListener == null) {
                        Log.e(V2rayCoreManager.class.getSimpleName(), "shutdown failed => can't find initial service.");
                        return -1;
                    }
                    try {
                        v2rayServicesListener.stopService();
                        v2rayServicesListener = null;
                        return 0;
                    } catch (Exception e) {
                        Log.e(V2rayCoreManager.class.getSimpleName(), "shutdown failed =>", e);
                        return -1;
                    }
                }

                @Override
                public long startup() {
                    Log.d(V2rayCoreManager.class.getSimpleName(), "CoreCallbackHandler.startup()");
                    if (v2rayServicesListener != null) {
                        try {
                            v2rayServicesListener.startService();
                        } catch (Exception e) {
                            Log.e(V2rayCoreManager.class.getSimpleName(), "startup failed => ", e);
                            return -1;
                        }
                    }
                    return 0;
                }
            });

            // Reset counters
            isLibV2rayCoreInitialized = true;
            seconds = 0;
            uploadSpeed = downloadSpeed = totalDownload = totalUpload = 0;

            Log.e(V2rayCoreManager.class.getSimpleName(), "setUpListener => initialized from "
                    + v2rayServicesListener.getService().getClass().getSimpleName());
        } catch (Exception e) {
            Log.e(V2rayCoreManager.class.getSimpleName(), "setUpListener failed => ", e);
            isLibV2rayCoreInitialized = false;
        }
    }

    /**
     * Starts the V2Ray core process using provided configuration.
     *
     * @param v2rayConfig Full runtime configuration for V2Ray
     * @return true if successfully started, false otherwise
     */
    public boolean startCore(final V2rayConfig v2rayConfig) {
        makeDurationTimer(v2rayServicesListener.getService().getApplicationContext(),
                v2rayConfig.ENABLE_TRAFFIC_STATICS);
        V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_CONNECTING;

        if (!isLibV2rayCoreInitialized) {
            Log.e(V2rayCoreManager.class.getSimpleName(),
                    "startCore failed => LibV2rayCore should be initialized before start.");
            return false;
        }

        if (isV2rayCoreRunning()) {
            stopCore();
        }

        try {
            if (coreController == null) {
                Log.e(V2rayCoreManager.class.getSimpleName(), "startCore failed => coreController is null.");
                return false;
            }

            // Set protector target server before core startup
            try {
                String server = v2rayConfig.CONNECTED_V2RAY_SERVER_ADDRESS + ":" +
                        v2rayConfig.CONNECTED_V2RAY_SERVER_PORT;
                Libv2ray.setProtectorServer(server, false);
            } catch (Exception ignored) {
            }

            // Start V2Ray main loop
            coreController.startLoop(v2rayConfig.V2RAY_FULL_JSON_CONFIG);
            V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_CONNECTED;

            // Display persistent notification when running
            if (isV2rayCoreRunning()) {
                showNotification(v2rayConfig);
            }
        } catch (Exception e) {
            Log.e(V2rayCoreManager.class.getSimpleName(), "startCore failed =>", e);
            return false;
        }
        return true;
    }

    /**
     * Stops the currently running V2Ray core and cancels notifications.
     * Sends broadcast to update UI state.
     */
    public void stopCore() {
        try {
            if (v2rayServicesListener != null && v2rayServicesListener.getService() != null) {
                NotificationManager notificationManager =
                        (NotificationManager) v2rayServicesListener.getService()
                                .getSystemService(Context.NOTIFICATION_SERVICE);
                if (notificationManager != null) {
                    notificationManager.cancel(NOTIFICATION_ID);
                }
            }
        } catch (Exception e) {
            Log.w("V2rayCoreManager", "Failed to cancel notification", e);
        }

        try {
            if (isV2rayCoreRunning()) {
                if (coreController != null) {
                    coreController.stopLoop();
                }
                v2rayServicesListener.stopService();
                Log.e(V2rayCoreManager.class.getSimpleName(), "stopCore success => v2ray core stopped.");
            } else {
                Log.e(V2rayCoreManager.class.getSimpleName(), "stopCore failed => v2ray core not running.");
            }
            sendDisconnectedBroadCast();
        } catch (Exception e) {
            Log.e(V2rayCoreManager.class.getSimpleName(), "stopCore failed =>", e);
        }
    }

    /**
     * Broadcasts V2Ray disconnection event and resets counters.
     */
    private void sendDisconnectedBroadCast() {
        V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_DISCONNECTED;
        seconds = 0;
        uploadSpeed = downloadSpeed = 0;

        if (v2rayServicesListener != null) {
            Context context = v2rayServicesListener.getService().getApplicationContext();
            Intent connection_info_intent = new Intent("V2RAY_CONNECTION_INFO");
            connection_info_intent.putExtra("STATE", V2rayCoreManager.getInstance().V2RAY_STATE);
            connection_info_intent.putExtra("DURATION", String.valueOf(seconds));
            connection_info_intent.putExtra("UPLOAD_SPEED", uploadSpeed);
            connection_info_intent.putExtra("DOWNLOAD_SPEED", downloadSpeed);
            connection_info_intent.putExtra("UPLOAD_TRAFFIC", totalUpload);
            connection_info_intent.putExtra("DOWNLOAD_TRAFFIC", totalDownload);
            try {
                context.sendBroadcast(connection_info_intent);
            } catch (Exception e) {
                Log.w("V2rayCoreManager", "Failed to send disconnected broadcast", e);
            }
        }

        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
    }

    /**
     * Creates a notification channel for Android 8+ if required.
     *
     * @param appName Application name for channel naming
     * @return The created or existing channel ID
     */
    private String createNotificationChannelID(String appName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelId = "A_flutter_vless_SERVICE_CH_ID";
            try {
                if (v2rayServicesListener == null || v2rayServicesListener.getService() == null) {
                    return channelId;
                }

                NotificationManager notificationManager =
                        (NotificationManager) v2rayServicesListener.getService()
                                .getSystemService(Context.NOTIFICATION_SERVICE);

                String channelName = appName + " Background Service";
                NotificationChannel channel = new NotificationChannel(channelId, channelName,
                        NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription(channelName);
                channel.setLightColor(Color.DKGRAY);
                channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);

                if (notificationManager != null) {
                    notificationManager.createNotificationChannel(channel);
                }
            } catch (Exception e) {
                Log.w("V2rayCoreManager", "Failed to create notification channel", e);
            }
            return channelId;
        }
        return "";
    }

    /**
     * Builds and displays a persistent foreground notification representing
     * the active V2Ray connection.
     *
     * @param v2rayConfig Configuration providing app name, icons, etc.
     */
    private void showNotification(final V2rayConfig v2rayConfig) {
        Service context = v2rayServicesListener.getService();
        if (context == null) return;

        // Check Android 13+ notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        // Prepare main app launch intent
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launchIntent != null) {
            launchIntent.setAction("FROM_DISCONNECT_BTN");
            launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP |
                    Intent.FLAG_ACTIVITY_CLEAR_TOP |
                    Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        final int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;

        PendingIntent notificationContentPendingIntent =
                PendingIntent.getActivity(context, 0, launchIntent, flags);

        String notificationChannelID = createNotificationChannelID(v2rayConfig.APPLICATION_NAME);

        // Create intent to stop service from notification button
        Intent stopIntent;
        if (AppConfigs.V2RAY_CONNECTION_MODE == AppConfigs.V2RAY_CONNECTION_MODES.PROXY_ONLY) {
            stopIntent = new Intent(context, V2rayProxyOnlyService.class);
        } else if (AppConfigs.V2RAY_CONNECTION_MODE == AppConfigs.V2RAY_CONNECTION_MODES.VPN_TUN) {
            stopIntent = new Intent(context, V2rayVPNService.class);
        } else {
            return;
        }
        stopIntent.putExtra("COMMAND", AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE);
        PendingIntent pendingIntent = PendingIntent.getService(context, 0, stopIntent, flags);

        try {
            // Build foreground notification
            NotificationCompat.Builder notificationBuilder =
                    new NotificationCompat.Builder(context, notificationChannelID)
                            .setSmallIcon(v2rayConfig.APPLICATION_ICON)
                            .setContentTitle(v2rayConfig.REMARK)
                            .addAction(0, v2rayConfig.NOTIFICATION_DISCONNECT_BUTTON_NAME, pendingIntent)
                            .setPriority(NotificationCompat.PRIORITY_MIN)
                            .setShowWhen(false)
                            .setOnlyAlertOnce(true)
                            .setContentIntent(notificationContentPendingIntent)
                            .setSilent(true)
                            .setOngoing(true);

            context.startForeground(NOTIFICATION_ID, notificationBuilder.build());
        } catch (Exception e) {
            Log.w("V2rayCoreManager", "Failed to show notification, continuing without notification", e);
        }
    }

       /**
     * Checks whether the V2Ray core process is currently running.
     *
     * @return true if the V2Ray core is active; false otherwise.
     */
    public boolean isV2rayCoreRunning() {
        if (coreController != null) {
            return coreController.getIsRunning();
        }
        return false;
    }

    /**
     * Measures the connection delay (latency) of the currently connected V2Ray server.
     *
     * @return The measured delay in milliseconds, or -1 if the measurement fails.
     */
    public Long getConnectedV2rayServerDelay() {
        try {
            if (coreController == null)
                return -1L;
            return coreController.measureDelay(AppConfigs.DELAY_URL);
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * Measures the delay (latency) for a given V2Ray server using a specific configuration and test URL.
     * <p>
     * This method attempts to sanitize the routing rules in the provided configuration before measurement
     * to prevent interference from custom routing.
     *
     * @param config The V2Ray JSON configuration as a string.
     * @param url    The target URL used to test the delay.
     * @return The measured delay in milliseconds, or -1 if the measurement fails.
     */
    public Long getV2rayServerDelay(final String config, final String url) {
        try {
            try {
                JSONObject config_json = new JSONObject(config);
                JSONObject new_routing_json = config_json.getJSONObject("routing");
                new_routing_json.remove("rules");
                config_json.remove("routing");
                config_json.put("routing", new_routing_json);
                return Libv2ray.measureOutboundDelay(config_json.toString(), url);
            } catch (Exception json_error) {
                Log.e("getV2rayServerDelay", json_error.toString());
                return Libv2ray.measureOutboundDelay(config, url);
            }
        } catch (Exception e) {
            Log.e("getV2rayServerDelayCore", e.toString());
            return -1L;
        }
    }
}