package com.github.tfox.flutter_vless.xray.utils

object AppConfigs {
    const val V2RAY_CONNECTION_INFO = "V2RAY_CONNECTION_INFO"
    const val V2RAY_CONNECTED_SERVER_DELAY = "CONNECTED_V2RAY_SERVER_DELAY"
    const val DELAY_URL = "https://www.google.com"

    enum class V2RAY_STATES {
        V2RAY_CONNECTED,
        V2RAY_DISCONNECTED,
        V2RAY_CONNECTING
    }

    enum class V2RAY_CONNECTION_MODES {
        VPN_TUN,
        PROXY_ONLY
    }

    enum class V2RAY_SERVICE_COMMANDS {
        START_SERVICE,
        STOP_SERVICE,
        MEASURE_DELAY
    }

    var V2RAY_STATE: V2RAY_STATES = V2RAY_STATES.V2RAY_DISCONNECTED
    var V2RAY_CONFIG: com.github.tfox.flutter_vless.xray.dto.XrayConfig? = null
    var V2RAY_CONNECTION_MODE: V2RAY_CONNECTION_MODES = V2RAY_CONNECTION_MODES.VPN_TUN
    var NOTIFICATION_ICON_RESOURCE_NAME: String = ""
    var NOTIFICATION_ICON_RESOURCE_TYPE: String = ""
}
