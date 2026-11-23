package com.vibe.radio.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Аудиопроцессор, встроенный в цепочку ExoPlayer: применяет эффекты к PCM 16-bit
 * и параллельно считает громкость/бас для визуализации (без разрешения на микрофон).
 */
@UnstableApi
class FxAudioProcessor : BaseAudioProcessor() {

    private var sr = 44100
    private var ch = 2
    private var delay = FloatArray(2)
    private var w = 0

    private var phase = 0.0
    private var lfo = 0.0

    private val lpA = FloatArray(8)
    private val lpB = FloatArray(8)
    private val hpS = FloatArray(8)
    private val beat = FloatArray(8)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Hi-res файлы (24-бит / float) пропускаем без эффектов, чтобы не было ошибки воспроизведения
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sr = inputAudioFormat.sampleRate
        ch = inputAudioFormat.channelCount.coerceIn(1, 8)
        delay = FloatArray(sr * ch * 2) // до 2 секунд задержки
        w = 0
        return inputAudioFormat
    }

    override fun onFlush() {
        delay.fill(0f)
        w = 0
        lpA.fill(0f); lpB.fill(0f); hpS.fill(0f); beat.fill(0f)
    }

    override fun onReset() {
        delay = FloatArray(2)
        w = 0
    }

    private fun coef(fc: Double) = (1.0 - exp(-2.0 * PI * fc / sr)).toFloat()

    private fun tap(ms: Int): Float {
        val off = ((ms * sr / 1000).coerceAtLeast(1)) * ch
        var i = w - off
        if (i < 0) i += delay.size
        return delay[i]
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val fx = AudioFx.effect

        val aLow = coef(120.0)
        val aTel1 = coef(450.0)
        val aTel2 = coef(3000.0)
        val aBass = coef(160.0)
        val aMon = coef(900.0)
        val ringFreq = when (fx) {
            VoiceEffect.ROBOT -> 55.0
            VoiceEffect.ALIEN -> 420.0
            else -> 0.0
        }

        var sumSq = 0.0
        var bassSq = 0.0
        var n = 0
        var c = 0

        while (inputBuffer.remaining() >= 2) {
            val x = inputBuffer.short / 32768f
            var store = x
            val y: Float = when (fx) {
                VoiceEffect.NONE, VoiceEffect.CHILD, VoiceEffect.CHIPMUNK -> x

                VoiceEffect.ECHO -> {
                    val d = tap(330)
                    store = x + 0.45f * d
                    x + 0.6f * d
                }

                VoiceEffect.CAVE -> {
                    val r = 0.30f * tap(89) + 0.26f * tap(157) + 0.22f * tap(233) + 0.18f * tap(347)
                    store = x + 0.55f * r
                    0.75f * x + 0.7f * r
                }

                VoiceEffect.STADIUM -> {
                    val r = 0.35f * tap(210) + 0.25f * tap(420) + 0.2f * tap(640)
                    store = x + 0.45f * r
                    0.8f * x + 0.6f * r
                }

                VoiceEffect.ROBOT -> {
                    val d = tap(9)
                    store = x + 0.55f * d
                    val m = sin(phase).toFloat()
                    (x + 0.6f * d) * (0.35f + 0.9f * m)
                }

                VoiceEffect.ALIEN -> {
                    val d = tap(23)
                    store = x + 0.4f * d
                    val m = sin(phase).toFloat()
                    0.4f * x + 0.9f * x * m + 0.3f * d
                }

                VoiceEffect.MONSTER -> {
                    lpA[c] += aMon * (x - lpA[c])
                    val dist = tanh(2.5f * lpA[c]) * 0.8f
                    val d = tap(40)
                    store = x + 0.3f * d
                    dist + 0.25f * d
                }

                VoiceEffect.TELEPHONE -> {
                    hpS[c] += aTel1 * (x - hpS[c])
                    val hp = x - hpS[c]
                    lpA[c] += aTel2 * (hp - lpA[c])
                    tanh(3f * lpA[c]) * 0.7f
                }

                VoiceEffect.UNDERWATER -> {
                    val fc = 350.0 + 250.0 * sin(lfo)
                    val a = coef(fc)
                    lpA[c] += a * (x - lpA[c])
                    lpB[c] += a * (lpA[c] - lpB[c])
                    val d = tap(120)
                    store = lpB[c] + 0.35f * d
                    1.6f * lpB[c] + 0.3f * d
                }

                VoiceEffect.BASS -> {
                    lpA[c] += aBass * (x - lpA[c])
                    tanh(1.2f * (x + 2.2f * lpA[c])) * 0.85f
                }
            }

            delay[w] = store.coerceIn(-1.5f, 1.5f)
            w++
            if (w >= delay.size) w = 0

            val clipped = y.coerceIn(-1f, 1f)
            out.putShort((clipped * 32767f).toInt().toShort())

            // анализ для визуала
            beat[c] += aLow * (clipped - beat[c])
            sumSq += (clipped * clipped).toDouble()
            bassSq += (beat[c] * beat[c]).toDouble()
            n++

            c++
            if (c >= ch) {
                c = 0
                if (ringFreq > 0) {
                    phase += 2.0 * PI * ringFreq / sr
                    if (phase > 2 * PI) phase -= 2 * PI
                }
                lfo += 2.0 * PI * 0.4 / sr
                if (lfo > 2 * PI) lfo -= 2 * PI
            }
        }
        out.flip()

        if (n > 0) {
            AudioFx.level = sqrt(sumSq / n).toFloat().coerceIn(0f, 1f)
            AudioFx.bass = (sqrt(bassSq / n) * 2.5).toFloat().coerceIn(0f, 1f)
        }
    }
}
