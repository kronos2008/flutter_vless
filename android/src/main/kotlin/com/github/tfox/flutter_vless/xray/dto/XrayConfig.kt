package com.github.tfox.flutter_vless.xray.dto

import java.io.Serializable
import java.util.ArrayList

data class XrayConfig(
    var CONNECTED_V2RAY_SERVER_ADDRESS: String = "",
    var CONNECTED_V2RAY_SERVER_PORT: String = "",
    var LOCAL_SOCKS5_PORT: Int = 10807,
    var LOCAL_HTTP_PORT: Int = 10808,
    var LOCAL_API_PORT: Int = 10809,
    var ALLOWED_TIMEOUT_MS: Int = 0,
    var BYPASS_SUBNETS: ArrayList<String> = ArrayList(),
    var BLOCKED_APPS: ArrayList<String> = ArrayList(),
    var V2RAY_FULL_JSON_CONFIG: String = "",
    var ENABLE_TRAFFIC_STATICS: Boolean = false,
    var REMARK: String = "",
    var APPLICATION_NAME: String = "Flutter Vless",
    var APPLICATION_ICON: Int = 0,
    var NOTIFICATION_DISCONNECT_BUTTON_NAME: String = "Disconnect",
    var DOMAIN_STRATEGY: String = "",
    var ROUTING_DOMAIN_STRATEGY: String = ""
) : Serializable
