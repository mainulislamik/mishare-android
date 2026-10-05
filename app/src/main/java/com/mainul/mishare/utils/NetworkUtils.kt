package com.mainul.mishare.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // First check wlan0 (Wi-Fi) or ap0 (Hotspot)
            for (intf in interfaces) {
                if (intf.name.contains("wlan") || intf.name.contains("ap") || intf.name.contains("eth")) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val hostAddress = addr.hostAddress
                            if (!hostAddress.isNullOrEmpty() && !hostAddress.startsWith("127.")) {
                                return hostAddress
                            }
                        }
                    }
                }
            }
            // Fallback: check any non-loopback IPv4 address
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddress = addr.hostAddress
                        if (!hostAddress.isNullOrEmpty() && !hostAddress.startsWith("127.")) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun getWifiSSID(context: Context): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifiManager?.connectionInfo
            val ssid = info?.ssid
            if (!ssid.isNullOrEmpty() && ssid != "<unknown ssid>") {
                return ssid.replace("\"", "")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "Wi-Fi Network / Hotspot"
    }
}
