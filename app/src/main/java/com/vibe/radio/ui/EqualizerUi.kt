@file:OptIn(ExperimentalMaterial3Api::class)

package com.vibe.radio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibe.radio.audio.AudioFx
import com.vibe.radio.audio.EQ_BAND_LABELS
import com.vibe.radio.audio.EqPreset
import com.vibe.radio.data.AppSettings
import kotlin.math.roundToInt

/** Модальное окно частотного 10-полосного эквалайзера (открывается прямо из плеера). */
@Composable
fun EqualizerSheet(vm: PlayerViewModel) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { vm.eqOpen = false },
        sheetState = state,
        containerColor = Neon.Sheet,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
        ) {
            EqualizerPanel(isPlaying = vm.isPlaying)
        }
    }
}

/**
 * 10-полосный частотный DSP-эквалайзер (31 Гц .. 16 кГц) с живым спектром,
 * АЧХ-кривой, 12 предустановками, Bass Boost и Preamp.
 */
@Composable
fun EqualizerPanel(isPlaying: Boolean, modifier: Modifier = Modifier) {
    val enabled = AppSettings.eqEnabled
    val bands = AppSettings.eqBands
    val activePreset = AppSettings.eqPresetId

    // Плавное обновление живого спектра в графике АЧХ
    var frameTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isPlaying, AppSettings.animations) {
        if (!isPlaying || !AppSettings.animations) return@LaunchedEffect
        while (true) {
            withFrameNanos { frameTick = it }
        }
    }

    Column(modifier.fillMaxWidth()) {
        // Заголовок + вкл/выкл + сброс
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Частотный эквалайзер", color = Neon.Text, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text("10 полос · 31 Гц – 16 кГц · DSP", color = Neon.Text2, fontSize = 12.sp)
            }
            TextButton(onClick = { AudioFx.resetEq() }) {
                Text("Сброс", color = Neon.Cyan, fontSize = 13.sp)
            }
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    AppSettings.update { eqEnabled = on }
                    AudioFx.syncEqFromSettings()
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Neon.OnAccent,
                    checkedTrackColor = Neon.Pink,
                    uncheckedTrackColor = Neon.Card,
                    uncheckedBorderColor = Neon.Line,
                    uncheckedThumbColor = Neon.Text2,
                ),
            )
        }

        Spacer(Modifier.height(10.dp))

        // График живого спектра + гладкая кривая АЧХ эквалайзера
        Box(
            Modifier
                .fillMaxWidth()
                .height(106.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Neon.Bg.copy(alpha = 0.65f))
                .border(1.dp, Neon.Line, RoundedCornerShape(18.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                @Suppress("UNUSED_VARIABLE")
                val tick = frameTick
                val w = size.width
                val h = size.height
                val midY = h * 0.5f

                // Горизонтальная сетка (-12 / 0 / +12 дБ)
                drawLine(Neon.Line, Offset(0f, h * 0.12f), Offset(w, h * 0.12f), strokeWidth = 1f)
                drawLine(Neon.Text2.copy(alpha = 0.25f), Offset(0f, midY), Offset(w, midY), strokeWidth = 1.5f)
                drawLine(Neon.Line, Offset(0f, h * 0.88f), Offset(w, h * 0.88f), strokeWidth = 1f)

                val stepX = w / 10f

                // Живой 10-полосный спектр на фоне
                val spec = AudioFx.spectrum10
                for (i in 0 until 10) {
                    val v = if (isPlaying) spec[i].coerceIn(0f, 1f) else 0f
                    val bh = v * h * 0.85f
                    val bx = i * stepX + stepX * 0.18f
                    drawRoundRect(
                        Brush.verticalGradient(
                            listOf(Neon.Cyan.copy(alpha = 0.55f), Neon.Violet.copy(alpha = 0.12f)),
                            startY = h - bh,
                            endY = h,
                        ),
                        topLeft = Offset(bx, h - bh),
                        size = Size(stepX * 0.64f, bh),
                        cornerRadius = CornerRadius(6f, 6f),
                    )
                }

                // Гладкая кривая АЧХ по 10 точкам
                val pts = List(10) { i ->
                    val g = if (enabled) bands.getOrElse(i) { 0f } else 0f
                    val x = (i + 0.5f) * stepX
                    val y = midY - (g / 12f) * (h * 0.38f)
                    Offset(x, y)
                }
                val curve = Path().apply {
                    moveTo(pts.first().x, pts.first().y)
                    for (i in 0 until pts.size - 1) {
                        val p0 = pts[i]
                        val p1 = pts[i + 1]
                        val cx = (p0.x + p1.x) * 0.5f
                        cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                    }
                }
                val fillPath = Path().apply {
                    addPath(curve)
                    lineTo(pts.last().x, midY)
                    lineTo(pts.first().x, midY)
                    close()
                }
                val alphaK = if (enabled) 1f else 0.35f
                drawPath(
                    fillPath,
                    Brush.verticalGradient(
                        listOf(Neon.Pink.copy(alpha = 0.28f * alphaK), Color.Transparent),
                    ),
                )
                drawPath(
                    curve,
                    Brush.horizontalGradient(listOf(Neon.Cyan.copy(alpha = alphaK), Neon.Pink.copy(alpha = alphaK))),
                    style = Stroke(width = 3.2f, cap = StrokeCap.Round),
                )
                pts.forEach { p ->
                    drawCircle(Neon.Pink.copy(alpha = alphaK), 4.5f, p)
                    drawCircle(Color.White.copy(alpha = alphaK), 2f, p)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Предустановки (пресеты)
        Text("Предустановки", color = Neon.Text2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(EqPreset.entries.toList()) { preset ->
                Chip(
                    text = preset.title,
                    active = activePreset == preset.name,
                    leading = preset.emoji,
                ) {
                    AudioFx.applyPreset(preset)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 10 вертикальных ползунков частот (31 Гц .. 16 кГц)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Neon.Card)
                .border(1.dp, Neon.Line, RoundedCornerShape(18.dp))
                .padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            for (b in 0 until 10) {
                val gain = bands.getOrElse(b) { 0f }
                VerticalBandFader(
                    label = EQ_BAND_LABELS[b],
                    gainDb = gain,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    onChange = { AudioFx.setBandGain(b, it) },
                    onFinish = { AudioFx.commitEqSettings() },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Усиление баса (Bass Boost) + Предусиление (Preamp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Neon.Card)
                .border(1.dp, Neon.Line, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("🔊 Усиление баса", color = Neon.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${(AppSettings.eqBassBoost * 100).roundToInt()}%", color = Neon.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = AppSettings.eqBassBoost,
                onValueChange = {
                    AppSettings.eqBassBoost = it
                    AppSettings.eqEnabled = true
                    AudioFx.syncEqFromSettings()
                },
                onValueChangeFinished = { AudioFx.commitEqSettings() },
                valueRange = 0f..1f,
                colors = eqSliderColors(),
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("🎚️ Предусиление (Preamp)", color = Neon.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                val preInt = AppSettings.eqPreampDb.roundToInt()
                Text(
                    if (preInt > 0) "+$preInt дБ" else "$preInt дБ",
                    color = Neon.Pink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                )
            }
            Slider(
                value = AppSettings.eqPreampDb,
                onValueChange = {
                    AppSettings.eqPreampDb = it
                    AppSettings.eqEnabled = true
                    AudioFx.syncEqFromSettings()
                },
                onValueChangeFinished = { AudioFx.commitEqSettings() },
                valueRange = -6f..6f,
                colors = eqSliderColors(),
            )
        }
    }
}

@Composable
private fun VerticalBandFader(
    label: String,
    gainDb: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit,
    onFinish: () -> Unit,
) {
    val dbInt = gainDb.roundToInt()
    val dbText = when {
        dbInt > 0 -> "+$dbInt"
        else -> "$dbInt"
    }
    Column(
        modifier = modifier.padding(horizontal = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            dbText,
            color = if (enabled && dbInt != 0) Neon.Cyan else Neon.Text2,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Canvas(
            Modifier
                .width(28.dp)
                .height(148.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val norm = (1f - (offset.y / size.height.toFloat())).coerceIn(0f, 1f)
                        val db = ((norm * 24f - 12f) * 2f).roundToInt() / 2f
                        onChange(db)
                        onFinish()
                    }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { onFinish() },
                        onDragCancel = { onFinish() },
                    ) { change, _ ->
                        change.consume()
                        val norm = (1f - (change.position.y / size.height.toFloat())).coerceIn(0f, 1f)
                        val db = ((norm * 24f - 12f) * 2f).roundToInt() / 2f
                        onChange(db)
                    }
                },
        ) {
            val cx = size.width * 0.5f
            val padY = 12f
            val usableH = size.height - padY * 2f
            val midY = padY + usableH * 0.5f
            val norm = ((gainDb + 12f) / 24f).coerceIn(0f, 1f)
            val thumbY = padY + (1f - norm) * usableH

            // Дорожка
            drawLine(
                Neon.Line,
                Offset(cx, padY),
                Offset(cx, size.height - padY),
                strokeWidth = 6f,
                cap = StrokeCap.Round,
            )
            // Отметка 0 дБ
            drawLine(Neon.Text2.copy(alpha = 0.35f), Offset(cx - 7f, midY), Offset(cx + 7f, midY), strokeWidth = 1.5f)
            // Активная часть от 0 дБ до текущего значения
            val activeCol = if (enabled) (if (gainDb >= 0f) Neon.Pink else Neon.Cyan) else Neon.Text2.copy(alpha = 0.35f)
            drawLine(
                activeCol,
                Offset(cx, midY),
                Offset(cx, thumbY),
                strokeWidth = 7f,
                cap = StrokeCap.Round,
            )
            // Бегунок
            drawCircle(activeCol, 9.5f, Offset(cx, thumbY))
            drawCircle(Color.White.copy(alpha = if (enabled) 0.9f else 0.4f), 4f, Offset(cx, thumbY))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = Neon.Text2,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun eqSliderColors() = SliderDefaults.colors(
    thumbColor = Neon.Pink,
    activeTrackColor = Neon.Pink,
    inactiveTrackColor = Neon.Line,
)
