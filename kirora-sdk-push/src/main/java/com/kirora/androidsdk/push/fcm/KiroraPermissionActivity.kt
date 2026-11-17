package com.kirora.androidsdk.push.fcm

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

/**
 * Transparent trampoline that shows the Android 13+ notification prompt and reports the answer,
 * so apps need no `onRequestPermissionsResult` plumbing.
 */
internal class KiroraPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // recreated while the prompt is up
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
        } else {
            deliver(true)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE) return
        deliver(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
    }

    private fun deliver(granted: Boolean?) {
        val callbacks = takePending()
        finish()
        callbacks.forEach { it(granted) }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Dismissed without an answer (e.g. the app was backgrounded): don't leak the callback.
        // Dismissed without an answer (e.g. the app was backgrounded).
        if (isFinishing) takePending().forEach { it(null) }
    }

    companion object {
        private const val REQUEST_CODE = 7301

        // Callers that ask while a prompt is already up share its answer.
        private val pending = mutableListOf<(Boolean?) -> Unit>()

        private fun takePending(): List<(Boolean?) -> Unit> = synchronized(pending) {
            pending.toList().also { pending.clear() }
        }

        fun request(context: Context, onResult: (Boolean?) -> Unit) {
            val alreadyShowing = synchronized(pending) {
                val showing = pending.isNotEmpty()
                pending.add(onResult)
                showing
            }
            if (alreadyShowing) return
            val intent = Intent(context, KiroraPermissionActivity::class.java)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                takePending().forEach { it(false) }
            }
        }
    }
}
