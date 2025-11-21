package com.github.tfox.flutter_vless.xray.service

import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.github.tfox.flutter_vless.xray.core.XrayCoreManager
import com.github.tfox.flutter_vless.xray.dto.XrayConfig
import com.github.tfox.flutter_vless.xray.utils.AppConfigs
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import java.util.ArrayList

class XrayVPNService : VpnService() {

    private var mInterface: ParcelFileDescriptor? = null
    private var tun2socksProcess: Process? = null
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val command = if (Build.VERSION.SDK_INT >= 33) {
            intent.getSerializableExtra("COMMAND", AppConfigs.V2RAY_SERVICE_COMMANDS::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra("COMMAND") as? AppConfigs.V2RAY_SERVICE_COMMANDS
        }

        if (command == AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE) {
            val config = if (Build.VERSION.SDK_INT >= 33) {
                intent.getSerializableExtra("V2RAY_CONFIG", XrayConfig::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getSerializableExtra("V2RAY_CONFIG") as? XrayConfig
            }

            if (config != null) {
                cleanup()
                
                val proxyOnly = intent.getBooleanExtra("PROXY_ONLY", false)
                
                if (XrayCoreManager.startCore(this, config)) {
                    if (!proxyOnly) {
                        setupVpn(config)
                    } else {
                        // Proxy Only Mode
                        isRunning = true
                        Log.d(TAG, "Starting in PROXY_ONLY mode")
                    }
                } else {
                    stopSelf()
                }
            }
        } else if (command == AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE) {
            stopAll()
        }

        return START_STICKY
    }

    private fun setupVpn(config: XrayConfig) {
        try {
            if (mInterface != null) {
                mInterface?.close()
                mInterface = null
            }

            val builder = Builder()
            builder.setSession(config.REMARK)
            builder.setMtu(1500)
            builder.addAddress("26.26.26.1", 30)
            builder.addRoute("0.0.0.0", 0)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to exclude app from VPN", e)
            }

            // Add routes
            val serverIp = config.CONNECTED_V2RAY_SERVER_ADDRESS
            if (serverIp.isNotEmpty() && !serverIp.contains(":")) { // Simple check for IPv4
                 try {
                     Log.d(TAG, "Excluding server IP: $serverIp")
                     val excludedRoutes = excludeIp(serverIp)
                     for (route in excludedRoutes) {
                         val parts = route.split("/")
                         builder.addRoute(parts[0], parts[1].toInt())
                     }
                 } catch (e: Exception) {
                     Log.e(TAG, "Failed to exclude server IP, falling back to 0.0.0.0/0", e)
                     builder.addRoute("0.0.0.0", 0)
                 }
            } else {
                builder.addRoute("0.0.0.0", 0)
            }

            // DNS
            try {
                builder.addDnsServer("8.8.8.8")
                builder.addDnsServer("1.1.1.1")
            } catch (e: Exception) {
                // ignore
            }

            mInterface = builder.establish()
            isRunning = true
            runTun2socks(config)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup VPN", e)
            stopAll()
        }
    }



    private fun runTun2socks(config: XrayConfig) {
        val tun2socksPath = File(applicationInfo.nativeLibraryDir, "libtun2socks.so").absolutePath
        val sockPath = File(filesDir, "sock_path").absolutePath
        
        // Use socket to pass file descriptor
        val cmd = arrayListOf(
            tun2socksPath,
            "-sock-path", sockPath,
            "-proxy", "socks5://127.0.0.1:${config.LOCAL_SOCKS5_PORT}",
            "-mtu", "1500",
            "-loglevel", "debug"
        )

        Log.d(TAG, "tun2socks command: ${cmd.joinToString(" ")}")

        try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            pb.directory(filesDir)
            tun2socksProcess = pb.start()

            Thread {
                try {
                    tun2socksProcess?.inputStream?.bufferedReader()?.use { reader ->
                        reader.forEachLine { line ->
                            Log.d(TAG, "tun2socks: $line")
                        }
                    }
                    
                    tun2socksProcess?.waitFor()
                    if (isRunning) {
                        // Restart if crashed and still supposed to be running
                        Log.e(TAG, "tun2socks exited unexpectedly, restarting...")
                        runTun2socks(config)
                    }
                } catch (e: java.io.InterruptedIOException) {
                    // Expected when stopping
                } catch (e: InterruptedException) {
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading tun2socks output", e)
                }
            }.start()

            sendFd()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tun2socks", e)
            stopAll()
        }
    }

    private fun sendFd() {
        val fd = mInterface?.fileDescriptor ?: return
        val sockFile = File(filesDir, "sock_path").absolutePath

        Thread {
            var tries = 0
            while (tries < 10) {
                try {
                    Thread.sleep(500)
                    val localSocket = LocalSocket()
                    localSocket.connect(LocalSocketAddress(sockFile, LocalSocketAddress.Namespace.FILESYSTEM))
                    val out = localSocket.outputStream
                    localSocket.setFileDescriptorsForSend(arrayOf(fd))
                    out.write(32)
                    localSocket.setFileDescriptorsForSend(null)
                    localSocket.shutdownOutput()
                    localSocket.close()
                    break
                } catch (e: Exception) {
                    tries++
                }
            }
        }.start()
    }

    private fun cleanup() {
        isRunning = false
        tun2socksProcess?.destroy()
        tun2socksProcess = null
        try {
            mInterface?.close()
            mInterface = null
        } catch (e: Exception) {}
    }

    private fun stopAll() {
        cleanup()
        XrayCoreManager.stopCore(this)
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun excludeIp(ip: String): List<String> {
        val parts = ip.split(".").map { it.toInt() }
        val ipLong = (parts[0].toLong() shl 24) + (parts[1].toLong() shl 16) + (parts[2].toLong() shl 8) + parts[3].toLong()
        
        val routes = ArrayList<String>()
        var start = 0L
        var end = 4294967295L // 255.255.255.255
        
        // We want to cover [0, ipLong - 1] and [ipLong + 1, end]
        // But wait, VpnService routes are prefixes.
        // A simpler approach for a single IP exclusion:
        // Add 0.0.0.0/1 and 128.0.0.0/1. One of them contains the IP.
        // Recurse on the one that contains the IP.
        
        fun addRoutesExcluding(target: Long, current: Long, prefix: Int) {
            if (prefix >= 32) return
            
            val size = 1L shl (32 - prefix)
            val nextPrefix = prefix + 1
            val left = current
            val right = current + (1L shl (32 - nextPrefix))
            
            // Check if target is in left half
            if (target >= left && target < left + (1L shl (32 - nextPrefix))) {
                // Target is in left half, so add right half fully
                routes.add(longToIp(right) + "/$nextPrefix")
                addRoutesExcluding(target, left, nextPrefix)
            } else {
                // Target is in right half, so add left half fully
                routes.add(longToIp(left) + "/$nextPrefix")
                addRoutesExcluding(target, right, nextPrefix)
            }
        }
        
        addRoutesExcluding(ipLong, 0L, 0)
        return routes
    }

    private fun longToIp(ip: Long): String {
        return "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
    }

    companion object {
        private const val TAG = "XrayVPNService"
    }
}
