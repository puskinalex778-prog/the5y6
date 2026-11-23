package com.vibe.radio.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Встроенный DSP-аудиопроцессор ExoPlayer:
 * 1) Декодирует любой PCM (8/16/24/32-бит и float — включая все форматы WAV и MP3).
 * 2) 10-полосный графический частотный эквалайзер на биквад-фильтрах + Bass Boost + Preamp.
 * 3) 12 голосовых / звуковых эффектов.
 * 4) 512-точечный FFT-анализатор в реальном времени для живого спектра (10 и 32 полосы),
 *    громкости и баса (для 3D-персонажей и экрана эквалайзера).
 */
@UnstableApi
class FxAudioProcessor : BaseAudioProcessor() {

    private var sr = 44100
    private var ch = 2
    private var inEncoding = C.ENCODING_PCM_16BIT

    // Линия задержки для эффектов
    private var delay = FloatArray(2)
    private var w = 0
    private var phase = 0.0
    private var lfo = 0.0
    private val lpA = FloatArray(8)
    private val lpB = FloatArray(8)
    private val hpS = FloatArray(8)
    private val beat = FloatArray(8)

    // 10-полосный биквад-эквалайзер (Direct Form II Transposed)
    private val eqB0 = FloatArray(10) { 1f }
    private val eqB1 = FloatArray(10)
    private val eqB2 = FloatArray(10)
    private val eqA1 = FloatArray(10)
    private val eqA2 = FloatArray(10)
    private val eqActive = BooleanArray(10)
    private var preampLinear = 1f
    private var lastEqVersion = -1
    private val eqZ1 = Array(8) { FloatArray(10) }
    private val eqZ2 = Array(8) { FloatArray(10) }

    // 512-точечный FFT-анализатор (без аллокаций в аудиопотоке)
    private val fftN = 512
    private val fftInput = FloatArray(fftN)
    private val fftRe = FloatArray(fftN)
    private val fftIm = FloatArray(fftN)
    private val hann = FloatArray(fftN) { i -> (0.5 * (1.0 - cos(2.0 * PI * i / (fftN - 1)))).toFloat() }
    private var fftPos = 0
    private var monoAccum = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT &&
            enc != C.ENCODING_PCM_8BIT &&
            enc != C.ENCODING_PCM_24BIT &&
            enc != C.ENCODING_PCM_32BIT &&
            enc != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        inEncoding = enc
        sr = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        ch = inputAudioFormat.channelCount.coerceIn(1, 8)
        delay = FloatArray(sr * ch * 2)
        w = 0
        lastEqVersion = -1
        clearEqState()
        return AudioProcessor.AudioFormat(sr, ch, C.ENCODING_PCM_16BIT)
    }

    override fun onFlush() {
        delay.fill(0f)
        w = 0
        lpA.fill(0f); lpB.fill(0f); hpS.fill(0f); beat.fill(0f)
        clearEqState()
        fftPos = 0
        monoAccum = 0f
    }

    override fun onReset() {
        delay = FloatArray(2)
        w = 0
        clearEqState()
        fftPos = 0
    }

    private fun clearEqState() {
        for (i in 0 until 8) {
            eqZ1[i].fill(0f)
            eqZ2[i].fill(0f)
        }
    }

    private fun updateEqCoefficients() {
        val ver = AudioFx.eqVersion.get()
        if (ver == lastEqVersion) return
        lastEqVersion = ver

        val gains = AudioFx.eqGainsDb
        val boost = AudioFx.eqBassBoost
        preampLinear = 10.0.pow(AudioFx.eqPreampDb / 20.0).toFloat()

        val nyquistLimit = sr * 0.45
        for (b in 0 until 10) {
            val extraBass = when (b) {
                0 -> boost * 7.5f
                1 -> boost * 5.0f
                2 -> boost * 2.2f
                else -> 0f
            }
            val db = (gains.getOrElse(b) { 0f } + extraBass).coerceIn(-15f, 15f)
            val freq = EQ_BAND_FREQS[b].toDouble()
            if (abs(db) < 0.05f || freq >= nyquistLimit) {
                eqActive[b] = false
                eqB0[b] = 1f; eqB1[b] = 0f; eqB2[b] = 0f; eqA1[b] = 0f; eqA2[b] = 0f
                continue
            }
            eqActive[b] = true
            val f0 = freq.coerceAtMost(nyquistLimit)
            val a = 10.0.pow(db / 40.0)
            val w0 = 2.0 * PI * f0 / sr
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)

            val b0: Double
            val b1: Double
            val b2: Double
            val a0: Double
            val a1: Double
            val a2: Double

            when (b) {
                0 -> {
                    // Low-shelf 31 Гц
                    val alpha = sinW0 / sqrt(2.0)
                    val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                    b0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)
                    b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
                    b2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)
                    a0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
                    a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
                    a2 = (a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha
                }
                9 -> {
                    // High-shelf 16 кГц
                    val alpha = sinW0 / sqrt(2.0)
                    val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                    b0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha)
                    b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
                    b2 = a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha)
                    a0 = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha
                    a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
                    a2 = (a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha
                }
                else -> {
                    // Peaking EQ (Q = 1.414 — ширина 1 октава)
                    val alpha = sinW0 / (2.0 * 1.41421356)
                    b0 = 1.0 + alpha * a
                    b1 = -2.0 * cosW0
                    b2 = 1.0 - alpha * a
                    a0 = 1.0 + alpha / a
                    a1 = -2.0 * cosW0
                    a2 = 1.0 - alpha / a
                }
            }

            eqB0[b] = (b0 / a0).toFloat()
            eqB1[b] = (b1 / a0).toFloat()
            eqB2[b] = (b2 / a0).toFloat()
            eqA1[b] = (a1 / a0).toFloat()
            eqA2[b] = (a2 / a0).toFloat()
        }
    }

    private fun bytesPerSample(): Int = when (inEncoding) {
        C.ENCODING_PCM_8BIT -> 1
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 2
    }

    private fun readSample(buf: ByteBuffer): Float = when (inEncoding) {
        C.ENCODING_PCM_16BIT -> buf.short / 32768f
        C.ENCODING_PCM_8BIT -> ((buf.get().toInt() and 0xFF) - 128) / 128f
        C.ENCODING_PCM_24BIT -> {
            val b0 = buf.get().toInt() and 0xFF
            val b1 = buf.get().toInt() and 0xFF
            val b2 = buf.get().toInt() // знаковый старший байт
            val v = (b2 shl 16) or (b1 shl 8) or b0
            v / 8388608f
        }
        C.ENCODING_PCM_32BIT -> buf.int / 2147483648f
        C.ENCODING_PCM_FLOAT -> buf.float
        else -> buf.short / 32768f
    }

    private fun coef(fc: Double) = (1.0 - exp(-2.0 * PI * fc / sr)).toFloat()

    private fun tap(ms: Int): Float {
        val off = ((ms * sr / 1000).coerceAtLeast(1)) * ch
        var i = w - off
        if (i < 0) i += delay.size
        return delay[i]
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bps = bytesPerSample()
        val samples = inputBuffer.remaining() / bps
        if (samples <= 0) return

        updateEqCoefficients()
        val out = replaceOutputBuffer(samples * 2)
        val fx = AudioFx.effect
        val eqOn = AudioFx.eqEnabled

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

        for (s in 0 until samples) {
            val raw = readSample(inputBuffer)
            var x = raw

            // 1. 10-полосный частотный DSP-эквалайзер
            if (eqOn) {
                x *= preampLinear
                val z1c = eqZ1[c]
                val z2c = eqZ2[c]
                for (b in 0 until 10) {
                    if (!eqActive[b]) continue
                    val yEq = eqB0[b] * x + z1c[b]
                    z1c[b] = eqB1[b] * x - eqA1[b] * yEq + z2c[b]
                    z2c[b] = eqB2[b] * x - eqA2[b] * yEq
                    x = yEq
                }
            }

            // 2. Голосовые / звуковые эффекты
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

            // Мягкий лимитер — предотвращает хрип при сильном усилении EQ (+12 дБ)
            val clipped = if (abs(y) > 0.90f) tanh(y.toDouble()).toFloat() else y.coerceIn(-1f, 1f)
            out.putShort((clipped * 32767f).toInt().toShort())

            // Анализ громкости/баса
            beat[c] += aLow * (clipped - beat[c])
            sumSq += (clipped * clipped).toDouble()
            bassSq += (beat[c] * beat[c]).toDouble()
            monoAccum += clipped
            n++

            c++
            if (c >= ch) {
                val mono = monoAccum / ch
                monoAccum = 0f
                fftInput[fftPos++] = mono
                if (fftPos >= fftN) {
                    fftPos = 0
                    computeSpectrum()
                }
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

    /** Быстрый 512-точечный radix-2 FFT для 10-полосного и 32-полосного спектра. */
    private fun computeSpectrum() {
        for (i in 0 until fftN) {
            fftRe[i] = fftInput[i] * hann[i]
            fftIm[i] = 0f
        }
        // Bit-reversal permutation
        var j = 0
        for (i in 1 until fftN) {
            var bit = fftN shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = fftRe[i]; fftRe[i] = fftRe[j]; fftRe[j] = tr
            }
        }
        // Butterfly stages
        var len = 2
        while (len <= fftN) {
            val ang = -2.0 * PI / len
            val wLenRe = cos(ang).toFloat()
            val wLenIm = sin(ang).toFloat()
            val half = len shr 1
            var i = 0
            while (i < fftN) {
                var wRe = 1f
                var wIm = 0f
                for (k in 0 until half) {
                    val uRe = fftRe[i + k]
                    val uIm = fftIm[i + k]
                    val vRe = fftRe[i + k + half] * wRe - fftIm[i + k + half] * wIm
                    val vIm = fftRe[i + k + half] * wIm + fftIm[i + k + half] * wRe
                    fftRe[i + k] = uRe + vRe
                    fftIm[i + k] = uIm + vIm
                    fftRe[i + k + half] = uRe - vRe
                    fftIm[i + k + half] = uIm - vIm
                    val nextWRe = wRe * wLenRe - wIm * wLenIm
                    wIm = wRe * wLenIm + wIm * wLenRe
                    wRe = nextWRe
                }
                i += len
            }
            len = len shl 1
        }

        val halfN = fftN / 2
        val binHz = sr.toFloat() / fftN

        // 10 полос для частотного эквалайзера (31 Гц .. 16 кГц)
        val s10 = AudioFx.spectrum10
        for (b in 0 until 10) {
            val fCenter = EQ_BAND_FREQS[b].toFloat()
            val fLow = fCenter * 0.707f
            val fHigh = fCenter * 1.414f
            val k0 = (fLow / binHz).toInt().coerceIn(1, halfN - 1)
            val k1 = (fHigh / binHz).toInt().coerceIn(k0, halfN - 1)
            var peak = 0f
            for (k in k0..k1) {
                val mag = sqrt(fftRe[k] * fftRe[k] + fftIm[k] * fftIm[k]) / (fftN * 0.25f)
                if (mag > peak) peak = mag
            }
            // Логарифмическое сжатие в 0..1
            val norm = (ln(1.0 + peak * 18.0) / ln(19.0)).toFloat().coerceIn(0f, 1f)
            val prev = s10[b]
            s10[b] = prev + (norm - prev) * (if (norm > prev) 0.55f else 0.18f)
        }

        // 32 логарифмические полосы (40 Гц .. 15 кГц) для 3D-сцен
        val s32 = AudioFx.spectrum32
        val minF = 40.0
        val maxF = (sr * 0.42).coerceAtMost(15000.0)
        val ratio = (maxF / minF).pow(1.0 / 32.0)
        for (b in 0 until 32) {
            val f0 = minF * ratio.pow(b)
            val f1 = f0 * ratio
            val k0 = (f0 / binHz).toInt().coerceIn(1, halfN - 1)
            val k1 = (f1 / binHz).toInt().coerceIn(k0, halfN - 1)
            var peak = 0f
            for (k in k0..k1) {
                val mag = sqrt(fftRe[k] * fftRe[k] + fftIm[k] * fftIm[k]) / (fftN * 0.25f)
                if (mag > peak) peak = mag
            }
            val norm = (ln(1.0 + peak * 20.0) / ln(21.0)).toFloat().coerceIn(0f, 1f)
            val prev = s32[b]
            s32[b] = prev + (norm - prev) * (if (norm > prev) 0.55f else 0.20f)
        }
    }
}
