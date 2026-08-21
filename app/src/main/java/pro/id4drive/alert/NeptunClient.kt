package pro.id4drive.alert

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

enum class ConnectionState { CONNECTING, LIVE, RECONNECTING, FALLBACK_REST, FAILED }

data class AlertArea(
    val key: String,
    val title: String,
    val active: Boolean,
    val since: Long?,
    val type: String? = null,
)

/**
 * WebSocket-клієнт до NEPTUN з реконнектом (експоненційний backoff), watchdog
 * по тиші потоку та REST-фолбеком, поки WS не відновиться.
 */
class NeptunClient(
    private val scope: CoroutineScope,
    private val onAreasUpdated: (List<AlertArea>) -> Unit,
    private val onConnectionStateChanged: (ConnectionState) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var fallbackJob: Job? = null
    private var reconnectDelayMs = Config.RECONNECT_INITIAL_DELAY_MS
    private var stopped = true

    private val areas = mutableMapOf<String, AlertArea>()

    fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        reconnectJob?.cancel()
        watchdogJob?.cancel()
        fallbackJob?.cancel()
        webSocket?.close(1000, "stop")
        webSocket = null
    }

    private fun connect() {
        if (stopped) return
        onConnectionStateChanged(ConnectionState.CONNECTING)
        val request = Request.Builder().url(Config.WS_URL).build()
        webSocket = client.newWebSocket(request, listener)
        armWatchdog()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectDelayMs = Config.RECONNECT_INITIAL_DELAY_MS
            onConnectionStateChanged(ConnectionState.LIVE)
            fallbackJob?.cancel()
            armWatchdog()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            armWatchdog()
            handleFrame(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WS failure: ${t.message}")
            scheduleReconnect()
        }
    }

    private fun armWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(Config.STREAM_SILENCE_TIMEOUT_MS)
            Log.w(TAG, "Потік мовчить занадто довго, вмикаю REST-фолбек")
            startRestFallback()
        }
    }

    private fun scheduleReconnect() {
        if (stopped) return
        onConnectionStateChanged(ConnectionState.RECONNECTING)
        startRestFallback()
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(reconnectDelayMs)
            reconnectDelayMs = min(
                (reconnectDelayMs * Config.RECONNECT_BACKOFF_MULTIPLIER).toLong(),
                Config.RECONNECT_MAX_DELAY_MS,
            )
            connect()
        }
    }

    private fun startRestFallback() {
        if (fallbackJob?.isActive == true) return
        fallbackJob = scope.launch {
            onConnectionStateChanged(ConnectionState.FALLBACK_REST)
            while (isActive && !stopped) {
                fetchRestSnapshot()
                delay(Config.REST_FALLBACK_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun fetchRestSnapshot() {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(Config.REST_SNAPSHOT_URL).get().build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (response.isSuccessful && body != null) {
                        handleFrame(body)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "REST-фолбек не спрацював: ${e.message}")
            }
        }
    }

    /**
     * Задокументований конверт: {"type": ..., "ts": ..., "data": ...}, типи —
     * snapshot/upsert/remove/heartbeat/alerts. Розбираємо максимально
     * толерантно: шукаємо масив тривог у кількох ймовірних місцях, бо точні
     * назви полів не підтверджені.
     */
    private fun handleFrame(text: String) {
        try {
            val root = JSONObject(text)
            val type = root.optString("type", "")

            val dataNode: JSONObject = when {
                root.opt("data") is JSONObject -> root.getJSONObject("data")
                root.opt("snap") is JSONObject -> root.getJSONObject("snap")
                else -> root
            }

            val alertsArray = firstArray(
                dataNode.opt("alertOblasts"),
                dataNode.opt("alerts"),
                dataNode.opt("areas"),
                dataNode.opt("threats"),
                root.opt("alertOblasts"),
                root.opt("alerts"),
            )

            if (alertsArray != null) {
                parseAreas(alertsArray)
                onAreasUpdated(areas.values.toList())
                return
            }

            if (type == "remove") {
                val removedKey = dataNode.optString("key", root.optString("key", ""))
                val existing = areas[removedKey] ?: return
                areas[removedKey] = existing.copy(active = false)
                onAreasUpdated(areas.values.toList())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не вдалось розпарсити фрейм: ${e.message}")
        }
    }

    private fun firstArray(vararg candidates: Any?): JSONArray? =
        candidates.filterIsInstance<JSONArray>().firstOrNull()

    private fun parseAreas(array: JSONArray) {
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val key = obj.optString("key", obj.optString("uid", obj.optString("id", "")))
            if (key.isEmpty()) continue
            val title = obj.optString("title", obj.optString("name", obj.optString("label", key)))
            val active = when {
                obj.has("active") -> obj.optBoolean("active", true)
                obj.has("state") -> obj.optString("state") != "inactive" && obj.optString("state") != "no_alert"
                else -> true
            }
            val since = if (obj.has("since")) obj.optLong("since") else null
            val type = obj.optString(
                "type",
                obj.optString("kind", obj.optString("category", obj.optString("threatType", ""))),
            ).ifEmpty { null }
            areas[key] = AlertArea(key = key, title = title, active = active, since = since, type = type)
        }
    }

    companion object {
        private const val TAG = "NeptunClient"
    }
}
