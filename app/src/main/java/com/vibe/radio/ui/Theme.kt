package com.vibe.radio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.vibe.radio.data.AppSettings
import com.vibe.radio.data.ThemeMode

/** Цветовая схема: main — основной акцент, deep — второй цвет градиента, glow — контрастная подсветка. */
data class AccentPreset(val title: String, val main: Color, val deep: Color, val glow: Color)

val Accents = listOf(
    AccentPreset("Неон", Color(0xFFFF4FD8), Color(0xFF7B2FF7), Color(0xFF4FE3FF)),
    AccentPreset("Океан", Color(0xFF4FE3FF), Color(0xFF2F5BFF), Color(0xFFB4FF6A)),
    AccentPreset("Лайм", Color(0xFFB6FF3B), Color(0xFF0FA982), Color(0xFFFFE14F)),
    AccentPreset("Закат", Color(0xFFFFA23B), Color(0xFFFF3B6B), Color(0xFFFFE07A)),
    AccentPreset("Рубин", Color(0xFFFF4B5C), Color(0xFF9E1046), Color(0xFFFFB35C)),
    AccentPreset("Лаванда", Color(0xFFC59BFF), Color(0xFF5D3BFF), Color(0xFF7FF5FF)),
    AccentPreset("Золото", Color(0xFFFFD84F), Color(0xFFFF7A00), Color(0xFFFFFFFF)),
    AccentPreset("Мята", Color(0xFF5CFFC8), Color(0xFF008F9E), Color(0xFFB6A4FF)),
    AccentPreset("Сакура", Color(0xFFFFA7C4), Color(0xFFD6458F), Color(0xFFFFF1A8)),
    AccentPreset("Лёд", Color(0xFFCFE8FF), Color(0xFF4F7BA8), Color(0xFF8CF0FF)),
)

fun customAccent(hue: Float) = AccentPreset(
    "Свой",
    Color.hsv(hue % 360f, 0.70f, 1f),
    Color.hsv((hue + 320f) % 360f, 0.85f, 0.85f),
    Color.hsv((hue + 160f) % 360f, 0.60f, 1f),
)

/**
 * Палитра приложения. Значения читаются из настроек (Compose-состояние),
 * поэтому смена цвета или темы мгновенно перекрашивает весь интерфейс.
 */
object Neon {
    private val preset: AccentPreset
        get() = Accents.getOrNull(AppSettings.accentIndex) ?: customAccent(AppSettings.customHue)

    val isLight: Boolean get() = AppSettings.themeMode == ThemeMode.LIGHT

    /** Основной акцент. */
    val Pink: Color get() = preset.main
    /** Второй цвет градиента. */
    val Violet: Color get() = preset.deep
    /** Контрастная подсветка. */
    val Cyan: Color get() = if (isLight) preset.deep else preset.glow

    val Bg: Color
        get() = when (AppSettings.themeMode) {
            ThemeMode.DARK -> Color(0xFF0B0B1A)
            ThemeMode.AMOLED -> Color.Black
            ThemeMode.LIGHT -> Color(0xFFF6F4FA)
        }
    val Bg2: Color
        get() = when (AppSettings.themeMode) {
            ThemeMode.DARK -> lerp(Color(0xFF0B0B1A), preset.deep, 0.25f)
            ThemeMode.AMOLED -> lerp(Color.Black, preset.deep, 0.08f)
            ThemeMode.LIGHT -> lerp(Color.White, preset.main, 0.14f)
        }
    val Text: Color get() = if (isLight) Color(0xFF16121F) else Color.White
    val Text2: Color get() = Text.copy(alpha = 0.68f)
    val Card: Color get() = if (isLight) Color.Black.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.10f)
    val Line: Color get() = Text.copy(alpha = 0.08f)
    val Sheet: Color get() = if (isLight) Color.White else lerp(Bg, preset.deep, 0.18f)

    /** Цвет текста/иконок поверх акцентного градиента. */
    val OnAccent: Color
        get() = if ((preset.main.luminance() + preset.deep.luminance()) / 2f > 0.55f) Color(0xFF111111) else Color.White

    val bgBrush: Brush get() = Brush.verticalGradient(listOf(Bg2, Bg, Bg))
    val accent: Brush get() = Brush.linearGradient(listOf(Violet, Pink))
    val cardBrush: Brush get() = Brush.linearGradient(listOf(Card, Card))
}

@Composable
fun VibeTheme(content: @Composable () -> Unit) {
    val scheme = if (Neon.isLight) {
        lightColorScheme(
            primary = Neon.Violet, secondary = Neon.Pink, tertiary = Neon.Cyan,
            background = Neon.Bg, surface = Color.White,
            onPrimary = Color.White, onBackground = Neon.Text, onSurface = Neon.Text,
        )
    } else {
        darkColorScheme(
            primary = Neon.Pink, secondary = Neon.Cyan, tertiary = Neon.Violet,
            background = Neon.Bg, surface = Neon.Sheet,
            onPrimary = Neon.OnAccent, onBackground = Color.White, onSurface = Color.White,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
