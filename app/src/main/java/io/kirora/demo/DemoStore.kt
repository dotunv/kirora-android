package io.kirora.demo

import android.app.Application
import android.content.Context
import android.graphics.Color
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kirora.androidsdk.Kirora
import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.model.KiroraPushOptions
import com.kirora.androidsdk.model.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Settings(
    val clientKey: String = "",
    val apiUrl: String = DEFAULT_API_URL,
    val userToken: String = "",
    val showInForeground: Boolean = true
)

enum class InboxKind { RECEIVED, OPENED }

data class InboxItem(
    val at: Long,
    val kind: InboxKind,
    val title: String,
    val body: String,
    val data: Map<String, String>
)

data class SentEvent(val at: Long, val name: String, val properties: Map<String, Any?>)

/** What the SDK reports right now; re-read every second and after each action. */
data class Snapshot(
    val connected: Boolean = false,
    val identified: Boolean = false,
    val deviceId: String? = null,
    val sessionId: String? = null,
    val pushToken: String? = null,
    val pushConsent: Boolean = false,
    val queued: Int = 0
)

const val DEFAULT_API_URL = "https://api.kirora.io"

/**
 * Everything the demo shows comes from the real SDK talking to the real Kirora server: the key
 * and user token are the ones you enter, and pushes are whatever the server sends.
 */
class DemoStore(private val app: Application) {
    private val prefs = app.getSharedPreferences("demo", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _kirora = MutableStateFlow<Kirora?>(null)
    val kirora: StateFlow<Kirora?> = _kirora.asStateFlow()

    private val _inbox = MutableStateFlow(loadList<InboxItem>(KEY_INBOX))
    val inbox: StateFlow<List<InboxItem>> = _inbox.asStateFlow()

    private val _sent = MutableStateFlow<List<SentEvent>>(emptyList())
    val sent: StateFlow<List<SentEvent>> = _sent.asStateFlow()

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private val onForeground: (Map<String, String>) -> Unit = { add(InboxKind.RECEIVED, it) }
    private val onOpened: (Map<String, String>) -> Unit = { add(InboxKind.OPENED, it) }

    @Volatile private var queued = 0

    init {
        // The app's say over pushes that arrive while it is open; Kirora shows them by default.
        Kirora.setForegroundNotificationHandler { _settings.value.showInForeground }
        if (_settings.value.clientKey.isNotBlank()) startSdk()
        scope.launch {
            _kirora.collectLatest { k ->
                val flow = k?.getQueuedEventCountFlow() ?: return@collectLatest
                flow.collect { queued = it; refresh() }
            }
        }
        scope.launch {
            while (true) {
                refresh()
                delay(1000)
            }
        }
    }

    fun connect(clientKey: String, apiUrl: String) {
        val key = clientKey.trim()
        if (key.isEmpty()) return notice("Enter your client key first")
        val url = apiUrl.trim().ifEmpty { DEFAULT_API_URL }
        update { it.copy(clientKey = key, apiUrl = url) }
        startSdk()
        if (_kirora.value?.isInitialized == true) notice("Connected") else notice("The SDK refused this key or URL")
    }

    fun disconnect() {
        _kirora.value?.logoutAsync()
        update { it.copy(clientKey = "", userToken = "") }
        _kirora.value = null
        refresh()
    }

    fun signIn(token: String) {
        val kirora = _kirora.value ?: return notice("Connect first")
        val value = token.trim()
        if (value.isEmpty()) return notice("Paste a user token from your backend")
        update { it.copy(userToken = value) }
        kirora.identifyAsync(value, onError = { notice("Sign-in failed: ${it.message}") })
        notice("Signed in")
        refresh()
    }

    fun signOut() {
        _kirora.value?.logoutAsync()
        update { it.copy(userToken = "") }
        notice("Signed out")
        refresh()
    }

    fun setShowInForeground(show: Boolean) = update { it.copy(showInForeground = show) }

    fun setPushConsent(granted: Boolean) {
        _kirora.value?.setPushConsentAsync(granted)
        refresh()
    }

    suspend fun requestPush(context: Context) {
        val kirora = _kirora.value ?: return notice("Connect first")
        val result = kirora.requestPushPermission(context)
        notice("Notification permission: ${result.wireValue}")
        refresh()
    }

    fun track(name: String, properties: Map<String, Any?>) {
        val kirora = _kirora.value ?: return notice("Connect first")
        if (name.isBlank()) return notice("Give the event a name")
        kirora.track(name.trim(), properties.ifEmpty { null })
        _sent.update { (listOf(SentEvent(System.currentTimeMillis(), name.trim(), properties)) + it).take(30) }
        refresh()
    }

    fun trackScreen(name: String) {
        val kirora = _kirora.value ?: return notice("Connect first")
        kirora.trackScreenView(name)
        _sent.update { (listOf(SentEvent(System.currentTimeMillis(), "Screen Viewed", mapOf("name" to name))) + it).take(30) }
        refresh()
    }

    fun flush() {
        val kirora = _kirora.value ?: return notice("Connect first")
        scope.launch(Dispatchers.IO) {
            kirora.flush()
            refresh()
        }
    }

    fun clearInbox() {
        _inbox.value = emptyList()
        saveList(KEY_INBOX, _inbox.value)
    }

    private fun startSdk() {
        val s = _settings.value
        val instance = Kirora(
            app,
            KiroraOptions(
                clientKey = s.clientKey,
                apiUrl = s.apiUrl,
                fetchUserToken = { _settings.value.userToken.ifEmpty { null } },
                push = KiroraPushOptions(
                    smallIconResId = R.drawable.ic_stat_kirora,
                    accentColor = Color.parseColor("#4F46E5")
                ),
                logLevel = LogLevel.DEBUG
            )
        )
        instance.addForegroundNotificationListener(onForeground)
        instance.addNotificationClickListener(onOpened)
        _kirora.value = instance
        if (s.userToken.isNotEmpty()) instance.identifyAsync(onError = { notice("Sign-in failed: ${it.message}") })
        refresh()
    }

    private fun add(kind: InboxKind, data: Map<String, String>) {
        val item = InboxItem(
            at = System.currentTimeMillis(),
            kind = kind,
            title = data["title"].orEmpty(),
            body = data["body"].orEmpty(),
            data = data
        )
        _inbox.update { (listOf(item) + it).take(50) }
        saveList(KEY_INBOX, _inbox.value)
    }

    private fun refresh() {
        val k = _kirora.value
        _snapshot.value = if (k == null || !k.isInitialized) {
            Snapshot()
        } else {
            Snapshot(
                connected = true,
                identified = k.isIdentified,
                deviceId = k.deviceId,
                sessionId = k.sessionId,
                pushToken = k.pushToken,
                pushConsent = k.pushConsent,
                queued = queued
            )
        }
    }

    private fun notice(message: String) {
        _notices.tryEmit(message)
    }

    private fun update(change: (Settings) -> Settings) {
        _settings.value = change(_settings.value)
        val s = _settings.value
        prefs.edit {
            putString("clientKey", s.clientKey)
            putString("apiUrl", s.apiUrl)
            putString("userToken", s.userToken)
            putBoolean("showInForeground", s.showInForeground)
        }
    }

    private fun loadSettings() = Settings(
        clientKey = prefs.getString("clientKey", "").orEmpty(),
        apiUrl = prefs.getString("apiUrl", DEFAULT_API_URL).orEmpty(),
        userToken = prefs.getString("userToken", "").orEmpty(),
        showInForeground = prefs.getBoolean("showInForeground", true)
    )

    private inline fun <reified T> loadList(key: String): List<T> = try {
        gson.fromJson(prefs.getString(key, null), object : TypeToken<List<T>>() {}.type) ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun saveList(key: String, list: List<Any>) = prefs.edit { putString(key, gson.toJson(list)) }

    private companion object {
        const val KEY_INBOX = "inbox"
    }
}
