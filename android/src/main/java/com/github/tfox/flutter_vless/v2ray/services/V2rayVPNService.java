package com.github.tfox.flutter_vless.v2ray.services;

import android.app.Service;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.github.tfox.flutter_vless.v2ray.core.V2rayCoreManager;
import com.github.tfox.flutter_vless.v2ray.interfaces.V2rayServicesListener;
import com.github.tfox.flutter_vless.v2ray.utils.AppConfigs;
import com.github.tfox.flutter_vless.v2ray.utils.V2rayConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * {@code V2rayVPNService} is an Android {@link VpnService} that manages
 * the VPN tunnel for routing device traffic through the V2Ray core.
 * <p>
 * It creates and configures a virtual network interface (TUN),
 * launches the tun2socks process, and communicates file descriptors
 * with it through a local socket.
 * <p>
 * This class implements {@link V2rayServicesListener} to interact with
 * the {@link V2rayCoreManager} for service lifecycle events.
 */
public class V2rayVPNService extends VpnService implements V2rayServicesListener {

    private ParcelFileDescriptor mInterface;
    private Process process;
    private V2rayConfig v2rayConfig;
    private boolean isRunning = true;

    /**
     * Called when the VPN service is first created.
     * Sets up the listener for the {@link V2rayCoreManager}.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        V2rayCoreManager.getInstance().setUpListener(this);
    }

    /**
     * Called whenever the service receives a start command.
     * This handles commands such as start, stop, and measure delay.
     *
     * @param intent  The intent that started the service.
     * @param flags   Additional data about the start request.
     * @param startId A unique integer representing this specific start request.
     * @return The service mode flag indicating how to handle service restarts.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Handle null intent case - can happen when service is restarted by system
        if (intent == null) {
            Log.w("V2rayVPNService", "onStartCommand called with null intent, stopping service");
            this.onDestroy();
            return START_NOT_STICKY;
        }

        AppConfigs.V2RAY_SERVICE_COMMANDS startCommand = (AppConfigs.V2RAY_SERVICE_COMMANDS) intent
                .getSerializableExtra("COMMAND");

        // Handle null command case
        if (startCommand == null) {
            Log.w("V2rayVPNService", "No command found in intent, stopping service");
            this.onDestroy();
            return START_NOT_STICKY;
        }

        // Handle start service command
        if (startCommand.equals(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE)) {
            v2rayConfig = (V2rayConfig) intent.getSerializableExtra("V2RAY_CONFIG");
            if (v2rayConfig == null) {
                Log.w("V2rayVPNService", "V2RAY_CONFIG is null, cannot start service");
                this.onDestroy();
                return START_NOT_STICKY;
            }
            if (V2rayCoreManager.getInstance().isV2rayCoreRunning()) {
                V2rayCoreManager.getInstance().stopCore();
            }
            if (V2rayCoreManager.getInstance().startCore(v2rayConfig)) {
                Log.i("V2rayVPNService", "onStartCommand success => v2ray core started.");
            } else {
                Log.e("V2rayVPNService", "Failed to start v2ray core");
                this.onDestroy();
                return START_NOT_STICKY;
            }

        // Handle stop service command
        } else if (startCommand.equals(AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE)) {
            V2rayCoreManager.getInstance().stopCore();
            AppConfigs.V2RAY_CONFIG = null;

        // Handle measure delay command
        } else if (startCommand.equals(AppConfigs.V2RAY_SERVICE_COMMANDS.MEASURE_DELAY)) {
            new Thread(() -> {
                try {
                    Intent sendB = new Intent("CONNECTED_V2RAY_SERVER_DELAY");
                    sendB.putExtra("DELAY", String.valueOf(V2rayCoreManager.getInstance().getConnectedV2rayServerDelay()));
                    sendBroadcast(sendB);
                } catch (Exception e) {
                    Log.w("V2rayVPNService", "Failed to send delay broadcast", e);
                }
            }, "MEASURE_CONNECTED_V2RAY_SERVER_DELAY").start();

        // Handle unknown commands
        } else {
            Log.w("V2rayVPNService", "Unknown command received, stopping service");
            this.onDestroy();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    /**
     * Stops all running processes including tun2socks, V2Ray core,
     * and closes the VPN interface.
     */
    private void stopAllProcess() {
        stopForeground(true);
        isRunning = false;
        if (process != null) {
            process.destroy();
        }
        V2rayCoreManager.getInstance().stopCore();
        try {
            stopSelf();
        } catch (Exception e) {
            Log.e("CANT_STOP", "SELF");
        }
        try {
            mInterface.close();
        } catch (Exception e) {
            // ignored
        }
    }

    /**
     * Configures and establishes the VPN interface.
     * Sets up DNS, routes, bypass rules, and launches tun2socks.
     */
    private void setup() {
        Intent prepare_intent = prepare(this);
        if (prepare_intent != null) {
            return;
        }
        Builder builder = new Builder();
        builder.setSession(v2rayConfig.REMARK);
        builder.setMtu(1500);
        builder.addAddress("26.26.26.1", 30);

        // Configure routing and bypass subnets
        if (v2rayConfig.BYPASS_SUBNETS == null || v2rayConfig.BYPASS_SUBNETS.isEmpty()) {
            builder.addRoute("0.0.0.0", 0);
        } else {
            for (String subnet : v2rayConfig.BYPASS_SUBNETS) {
                String[] parts = subnet.split("/");
                if (parts.length == 2) {
                    String address = parts[0];
                    int prefixLength = Integer.parseInt(parts[1]);
                    builder.addRoute(address, prefixLength);
                }
            }
        }

        // Blocked apps handling
        if (v2rayConfig.BLOCKED_APPS != null) {
            for (int i = 0; i < v2rayConfig.BLOCKED_APPS.size(); i++) {
                try {
                    builder.addDisallowedApplication(v2rayConfig.BLOCKED_APPS.get(i));
                } catch (Exception e) {
                    // ignore
                }
            }
        }

        // Configure DNS servers
        try {
            JSONObject json = new JSONObject(v2rayConfig.V2RAY_FULL_JSON_CONFIG);
            if (json.has("dns")) {
                JSONObject dnsObject = json.getJSONObject("dns");
                if (dnsObject.has("servers")) {
                    JSONArray serversArray = dnsObject.getJSONArray("servers");
                    for (int i = 0; i < serversArray.length(); i++) {
                        try {
                            Object entry = serversArray.get(i);
                            if (entry instanceof String) {
                                builder.addDnsServer((String) entry);
                            } else if (entry instanceof JSONObject) {
                                JSONObject obj = (JSONObject) entry;
                                if (obj.has("address")) {
                                    builder.addDnsServer(obj.getString("address"));
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Fallback DNS if parsing fails
            try {
                builder.addDnsServer("1.1.1.1");
            } catch (Exception ignored) {
            }
            try {
                builder.addDnsServer("8.8.8.8");
            } catch (Exception ignored) {
            }
        }

        // Reinitialize VPN interface
        try {
            mInterface.close();
        } catch (Exception e) {
            // ignore
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false);
        }

        try {
            mInterface = builder.establish();
            isRunning = true;
            runTun2socks();
        } catch (Exception e) {
            Log.e("VPN_SERVICE", "Failed to establish VPN interface", e);
            stopAllProcess();
        }
    }

    /**
     * Starts the tun2socks process that bridges the VPN TUN interface with the SOCKS proxy.
     * Handles process restarts and monitors the execution thread.
     */
    private void runTun2socks() {
        ArrayList<String> cmd = new ArrayList<>(
                Arrays.asList(new File(getApplicationInfo().nativeLibraryDir, "libtun2socks.so").getAbsolutePath(),
                        "--netif-ipaddr", "26.26.26.2",
                        "--netif-netmask", "255.255.255.252",
                        "--socks-server-addr", "127.0.0.1:" + v2rayConfig.LOCAL_SOCKS5_PORT,
                        "--tunmtu", "1500",
                        "--sock-path", "sock_path",
                        "--enable-udprelay",
                        "--loglevel", "error"));
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(cmd);
            processBuilder.redirectErrorStream(true);
            process = processBuilder.directory(getApplicationContext().getFilesDir()).start();
            new Thread(() -> {
                try {
                    process.waitFor();
                    if (isRunning) {
                        runTun2socks();
                    }
                } catch (InterruptedException e) {
                    // ignore
                }
            }, "Tun2socks_Thread").start();
            sendFileDescriptor();
        } catch (Exception e) {
            Log.e("VPN_SERVICE", "FAILED=>", e);
            this.onDestroy();
        }
    }

    /**
     * Sends the VPN TUN interface file descriptor to the tun2socks process
     * using a UNIX domain socket for communication.
     */
    private void sendFileDescriptor() {
        String localSocksFile = new File(getApplicationContext().getFilesDir(), "sock_path").getAbsolutePath();
        FileDescriptor tunFd = mInterface.getFileDescriptor();
        new Thread(() -> {
            int tries = 0;
            while (true) {
                try {
                    Thread.sleep(50L * tries);
                    LocalSocket clientLocalSocket = new LocalSocket();
                    clientLocalSocket
                            .connect(new LocalSocketAddress(localSocksFile, LocalSocketAddress.Namespace.FILESYSTEM));
                    if (!clientLocalSocket.isConnected()) {
                        Log.e("SOCK_FILE", "Unable to connect to localSocksFile [" + localSocksFile + "]");
                    } else {
                        Log.e("SOCK_FILE", "connected to sock file [" + localSocksFile + "]");
                    }
                    OutputStream clientOutStream = clientLocalSocket.getOutputStream();
                    clientLocalSocket.setFileDescriptorsForSend(new FileDescriptor[]{tunFd});
                    clientOutStream.write(32);
                    clientLocalSocket.setFileDescriptorsForSend(null);
                    clientLocalSocket.shutdownOutput();
                    clientLocalSocket.close();
                    break;
                } catch (Exception e) {
                    Log.e(V2rayVPNService.class.getSimpleName(), "sendFd failed =>", e);
                    if (tries > 5)
                        break;
                    tries += 1;
                }
            }
        }, "sendFd_Thread").start();
    }

    /**
     * Cleans up all resources when the service is destroyed.
     * Stops V2Ray, terminates tun2socks, and closes VPN interface.
     */
    @Override
    public void onDestroy() {
        Log.i("V2rayVPNService", "onDestroy called - cleaning up resources");
        isRunning = false;

        try {
            if (V2rayCoreManager.getInstance().isV2rayCoreRunning()) {
                V2rayCoreManager.getInstance().stopCore();
            }
        } catch (Exception e) {
            Log.e("V2rayVPNService", "Error stopping V2ray core in onDestroy", e);
        }

        try {
            stopForeground(true);
        } catch (Exception e) {
            Log.e("V2rayVPNService", "Error stopping foreground in onDestroy", e);
        }

        try {
            if (process != null) {
                process.destroy();
                process = null;
            }
        } catch (Exception e) {
            Log.e("V2rayVPNService", "Error destroying process in onDestroy", e);
        }

        try {
            if (mInterface != null) {
                mInterface.close();
                mInterface = null;
            }
        } catch (Exception e) {
            Log.e("V2rayVPNService", "Error closing VPN interface in onDestroy", e);
        }

        super.onDestroy();
    }

    /**
     * Called by the system when VPN permissions are revoked by the user.
     * Stops all processes immediately.
     */
    @Override
    public void onRevoke() {
        stopAllProcess();
    }

    /**
     * Protects sockets from being captured by the VPN tunnel.
     *
     * @param socket The socket file descriptor.
     * @return {@code true} if the socket is successfully protected.
     */
    @Override
    public boolean onProtect(int socket) {
        return protect(socket);
    }

    /**
     * Returns the current {@link Service} instance.
     *
     * @return This service.
     */
    @Override
    public Service getService() {
        return this;
    }

    /**
     * Starts the VPN setup process.
     */
    @Override
    public void startService() {
        setup();
    }

    /**
     * Stops all running processes and cleans up resources.
     */
    @Override
    public void stopService() {
        stopAllProcess();
    }
}
