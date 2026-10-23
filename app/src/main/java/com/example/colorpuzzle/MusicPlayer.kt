
package com.example.colorpuzzle

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Простая циклическая мелодия, работающая без интернета
 * и без внешних аудиофайлов.
 */
class MusicPlayer(@Suppress("UNUSED_PARAMETER") context: Context) {

    private var track: AudioTrack? = null

    init {
        track = createTrack()
    }

    private fun createTrack(): AudioTrack? {
        return try {
            val sampleRate = 22050

            val notes = listOf(
                523.25 to 0.30,
                659.25 to 0.30,
                783.99 to 0.30,
                659.25 to 0.30,
                587.33 to 0.30,
                659.25 to 0.30,
                523.25 to 0.50,
                392.00 to 0.30,
                440.00 to 0.30,
                523.25 to 0.30,
                587.33 to 0.30,
                523.25 to 0.30,
                440.00 to 0.30,
                392.00 to 0.30,
                329.63 to 0.50,
                392.00 to 0.30,
                440.00 to 0.30,
                523.25 to 0.80
            )

            val samples = ArrayList<Short>()

            for ((frequency, duration) in notes) {
                val count = (sampleRate * duration).toInt()

                for (i in 0 until count) {
                    val t = i.toDouble() / sampleRate

                    val fadeIn = (t / 0.02).coerceIn(0.0, 1.0)
                    val fadeOut =
                        ((duration - t) / 0.05).coerceIn(0.0, 1.0)

                    val wave = (
                        sin(2.0 * PI * frequency * t) +
                        0.22 * sin(4.0 * PI * frequency * t)
                    ) * 0.14 * fadeIn * fadeOut

                    samples.add(
                        (wave.coerceIn(-1.0, 1.0) *
                            Short.MAX_VALUE).toInt().toShort()
                    )
                }

                repeat((sampleRate * 0.035).toInt()) {
                    samples.add(0)
                }
            }

            val data = ShortArray(samples.size) { samples[it] }

            val audio = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                data.size * 2,
                AudioTrack.MODE_STATIC
            )

            val written = audio.write(data, 0, data.size)

            if (written <= 0) {
                audio.release()
                return null
            }

            audio.setVolume(0.22f)
            audio.setLoopPoints(0, written - 1, -1)

            audio
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        val audio = track ?: return

        try {
            if (enabled) {
                if (audio.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    audio.play()
                }
            } else {
                if (audio.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    audio.pause()
                }

                audio.flush()
                audio.reloadStaticData()
            }
        } catch (_: Exception) {
            // Если устройство не поддерживает воспроизведение,
            // игра продолжает работать без музыки.
        }
    }

    @Synchronized
    fun release() {
        try {
            track?.pause()
            track?.flush()
        } catch (_: Exception) {
            // Игнорируем ошибку освобождения аудио.
        }

        track?.release()
        track = null
    }
}
