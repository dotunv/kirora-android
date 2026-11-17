package com.kirora.androidsdk.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.kirora.androidsdk.utils.KiroraLogger

internal class KiroraNetworkMonitor(
    context: Context,
    private val onNetworkRestored: () -> Unit
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var isInitialCallback: Boolean = true

    fun start() {
        if (connectivityManager == null) return

        try {
            val builder = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (isInitialCallback) {
                        isInitialCallback = false
                        return
                    }
                    KiroraLogger.d("Network connectivity restored. Triggering event flush...")
                    onNetworkRestored()
                }

                override fun onLost(network: Network) {
                    KiroraLogger.d("Network connectivity lost.")
                    isInitialCallback = false
                }
            }

            this.networkCallback = callback
            connectivityManager.registerNetworkCallback(builder.build(), callback)
        } catch (e: Exception) {
            KiroraLogger.w("Could not register network callback", e)
        }
    }

    fun stop() {
        try {
            networkCallback?.let {
                connectivityManager?.unregisterNetworkCallback(it)
            }
            networkCallback = null
        } catch (e: Exception) {
            KiroraLogger.w("Could not unregister network callback", e)
        }
    }
}
