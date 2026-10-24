package dev.aircast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetUtil {
    /**
     * If the phone is on Wi-Fi, make this process use it for all sockets. Without this,
     * Android may route traffic over mobile data when the Wi-Fi has no internet, and the
     * speaker (which lives on the local network) would be unreachable.
     */
    fun bindToWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return cm.bindProcessToNetwork(network)
            }
        }
        return false
    }
}
