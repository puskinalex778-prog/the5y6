package com.vibe.radio.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode(val title: String) { DARK("Тёмная"), AMOLED("Чёрная"), LIGHT("Светлая") }

enum class RadioQuality(val title: String, val hint: String) {
    AUTO("Авто", "Wi-Fi — максимум, мобильная сеть — до 128 kbps"),
    LOW("Экономия", "до 64 kbps"),
    MEDIUM("Стандарт", "до 128 kbps"),
    HIGH("Максимум", "лучший доступный поток"),
}

enum class StartSection(val title: String) { MUSIC("Музыка"), RADIO("Радио") }

/** Все настройки приложения. Значения — Compose-состояние, поэтому UI обновляется сразу. */
object AppSettings {
    private var prefs: SharedPreferences? = null

    // Оформление
    var accentIndex by mutableIntStateOf(0)
    var customHue by mutableFloatStateOf(285f)
    var themeMode by mutableStateOf(ThemeMode.DARK)
    var animations by mutableStateOf(true)
    var showCover by mutableStateOf(true)
    var keepScreenOn by mutableStateOf(false)

    // Медиатека
    var scanOnStart by mutableStateOf(true)
    var minDurationSec by mutableIntStateOf(30)
    var hideMessengers by mutableStateOf(true)

    // Воспроизведение
    var startSection by mutableStateOf(StartSection.MUSIC)
    var pauseOnDisconnect by mutableStateOf(true)
    var skipSilence by mutableStateOf(false)
    var restoreQueue by mutableStateOf(true)

    // Эквалайзер
    var eqEnabled by mutableStateOf(false)
    var eqPreset by mutableIntStateOf(-1)
    var eqBands by mutableStateOf("")

    // Радио
    var radioQuality by mutableStateOf(RadioQuality.AUTO)
    var saveDataOnMobile by mutableStateOf(false)
    var autoNextStation by mutableStateOf(true)
    var regions by mutableStateOf(listOf("RU", "US", "KR", "DE"))

    // Игры
    var bestSnake by mutableIntStateOf(0)
    var bestNotes by mutableIntStateOf(0)

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs = p
        accentIndex = p.getInt("accent", 0)
        customHue = p.getFloat("hue", 285f)
        themeMode = enumOr(p.getString("theme", null), ThemeMode.DARK)
        animations = p.getBoolean("anim", true)
        showCover = p.getBoolean("cover", true)
        keepScreenOn = p.getBoolean("screenOn", false)
        scanOnStart = p.getBoolean("scanOnStart", true)
        minDurationSec = p.getInt("minDur", 30)
        hideMessengers = p.getBoolean("hideMsg", true)
        startSection = enumOr(p.getString("start", null), StartSection.MUSIC)
        pauseOnDisconnect = p.getBoolean("noisy", true)
        skipSilence = p.getBoolean("skipSilence", false)
        restoreQueue = p.getBoolean("restore", true)
        eqEnabled = p.getBoolean("eqOn", false)
        eqPreset = p.getInt("eqPreset", -1)
        eqBands = p.getString("eqBands", "") ?: ""
        radioQuality = enumOr(p.getString("quality", null), RadioQuality.AUTO)
        saveDataOnMobile = p.getBoolean("saveData", false)
        autoNextStation = p.getBoolean("autoNext", true)
        regions = (p.getString("regions", "RU,US,KR,DE") ?: "").split(",").filter { it.isNotBlank() }
        bestSnake = p.getInt("bestSnake", 0)
        bestNotes = p.getInt("bestNotes", 0)
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, def: T): T =
        runCatching { enumValueOf<T>(name ?: "") }.getOrDefault(def)

    /** Изменить настройки и сразу сохранить. */
    fun update(block: AppSettings.() -> Unit) {
        block()
        save()
    }

    fun save() {
        val p = prefs ?: return
        p.edit()
            .putInt("accent", accentIndex)
            .putFloat("hue", customHue)
            .putString("theme", themeMode.name)
            .putBoolean("anim", animations)
            .putBoolean("cover", showCover)
            .putBoolean("screenOn", keepScreenOn)
            .putBoolean("scanOnStart", scanOnStart)
            .putInt("minDur", minDurationSec)
            .putBoolean("hideMsg", hideMessengers)
            .putString("start", startSection.name)
            .putBoolean("noisy", pauseOnDisconnect)
            .putBoolean("skipSilence", skipSilence)
            .putBoolean("restore", restoreQueue)
            .putBoolean("eqOn", eqEnabled)
            .putInt("eqPreset", eqPreset)
            .putString("eqBands", eqBands)
            .putString("quality", radioQuality.name)
            .putBoolean("saveData", saveDataOnMobile)
            .putBoolean("autoNext", autoNextStation)
            .putString("regions", regions.joinToString(","))
            .putInt("bestSnake", bestSnake)
            .putInt("bestNotes", bestNotes)
            .apply()
    }
}
