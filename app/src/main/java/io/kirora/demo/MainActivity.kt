package io.kirora.demo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.kirora.demo.ui.DemoApp
import io.kirora.demo.ui.DemoTheme

class MainActivity : ComponentActivity() {
    private val store get() = (application as DemoApplication).store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store.kirora.value?.handleNotificationIntent(intent)
        setContent { DemoTheme { DemoApp(store) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        store.kirora.value?.handleNotificationIntent(intent)
    }
}
