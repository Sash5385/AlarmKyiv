package pro.id4drive.alert

/**
 * NEPTUN (neptun.in.ua) — публічний неофіційний агрегатор тривог, без ключа.
 * Точна форма WebSocket/REST відповіді офіційно не задокументована, тому
 * NeptunClient розбирає її толерантно, а MainActivity показує сирий список
 * areas із ключами — звіряй TARGET_KEYS з ним під час реальної тривоги.
 */
object Config {

    const val WS_URL = "wss://neptun.in.ua/api/v1/stream"
    const val REST_SNAPSHOT_URL = "https://neptun.in.ua/api/v1/snapshot"
    const val ATTRIBUTION_URL = "https://neptun.in.ua"

    const val RECONNECT_INITIAL_DELAY_MS = 2_000L
    const val RECONNECT_MAX_DELAY_MS = 60_000L
    const val RECONNECT_BACKOFF_MULTIPLIER = 2.0

    /** Якщо потоком (включно з heartbeat) довго тихо — вважаємо WS мертвим і йдемо на REST. */
    const val STREAM_SILENCE_TIMEOUT_MS = 90_000L

    const val REST_FALLBACK_POLL_INTERVAL_MS = 30_000L

    /** Не підтверджено на реальній тривозі — перевіряй за списком у MainActivity. */
    val TARGET_KEYS: Set<String> = setOf(
        "kyiv",
        "kyiv_misto",
        "kyiv_oblast",
        "kyiv_city",
        "31", // типовий числовий код Києва в держреєстрах ДСНС/АРІ
    )

    private val TARGET_TITLES: Set<String> = setOf(
        "київ",
        "м. київ",
        "місто київ",
        "kyiv",
    )

    fun isKyiv(key: String?, title: String?): Boolean {
        val normalizedKey = key?.trim()?.lowercase()
        if (normalizedKey != null && TARGET_KEYS.any { it.lowercase() == normalizedKey }) {
            return true
        }
        val normalizedTitle = title?.trim()?.lowercase()
        return normalizedTitle != null && normalizedTitle in TARGET_TITLES
    }
}
