package pro.id4drive.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log

/**
 * Грає на потоці STREAM_ALARM (через AudioAttributes.USAGE_ALARM) — цей потік
 * не глушиться беззвучним/віброрежимом, на відміну від звичайних сповіщень.
 * Канал сповіщень навмисно тихий (setSound(null, null) в AlertService),
 * звук вмикається тут вручну.
 */
object AlertSound {
    private const val TAG = "AlertSound"

    private var player: MediaPlayer? = null
    private var savedAlarmVolume: Int? = null

    @Synchronized
    fun playAlertLoop(context: Context) {
        stopInternal()
        boostAlarmVolume(context)
        player = createPlayer(context, R.raw.alert, loop = true)?.also { it.start() }
    }

    @Synchronized
    fun playClearOnce(context: Context) {
        stopInternal()
        boostAlarmVolume(context)
        val clearPlayer = createPlayer(context, R.raw.clear, loop = false)
        clearPlayer?.setOnCompletionListener {
            it.release()
            if (player === it) player = null
            restoreAlarmVolume(context)
        }
        player = clearPlayer
        clearPlayer?.start()
    }

    @Synchronized
    fun stop(context: Context) {
        stopInternal()
        restoreAlarmVolume(context)
    }

    private fun stopInternal() {
        player?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "stop() у невалідному стані: ${e.message}")
            }
            it.release()
        }
        player = null
    }

    private fun createPlayer(context: Context, resId: Int, loop: Boolean): MediaPlayer? {
        return try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                context.resources.openRawResourceFd(resId).use { afd ->
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
                isLooping = loop
                prepare()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не вдалось створити плеєр для $resId: ${e.message}")
            null
        }
    }

    private fun boostAlarmVolume(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (savedAlarmVolume == null) {
            savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        }
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        try {
            am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "Не вдалось підняти гучність будильника: ${e.message}")
        }
    }

    private fun restoreAlarmVolume(context: Context) {
        val saved = savedAlarmVolume ?: return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            am.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "Не вдалось повернути гучність будильника: ${e.message}")
        }
        savedAlarmVolume = null
    }
}
