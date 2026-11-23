package com.vibe.radio.audio

import android.media.audiofx.Equalizer
import com.vibe.radio.data.AppSettings

/** Голосовые / звуковые эффекты. pitch применяется через Media3 (Sonic), остальное — в FxAudioProcessor. */
enum class VoiceEffect(val title: String, val emoji: String, val pitch: Float) {
    NONE("Обычный", "🎧", 1f),
    ECHO("Эхо", "🏔️", 1f),
    CAVE("Пещера", "🕳️", 1f),
    ROBOT("Робот", "🤖", 1f),
    MONSTER("Монстр", "👹", 0.62f),
    CHILD("Детский", "👶", 1.45f),
    CHIPMUNK("Бурундук", "🐿️", 1.9f),
    ALIEN("Пришелец", "👽", 1.2f),
    TELEPHONE("Телефон", "📞", 1f),
    UNDERWATER("Под водой", "🌊", 1f),
    BASS("Мега-бас", "🔊", 1f),
    STADIUM("Стадион", "🏟️", 1f),
}

class EqInfo(
    val bands: Int,
    val minLevel: Int,
    val maxLevel: Int,
    val freqsHz: List<Int>,
    val presets: List<String>,
)

/** Общее состояние между сервисом воспроизведения и UI (один процесс). */
object AudioFx {
    @Volatile var effect: VoiceEffect = VoiceEffect.NONE

    /** Громкость текущего звука 0..1 (RMS) — для анимации танцоров. */
    @Volatile var level: Float = 0f

    /** Энергия низких частот 0..1 — «бит». */
    @Volatile var bass: Float = 0f

    /** Сервис регистрирует здесь функцию применения настроек воспроизведения. */
    @Volatile var settingsHook: (() -> Unit)? = null

    private var eq: Equalizer? = null

    @Volatile var eqInfo: EqInfo? = null
        private set

    fun attachEqualizer(sessionId: Int) {
        releaseEqualizer()
        if (sessionId == 0) return
        try {
            val e = Equalizer(0, sessionId)
            eq = e
            val n = e.numberOfBands.toInt()
            val range = e.bandLevelRange
            eqInfo = EqInfo(
                bands = n,
                minLevel = range[0].toInt(),
                maxLevel = range[1].toInt(),
                freqsHz = (0 until n).map { e.getCenterFreq(it.toShort()) / 1000 },
                presets = (0 until e.numberOfPresets.toInt()).map { e.getPresetName(it.toShort()) },
            )
            applyEqualizer()
        } catch (_: Throwable) {
            eq = null
            eqInfo = null
        }
    }

    private fun savedBands(): List<Int> =
        AppSettings.eqBands.split(",").mapNotNull { it.trim().toIntOrNull() }

    fun applyEqualizer() {
        val e = eq ?: return
        try {
            val preset = AppSettings.eqPreset
            if (preset >= 0 && preset < e.numberOfPresets.toInt()) {
                e.usePreset(preset.toShort())
            } else {
                val n = e.numberOfBands.toInt()
                savedBands().forEachIndexed { i, v -> if (i < n) e.setBandLevel(i.toShort(), v.toShort()) }
            }
            e.setEnabled(AppSettings.eqEnabled)
        } catch (_: Throwable) {
        }
    }

    fun currentLevels(): List<Int> {
        val e = eq
        if (e != null) {
            try {
                return (0 until e.numberOfBands.toInt()).map { e.getBandLevel(it.toShort()).toInt() }
            } catch (_: Throwable) {
            }
        }
        val n = eqInfo?.bands ?: 5
        return savedBands().ifEmpty { List(n) { 0 } }
    }

    fun releaseEqualizer() {
        try { eq?.release() } catch (_: Throwable) { }
        eq = null
    }
}
