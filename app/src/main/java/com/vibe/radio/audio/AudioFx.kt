package com.vibe.radio.audio

import com.vibe.radio.data.AppSettings
import java.util.concurrent.atomic.AtomicInteger

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

/** 10 стандартных октавных полос графического эквалайзера (Гц). */
val EQ_BAND_FREQS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

val EQ_BAND_LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

/** Предустановки 10-полосного частотного эквалайзера (дБ, от -12 до +12). */
enum class EqPreset(val title: String, val emoji: String, val gains: List<Float>) {
    FLAT("Ровный", "➖", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    BASS("Мега-бас", "🔊", listOf(8f, 7f, 5f, 2f, 0f, 0f, 1f, 2f, 2f, 1f)),
    ROCK("Рок", "🎸", listOf(5f, 4f, 2f, -1f, -2f, 1f, 3f, 5f, 5f, 4f)),
    POP("Поп", "🎤", listOf(-1f, 2f, 4f, 5f, 3f, 0f, -1f, 1f, 3f, 3f)),
    ELECTRONIC("Клуб / EDM", "🎧", listOf(6f, 5f, 2f, 0f, -2f, 2f, 3f, 4f, 5f, 4f)),
    JAZZ("Джаз", "🎷", listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 2f, 3f, 4f)),
    CLASSICAL("Классика", "🎻", listOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 4f, 5f)),
    VOCAL("Вокал", "🗣️", listOf(-2f, -2f, -1f, 2f, 5f, 5f, 4f, 2f, 0f, -1f)),
    HIPHOP("Хип-хоп", "🔥", listOf(6f, 5f, 2f, 3f, -1f, -1f, 1f, 2f, 3f, 3f)),
    LOUDNESS("Громкость", "⚡", listOf(6f, 4f, 1f, -1f, -2f, -1f, 1f, 3f, 5f, 5f)),
    NIGHT("Ночной", "🌙", listOf(3f, 2f, 1f, 0f, 1f, 1f, 0f, -2f, -3f, -4f)),
    CUSTOM("Свой", "🎚️", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
}

/** Общее состояние между сервисом воспроизведения (DSP) и UI (один процесс). */
object AudioFx {
    @Volatile var effect: VoiceEffect = VoiceEffect.NONE

    /** Громкость текущего звука 0..1 (RMS) — для анимации 3D-персонажей. */
    @Volatile var level: Float = 0f

    /** Энергия низких частот 0..1 — «бит». */
    @Volatile var bass: Float = 0f

    /** Живой спектр 10 полос (31 Гц .. 16 кГц), значения 0..1 — для UI эквалайзера. */
    val spectrum10 = FloatArray(10)

    /** Живой спектр 32 полосы, значения 0..1 — для 3D-сцены и 3D-эквалайзера. */
    val spectrum32 = FloatArray(32)

    /** Настройки 10-полосного DSP-эквалайзера (читаются аудиопотоком). */
    @Volatile var eqEnabled: Boolean = true
    @Volatile var eqBassBoost: Float = 0f
    @Volatile var eqPreampDb: Float = 0f
    @Volatile var eqGainsDb: FloatArray = FloatArray(10)

    /** Счётчик изменений EQ, чтобы FxAudioProcessor пересчитал биквад-фильтры. */
    val eqVersion = AtomicInteger(1)

    /** Сервис регистрирует здесь функцию применения настроек воспроизведения. */
    @Volatile var settingsHook: (() -> Unit)? = null

    fun syncEqFromSettings() {
        eqEnabled = AppSettings.eqEnabled
        eqBassBoost = AppSettings.eqBassBoost.coerceIn(0f, 1f)
        eqPreampDb = AppSettings.eqPreampDb.coerceIn(-6f, 6f)
        val arr = FloatArray(10)
        val src = AppSettings.eqBands
        for (i in 0 until 10) {
            arr[i] = src.getOrElse(i) { 0f }.coerceIn(-12f, 12f)
        }
        eqGainsDb = arr
        eqVersion.incrementAndGet()
    }

    fun applyPreset(preset: EqPreset) {
        if (preset == EqPreset.CUSTOM) {
            AppSettings.update { eqPresetId = EqPreset.CUSTOM.name }
        } else {
            AppSettings.update {
                eqEnabled = true
                eqPresetId = preset.name
                eqBands = preset.gains.toList()
            }
        }
        syncEqFromSettings()
    }

    fun setBandGain(band: Int, db: Float) {
        if (band !in 0 until 10) return
        val clamped = db.coerceIn(-12f, 12f)
        val next = AppSettings.eqBands.toMutableList()
        while (next.size < 10) next.add(0f)
        next[band] = clamped
        AppSettings.eqEnabled = true
        AppSettings.eqPresetId = EqPreset.CUSTOM.name
        AppSettings.eqBands = next
        syncEqFromSettings()
    }

    fun commitEqSettings() {
        AppSettings.save()
        syncEqFromSettings()
    }

    fun resetEq() {
        AppSettings.update {
            eqPresetId = EqPreset.FLAT.name
            eqBands = List(10) { 0f }
            eqBassBoost = 0f
            eqPreampDb = 0f
        }
        syncEqFromSettings()
    }
}
