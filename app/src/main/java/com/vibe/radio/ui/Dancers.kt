package com.vibe.radio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.vibe.radio.audio.AudioFx
import com.vibe.radio.data.AppSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class Dancer(val title: String, val emoji: String) {
    RUNNER("Кот-бегун", "🏃"),
    CAT("Вислоухий кот", "🐱"),
    RAT("Белый крыс", "🐀"),
    DIKAMIK("Дикамик", "👾"),
    ROACH("Таракан", "🪳"),
    GIRL("Девочка", "👧"),
    BARS("Эквалайзер", "📊"),
}

/** Сцена с танцующим персонажем. Движения ускоряются и усиливаются в такт басу. */
@Composable
fun DancerStage(dancer: Dancer, isPlaying: Boolean, modifier: Modifier = Modifier) {
    var t by remember { mutableFloatStateOf(0f) }
    var beat by remember { mutableFloatStateOf(0f) }
    var level by remember { mutableFloatStateOf(0f) }
    // для кота-бегуна: высота прыжка и пройденный путь
    var jump by remember { mutableFloatStateOf(0f) }
    var run by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isPlaying) {
        var last = 0L
        var peak = 0.05f
        var prevNorm = 0f
        var vel = 0f
        var cooldown = 0f
        while (true) {
            withFrameNanos { now ->
                var dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                last = now
                if (!AppSettings.animations) dt = 0f
                val raw = if (isPlaying) AudioFx.bass else 0f
                peak = maxOf(peak * 0.996f, raw, 0.04f)
                val norm = (raw / peak).coerceIn(0f, 1f)
                beat += (norm - beat) * (if (norm > beat) 0.55f else 0.10f)
                val lv = if (isPlaying) (AudioFx.level * 3.5f).coerceIn(0f, 1f) else 0f
                level += (lv - level) * 0.2f
                t += dt * (if (isPlaying) 1f + beat * 0.9f else 0.3f)

                // прыжок на сильную долю
                cooldown -= dt
                if (isPlaying && dt > 0f && norm > 0.78f && prevNorm <= 0.78f && cooldown <= 0f && jump <= 0.001f) {
                    vel = 2.4f + 1.4f * norm
                    cooldown = 0.32f
                }
                prevNorm = norm
                if (jump > 0f || vel > 0f) {
                    vel -= 9.5f * dt
                    jump = (jump + vel * dt).coerceAtLeast(0f)
                    if (jump == 0f) vel = 0f
                }
                run += dt * (if (isPlaying) 1.1f + level * 1.6f + beat * 0.6f else 0.18f)
            }
        }
    }

    Canvas(modifier) {
        if (dancer == Dancer.RUNNER) {
            drawRunner(t, beat, level, jump, run)
            return@Canvas
        }
        drawStageFx(t, beat, level)
        val u = min(size.width, size.height) / 13f
        val c = Offset(size.width / 2f, size.height * 0.56f)
        when (dancer) {
            Dancer.CAT -> drawCat(c, u, t, beat)
            Dancer.RAT -> drawRat(c, u, t, beat)
            Dancer.DIKAMIK -> drawDikamik(c, u, t, beat)
            Dancer.ROACH -> drawRoach(c, u, t, beat)
            Dancer.GIRL -> drawGirl(c, u, t, beat)
            Dancer.BARS -> drawBars(t, beat, level)
            Dancer.RUNNER -> Unit
        }
    }
}

// ---------------- общий фон ----------------
private fun DrawScope.drawStageFx(t: Float, beat: Float, level: Float) {
    val center = Offset(size.width / 2, size.height * 0.56f)
    val r = min(size.width, size.height) * (0.33f + 0.07f * beat)
    drawCircle(
        Brush.radialGradient(
            listOf(Neon.Pink.copy(alpha = 0.35f + 0.3f * beat), Neon.Violet.copy(alpha = 0.15f), Color.Transparent),
            center = center, radius = r * 1.6f,
        ),
        radius = r * 1.6f, center = center,
    )
    // кольцо лучей
    val rays = 36
    for (i in 0 until rays) {
        val a = (i * 2 * PI / rays).toFloat() + t * 0.4f
        val len = r * (0.12f + 0.25f * level * (0.5f + 0.5f * sin(t * 7f + i * 1.7f)))
        val p1 = center + Offset(cos(a), sin(a)) * (r * 1.05f)
        val p2 = center + Offset(cos(a), sin(a)) * (r * 1.05f + len)
        drawLine(
            if (i % 2 == 0) Neon.Cyan.copy(alpha = 0.6f) else Neon.Pink.copy(alpha = 0.6f),
            p1, p2, strokeWidth = r * 0.025f, cap = StrokeCap.Round,
        )
    }
    // летающие частицы
    for (i in 0 until 26) {
        val seed = (i * 73 % 100) / 100f
        val x = ((seed * size.width + t * 18f * (1 + i % 3)) % size.width)
        val y = size.height - ((t * 30f * (0.6f + seed) + i * 57f) % size.height)
        drawCircle(
            (if (i % 3 == 0) Neon.Cyan else if (i % 3 == 1) Neon.Pink else Color.White)
                .copy(alpha = 0.25f + 0.4f * beat),
            radius = 2f + 4f * seed + 4f * beat, center = Offset(x, y),
        )
    }
    // «пол»
    drawOval(
        Color.Black.copy(alpha = 0.35f),
        topLeft = Offset(center.x - r * 0.7f, center.y + r * 0.95f),
        size = Size(r * 1.4f, r * 0.22f),
    )
}

// ---------------- помощники ----------------
private fun DrawScope.limb(from: Offset, angleDeg: Float, len: Float, w: Float, color: Color): Offset {
    val a = Math.toRadians(angleDeg.toDouble())
    val to = from + Offset(cos(a).toFloat(), sin(a).toFloat()) * len
    drawLine(color, from, to, strokeWidth = w, cap = StrokeCap.Round)
    return to
}

private fun DrawScope.arm(from: Offset, a1: Float, a2: Float, len: Float, w: Float, color: Color): Offset {
    val elbow = limb(from, a1, len, w, color)
    return limb(elbow, a1 + a2, len * 0.9f, w * 0.9f, color)
}

private fun DrawScope.ovalAt(c: Offset, w: Float, h: Float, color: Color) =
    drawOval(color, topLeft = Offset(c.x - w / 2, c.y - h / 2), size = Size(w, h))

private fun DrawScope.ovalAt(c: Offset, w: Float, h: Float, brush: Brush) =
    drawOval(brush, topLeft = Offset(c.x - w / 2, c.y - h / 2), size = Size(w, h))

private fun DrawScope.cuteEye(c: Offset, r: Float, iris: Color, t: Float, lookX: Float = 0f) {
    val blink = (t % 3.7f) < 0.13f
    if (blink) {
        drawLine(Color(0xFF222222), c - Offset(r, 0f), c + Offset(r, 0f), strokeWidth = r * 0.35f, cap = StrokeCap.Round)
        return
    }
    drawCircle(Color.White, r, c)
    drawCircle(iris, r * 0.72f, c + Offset(lookX * r * 0.2f, r * 0.05f))
    drawCircle(Color(0xFF111111), r * 0.42f, c + Offset(lookX * r * 0.25f, r * 0.08f))
    drawCircle(Color.White, r * 0.2f, c + Offset(-r * 0.25f, -r * 0.3f))
}

private class Pose(t: Float, beat: Float) {
    val swing = sin(t * 6f)
    val bounce = abs(sin(t * 6f))
    val tilt = swing * (8f + 10f * beat)
    val lift = bounce * (0.4f + 0.9f * beat)
    val squash = 1f + 0.06f * beat * (1 - bounce)
}

// ---------------- Шотландский вислоухий кот ----------------
private fun DrawScope.drawCat(c: Offset, u: Float, t: Float, beat: Float) {
    val p = Pose(t, beat)
    val fur = Color(0xFF9BA6B8)
    val furDark = Color(0xFF7D879A)
    val belly = Color(0xFFEDE8E2)
    withTransform({
        translate(c.x, c.y - p.lift * u)
        rotate(p.tilt * 0.5f, Offset.Zero)
        scale(1f / p.squash, p.squash, Offset(0f, 4f * u))
    }) {
        // хвост
        val tail = Path().apply {
            moveTo(1.6f * u, 3.6f * u)
            quadraticBezierTo(4.6f * u, 3.2f * u, (3.8f + 0.8f * sin(t * 9f)) * u, 0.2f * u)
        }
        drawPath(tail, furDark, style = Stroke(width = 0.9f * u, cap = StrokeCap.Round))
        // ноги
        ovalAt(Offset(-1.1f * u, (4.4f - 0.3f * p.swing) * u), 1.5f * u, 1.1f * u, furDark)
        ovalAt(Offset(1.1f * u, (4.4f + 0.3f * p.swing) * u), 1.5f * u, 1.1f * u, furDark)
        // тело
        ovalAt(Offset(0f, 2.1f * u), 4.4f * u, 5.0f * u, fur)
        ovalAt(Offset(0f, 2.5f * u), 2.6f * u, 3.6f * u, belly)
        // лапки
        arm(Offset(-1.6f * u, 0.8f * u), -150f + 35f * p.swing, 40f, 1.4f * u, 0.85f * u, fur)
        arm(Offset(1.6f * u, 0.8f * u), -30f + 35f * p.swing, -40f, 1.4f * u, 0.85f * u, fur)
        // голова
        withTransform({ rotate(p.tilt, Offset(0f, -1.5f * u)) }) {
            ovalAt(Offset(0f, -1.6f * u), 5.0f * u, 4.4f * u, fur)
            // сложенные ушки — маленькие и прижатые вперёд
            listOf(-1f, 1f).forEach { s ->
                val ear = Path().apply {
                    moveTo(s * 1.0f * u, -3.5f * u)
                    quadraticBezierTo(s * 1.8f * u, -4.2f * u, s * 2.1f * u, -3.1f * u)
                    quadraticBezierTo(s * 1.6f * u, -3.0f * u, s * 1.0f * u, -3.5f * u)
                    close()
                }
                drawPath(ear, furDark)
            }
            // полоски на лбу
            for (i in -1..1) drawLine(furDark, Offset(i * 0.4f * u, -3.6f * u), Offset(i * 0.3f * u, -2.9f * u), 0.18f * u, cap = StrokeCap.Round)
            cuteEye(Offset(-1.0f * u, -1.7f * u), 0.62f * u, Color(0xFFF2B33D), t, p.swing)
            cuteEye(Offset(1.0f * u, -1.7f * u), 0.62f * u, Color(0xFFF2B33D), t, p.swing)
            // щёчки
            drawCircle(Color(0xFFFF8FB1).copy(alpha = 0.35f + 0.4f * beat), 0.45f * u, Offset(-1.7f * u, -0.8f * u))
            drawCircle(Color(0xFFFF8FB1).copy(alpha = 0.35f + 0.4f * beat), 0.45f * u, Offset(1.7f * u, -0.8f * u))
            // нос и рот
            val nose = Path().apply {
                moveTo(-0.25f * u, -1.05f * u); lineTo(0.25f * u, -1.05f * u); lineTo(0f, -0.75f * u); close()
            }
            drawPath(nose, Color(0xFFFF7FA0))
            drawArc(Color(0xFF333333), 0f, 180f, false, Offset(-0.5f * u, -0.95f * u), Size(0.5f * u, 0.4f * u), style = Stroke(0.12f * u))
            drawArc(Color(0xFF333333), 0f, 180f, false, Offset(0f, -0.95f * u), Size(0.5f * u, 0.4f * u), style = Stroke(0.12f * u))
            // усы
            listOf(-1f, 1f).forEach { s ->
                for (k in -1..1) drawLine(
                    Color.White.copy(alpha = 0.8f),
                    Offset(s * 1.0f * u, -0.8f * u), Offset(s * 2.8f * u, (-0.9f + k * 0.35f) * u), 0.07f * u,
                )
            }
        }
    }
}

// ---------------- Белый крыс ----------------
private fun DrawScope.drawRat(c: Offset, u: Float, t: Float, beat: Float) {
    val p = Pose(t, beat)
    val white = Color(0xFFF7F7F7)
    val shade = Color(0xFFDADADF)
    val pink = Color(0xFFFFB3C6)
    withTransform({
        translate(c.x, c.y - p.lift * u)
        rotate(p.tilt * 0.6f, Offset.Zero)
        scale(1f / p.squash, p.squash, Offset(0f, 4f * u))
    }) {
        // длинный розовый хвост-волна
        val tail = Path().apply {
            moveTo(1.2f * u, 4.0f * u)
            for (i in 1..20) {
                val x = (1.2f + i * 0.3f) * u
                val y = (4.0f - i * 0.12f + 0.45f * sin(t * 8f + i * 0.6f)) * u
                lineTo(x, y)
            }
        }
        drawPath(tail, pink, style = Stroke(width = 0.35f * u, cap = StrokeCap.Round))
        // лапки ног
        ovalAt(Offset(-1.0f * u, (4.5f - 0.3f * p.swing) * u), 1.4f * u, 0.7f * u, pink)
        ovalAt(Offset(1.0f * u, (4.5f + 0.3f * p.swing) * u), 1.4f * u, 0.7f * u, pink)
        // тело-груша
        ovalAt(Offset(0f, 2.3f * u), 4.0f * u, 4.8f * u, Brush.verticalGradient(listOf(white, shade), startY = 0f, endY = 5f * u))
        // руки
        arm(Offset(-1.3f * u, 1.0f * u), -140f + 40f * p.swing, 50f, 1.2f * u, 0.55f * u, white)
            .let { drawCircle(pink, 0.32f * u, it) }
        arm(Offset(1.3f * u, 1.0f * u), -40f + 40f * p.swing, -50f, 1.2f * u, 0.55f * u, white)
            .let { drawCircle(pink, 0.32f * u, it) }
        // голова с вытянутой мордочкой
        withTransform({ rotate(p.tilt, Offset(0f, -1.0f * u)) }) {
            // уши
            listOf(-1f, 1f).forEach { s ->
                drawCircle(white, 1.25f * u, Offset(s * 1.7f * u, -3.1f * u))
                drawCircle(pink, 0.85f * u, Offset(s * 1.7f * u, -3.1f * u))
            }
            ovalAt(Offset(0f, -1.7f * u), 3.6f * u, 3.4f * u, white)
            ovalAt(Offset(0f, -0.6f * u), 2.0f * u, 2.0f * u, white)
            cuteEye(Offset(-0.85f * u, -2.0f * u), 0.45f * u, Color(0xFFE0245E), t, p.swing)
            cuteEye(Offset(0.85f * u, -2.0f * u), 0.45f * u, Color(0xFFE0245E), t, p.swing)
            drawCircle(Color(0xFFFF6F91), 0.32f * u, Offset(0f, 0.25f * u))
            // зубки
            drawRect(Color.White, Offset(-0.22f * u, 0.55f * u), Size(0.2f * u, 0.35f * u))
            drawRect(Color.White, Offset(0.02f * u, 0.55f * u), Size(0.2f * u, 0.35f * u))
            listOf(-1f, 1f).forEach { s ->
                for (k in -1..1) drawLine(
                    Color(0xFFBBBBBB), Offset(s * 0.4f * u, 0.2f * u),
                    Offset(s * 2.3f * u, (0.1f + k * 0.35f + 0.1f * p.swing) * u), 0.06f * u,
                )
            }
        }
    }
}

// ---------------- Дикамик (оригинальный пушистый персонаж) ----------------
private fun DrawScope.drawDikamik(c: Offset, u: Float, t: Float, beat: Float) {
    val p = Pose(t, beat)
    val body = Color(0xFF8E5CFF)
    val bodyLight = Color(0xFFB794FF)
    withTransform({
        translate(c.x, c.y - p.lift * 1.4f * u)
        rotate(p.tilt * 0.7f, Offset.Zero)
        scale(1f / p.squash, p.squash * (1f + 0.05f * beat), Offset(0f, 3.5f * u))
    }) {
        // ножки
        ovalAt(Offset(-1.2f * u, (3.9f - 0.4f * p.swing) * u), 1.6f * u, 1.0f * u, Color(0xFF5A2DBA))
        ovalAt(Offset(1.2f * u, (3.9f + 0.4f * p.swing) * u), 1.6f * u, 1.0f * u, Color(0xFF5A2DBA))
        // пушистая кайма
        val fluff = 22
        for (i in 0 until fluff) {
            val a = (i * 2 * PI / fluff).toFloat()
            val rr = 3.2f * u + 0.15f * u * sin(t * 10f + i)
            drawCircle(body, 0.75f * u, Offset(cos(a) * rr, 0.6f * u + sin(a) * rr))
        }
        drawCircle(Brush.radialGradient(listOf(bodyLight, body), center = Offset(-u, -0.5f * u), radius = 4f * u), 3.3f * u, Offset(0f, 0.6f * u))
        // ручки
        arm(Offset(-3.0f * u, 0.8f * u), -160f + 50f * p.swing, 30f, 1.3f * u, 0.7f * u, body)
        arm(Offset(3.0f * u, 0.8f * u), -20f + 50f * p.swing, -30f, 1.3f * u, 0.7f * u, body)
        // антенна со светящимся шариком
        val tip = Offset(0.8f * u * sin(t * 5f), -4.4f * u)
        drawLine(Color(0xFF5A2DBA), Offset(0f, -2.4f * u), tip, 0.2f * u, cap = StrokeCap.Round)
        drawCircle(Neon.Cyan.copy(alpha = 0.35f + 0.5f * beat), (0.9f + 0.4f * beat) * u, tip)
        drawCircle(Neon.Cyan, 0.45f * u, tip)
        // гребешок
        for (i in -2..2) {
            val spike = Path().apply {
                moveTo((i * 0.6f - 0.3f) * u, -2.4f * u)
                lineTo((i * 0.6f) * u, (-3.4f + abs(i) * 0.25f - 0.3f * beat) * u)
                lineTo((i * 0.6f + 0.3f) * u, -2.4f * u)
                close()
            }
            drawPath(spike, Neon.Pink)
        }
        cuteEye(Offset(-1.15f * u, -0.2f * u), 0.95f * u, Color(0xFF39D98A), t, p.swing)
        cuteEye(Offset(1.15f * u, -0.2f * u), 0.95f * u, Color(0xFF39D98A), t + 0.4f, p.swing)
        // рот — открывается в такт
        val mouthH = (0.4f + 0.9f * beat) * u
        drawOval(Color(0xFF2B0B4D), Offset(-0.8f * u, 1.3f * u), Size(1.6f * u, mouthH))
        drawRect(Color.White, Offset(-0.45f * u, 1.3f * u), Size(0.35f * u, 0.3f * u))
        drawRect(Color.White, Offset(0.1f * u, 1.3f * u), Size(0.35f * u, 0.3f * u))
    }
}

// ---------------- Таракан ----------------
private fun DrawScope.drawRoach(c: Offset, u: Float, t: Float, beat: Float) {
    val p = Pose(t, beat)
    val brown = Color(0xFF6B3A1E)
    val brownLight = Color(0xFF9A5B2E)
    val leg = Color(0xFF4A2512)
    withTransform({
        translate(c.x, c.y - p.lift * u)
        rotate(p.tilt * 0.8f, Offset.Zero)
        scale(1f / p.squash, p.squash, Offset(0f, 4f * u))
    }) {
        // нижние лапки (стоит на них)
        arm(Offset(-0.8f * u, 3.4f * u), 110f + 10f * p.swing, -30f, 1.0f * u, 0.22f * u, leg)
        arm(Offset(0.8f * u, 3.4f * u), 70f + 10f * p.swing, 30f, 1.0f * u, 0.22f * u, leg)
        // брюшко с сегментами
        ovalAt(Offset(0f, 1.9f * u), 3.2f * u, 4.6f * u, brown)
        for (i in 0..4) {
            val y = (0.6f + i * 0.7f) * u
            drawArc(leg, 20f, 140f, false, Offset(-1.4f * u, y - 0.3f * u), Size(2.8f * u, 0.6f * u), style = Stroke(0.08f * u))
        }
        // крылья-надкрылья, чуть раскрываются на бите
        val open = 8f + 18f * beat
        withTransform({ rotate(-open, Offset(-0.2f * u, -0.2f * u)) }) {
            ovalAt(Offset(-0.9f * u, 1.9f * u), 1.7f * u, 4.4f * u, brownLight.copy(alpha = 0.85f))
        }
        withTransform({ rotate(open, Offset(0.2f * u, -0.2f * u)) }) {
            ovalAt(Offset(0.9f * u, 1.9f * u), 1.7f * u, 4.4f * u, brownLight.copy(alpha = 0.85f))
        }
        // средние и верхние лапки — машут
        arm(Offset(-1.3f * u, 0.9f * u), -170f + 30f * p.swing, 50f, 1.1f * u, 0.2f * u, leg)
        arm(Offset(1.3f * u, 0.9f * u), -10f + 30f * p.swing, -50f, 1.1f * u, 0.2f * u, leg)
        arm(Offset(-1.0f * u, -0.2f * u), -130f - 40f * p.swing, -40f, 1.2f * u, 0.2f * u, leg)
        arm(Offset(1.0f * u, -0.2f * u), -50f - 40f * p.swing, 40f, 1.2f * u, 0.2f * u, leg)
        // грудь и голова
        ovalAt(Offset(0f, -0.5f * u), 2.6f * u, 1.6f * u, brown)
        withTransform({ rotate(p.tilt, Offset(0f, -1.5f * u)) }) {
            ovalAt(Offset(0f, -1.8f * u), 2.2f * u, 1.9f * u, brownLight)
            // усики
            listOf(-1f, 1f).forEach { s ->
                val ant = Path().apply {
                    moveTo(s * 0.4f * u, -2.6f * u)
                    quadraticBezierTo(
                        s * (1.5f + 0.6f * sin(t * 7f)) * u, -5.0f * u,
                        s * (3.0f + 0.8f * sin(t * 5f + s)) * u, (-5.2f + 0.6f * cos(t * 6f)) * u,
                    )
                }
                drawPath(ant, leg, style = Stroke(0.14f * u, cap = StrokeCap.Round))
            }
            cuteEye(Offset(-0.5f * u, -1.95f * u), 0.38f * u, Color(0xFF5B3A1A), t, p.swing)
            cuteEye(Offset(0.5f * u, -1.95f * u), 0.38f * u, Color(0xFF5B3A1A), t, p.swing)
            drawArc(Color(0xFF2A1408), 0f, 180f, false, Offset(-0.4f * u, -1.5f * u), Size(0.8f * u, 0.45f * u), style = Stroke(0.1f * u, cap = StrokeCap.Round))
        }
    }
}

// ---------------- Девочка с русыми волосами ----------------
private fun DrawScope.drawGirl(c: Offset, u: Float, t: Float, beat: Float) {
    val p = Pose(t, beat)
    val skin = Color(0xFFFFDCC6)
    val hair = Color(0xFFC9A27E)
    val hairDark = Color(0xFFA9835E)
    val dress = Neon.Pink
    withTransform({
        translate(c.x, c.y - p.lift * 0.8f * u)
        rotate(p.tilt * 0.4f, Offset.Zero)
    }) {
        // ноги
        limb(Offset(-0.6f * u, 2.6f * u), 95f + 8f * p.swing, 2.0f * u, 0.5f * u, skin)
            .let { ovalAt(it + Offset(-0.1f * u, 0.1f * u), 0.9f * u, 0.45f * u, Color(0xFF3B2A6B)) }
        limb(Offset(0.6f * u, 2.6f * u), 85f + 8f * p.swing, 2.0f * u, 0.5f * u, skin)
            .let { ovalAt(it + Offset(0.1f * u, 0.1f * u), 0.9f * u, 0.45f * u, Color(0xFF3B2A6B)) }
        // волосы сзади — длинные, развеваются
        val sway = 0.6f * sin(t * 6f - 0.8f) * (0.5f + beat)
        val back = Path().apply {
            moveTo(-1.9f * u, -2.4f * u)
            cubicTo(-2.4f * u, 0f, (-2.2f + sway) * u, 1.0f * u, (-1.6f + sway) * u, 1.6f * u)
            lineTo((1.6f + sway) * u, 1.6f * u)
            cubicTo((2.2f + sway) * u, 1.0f * u, 2.4f * u, 0f, 1.9f * u, -2.4f * u)
            close()
        }
        drawPath(back, hairDark)
        // платье
        val flare = 0.4f * p.swing
        val dressPath = Path().apply {
            moveTo(-0.9f * u, -0.4f * u)
            lineTo(0.9f * u, -0.4f * u)
            lineTo((1.9f + flare) * u, 2.8f * u)
            quadraticBezierTo(0f, 3.2f * u, (-1.9f + flare) * u, 2.8f * u)
            close()
        }
        drawPath(dressPath, Brush.verticalGradient(listOf(dress, Neon.Violet), startY = -0.4f * u, endY = 3f * u))
        // руки машут
        arm(Offset(-0.85f * u, -0.1f * u), -150f + 45f * p.swing, 40f * sin(t * 6f + 1f), 1.2f * u, 0.38f * u, skin)
            .let { drawCircle(skin, 0.25f * u, it) }
        arm(Offset(0.85f * u, -0.1f * u), -30f + 45f * p.swing, -40f * sin(t * 6f + 1f), 1.2f * u, 0.38f * u, skin)
            .let { drawCircle(skin, 0.25f * u, it) }
        // шея и голова
        drawRect(skin, Offset(-0.25f * u, -1.0f * u), Size(0.5f * u, 0.7f * u))
        withTransform({ rotate(p.tilt * 0.8f, Offset(0f, -0.8f * u)) }) {
            ovalAt(Offset(0f, -2.2f * u), 3.0f * u, 3.2f * u, skin)
            // чёлка и макушка
            val top = Path().apply {
                moveTo(-1.65f * u, -1.9f * u)
                cubicTo(-1.8f * u, -4.4f * u, 1.8f * u, -4.4f * u, 1.65f * u, -1.9f * u)
                quadraticBezierTo(1.0f * u, -3.0f * u, 0.2f * u, -2.6f * u)
                quadraticBezierTo(-0.6f * u, -3.1f * u, -1.65f * u, -1.9f * u)
                close()
            }
            drawPath(top, hair)
            // заколка
            drawCircle(Neon.Cyan, 0.25f * u, Offset(1.15f * u, -3.0f * u))
            cuteEye(Offset(-0.62f * u, -2.0f * u), 0.36f * u, Color(0xFF6B8E5A), t, p.swing)
            cuteEye(Offset(0.62f * u, -2.0f * u), 0.36f * u, Color(0xFF6B8E5A), t, p.swing)
            drawCircle(Color(0xFFFF8FB1).copy(alpha = 0.45f), 0.3f * u, Offset(-1.05f * u, -1.45f * u))
            drawCircle(Color(0xFFFF8FB1).copy(alpha = 0.45f), 0.3f * u, Offset(1.05f * u, -1.45f * u))
            drawArc(
                Color(0xFFC2185B), 10f, 160f, false,
                Offset(-0.4f * u, -1.55f * u), Size(0.8f * u, (0.4f + 0.3f * beat) * u),
                style = Stroke(0.12f * u, cap = StrokeCap.Round),
            )
        }
    }
}

// ---------------- Круговой эквалайзер ----------------
private fun DrawScope.drawBars(t: Float, beat: Float, level: Float) {
    val center = Offset(size.width / 2, size.height * 0.56f)
    val r = min(size.width, size.height) * 0.2f * (1f + 0.15f * beat)
    val n = 64
    for (i in 0 until n) {
        val a = (i * 2 * PI / n).toFloat() - (PI / 2).toFloat()
        val wave = 0.5f + 0.5f * sin(t * 9f + i * 0.55f) * cos(t * 4f + i * 0.21f)
        val h = r * (0.15f + 1.3f * level * wave + 0.5f * beat * (if (i % 4 == 0) 1f else 0.4f))
        val dir = Offset(cos(a), sin(a))
        drawLine(
            Brush.linearGradient(listOf(Neon.Cyan, Neon.Pink), center + dir * r, center + dir * (r + h)),
            center + dir * r, center + dir * (r + h), strokeWidth = r * 0.07f, cap = StrokeCap.Round,
        )
    }
    drawCircle(Brush.radialGradient(listOf(Color.White, Neon.Violet)), r * 0.85f, center)
    drawCircle(Color(0xFF0B0B1A), r * 0.25f, center)
}

// ---------------- Кот-бегун ----------------
/**
 * Вислоухий кот бежит по ночному городу. Скорость бега зависит от громкости,
 * дома-«эквалайзер» пульсируют под бас, а на сильную долю кот подпрыгивает.
 */
private fun DrawScope.drawRunner(t: Float, beat: Float, level: Float, jump: Float, run: Float) {
    val w = size.width
    val h = size.height
    val u = min(w, h) / 13f
    val groundY = h * 0.80f

    // луна / диско-шар
    val moon = Offset(w * 0.80f, h * 0.18f)
    drawCircle(
        Brush.radialGradient(listOf(Neon.Pink.copy(alpha = 0.35f + 0.35f * beat), Color.Transparent), center = moon, radius = u * 4f),
        radius = u * 4f, center = moon,
    )
    drawCircle(Color(0xFFFFF4D6), u * (1.3f + 0.15f * beat), moon)

    // звёзды
    for (i in 0 until 22) {
        val sx = ((i * 137.5f + 40f) % w)
        val sy = ((i * 61.3f) % (h * 0.5f))
        val tw = 0.35f + 0.65f * abs(sin(t * 2f + i))
        drawCircle(Color.White.copy(alpha = 0.25f * tw + 0.3f * beat), 1.2f + 1.6f * tw, Offset(sx, sy))
    }

    // дальние дома — двигаются медленно и пульсируют как эквалайзер
    val farW = u * 1.6f
    val farShift = (run * u * 1.2f) % farW
    var i = 0
    var x = -farShift
    while (x < w + farW) {
        val idx = i + (run * 1.2f / 1.6f).toInt()
        val base = 0.25f + 0.2f * abs(sin(idx * 1.7f))
        val pulse = 0.35f * level * (0.5f + 0.5f * sin(t * 5f + idx))
        val bh = h * (base + pulse + 0.08f * beat)
        drawRect(
            Brush.verticalGradient(listOf(Neon.Violet.copy(alpha = 0.55f), Neon.Violet.copy(alpha = 0.15f)), startY = groundY - bh, endY = groundY),
            topLeft = Offset(x, groundY - bh), size = Size(farW * 0.86f, bh),
        )
        // окна
        var wy = groundY - bh + u * 0.4f
        while (wy < groundY - u * 0.5f) {
            val lit = ((idx * 7 + (wy / u).toInt() * 3) % 5) < 2
            if (lit) drawRect(
                Neon.Cyan.copy(alpha = 0.35f + 0.5f * beat),
                topLeft = Offset(x + farW * 0.2f, wy), size = Size(farW * 0.15f, u * 0.25f),
            )
            wy += u * 0.7f
        }
        x += farW
        i++
    }

    // земля
    drawRect(
        Brush.verticalGradient(listOf(Neon.Pink.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.6f)), startY = groundY, endY = h),
        topLeft = Offset(0f, groundY), size = Size(w, h - groundY),
    )
    drawLine(Neon.Pink, Offset(0f, groundY), Offset(w, groundY), strokeWidth = u * 0.12f)
    // бегущая разметка
    val dash = u * 2.2f
    val dShift = (run * u * 6f) % dash
    var dx = -dShift
    while (dx < w) {
        drawLine(Color.White.copy(alpha = 0.55f), Offset(dx, groundY + u * 0.7f), Offset(dx + dash * 0.5f, groundY + u * 0.7f), strokeWidth = u * 0.12f, cap = StrokeCap.Round)
        dx += dash
    }

    // летящие ноты
    for (n in 0 until 6) {
        val speed = 3f + n
        val nx = w - ((run * u * speed + n * w / 6f) % (w + u * 2f))
        val ny = groundY - u * (3.5f + (n % 3) * 1.6f) + sin(t * 3f + n) * u * 0.4f
        val col = if (n % 2 == 0) Neon.Cyan else Neon.Pink
        drawNote(Offset(nx, ny), u * 0.45f, col.copy(alpha = 0.55f + 0.4f * beat))
    }

    // тень
    val catX = w * 0.38f
    val jumpPx = jump * u * 4.2f
    val shadowScale = (1f - jump * 0.6f).coerceIn(0.35f, 1f)
    drawOval(
        Color.Black.copy(alpha = 0.35f * shadowScale),
        topLeft = Offset(catX - u * 2.2f * shadowScale, groundY - u * 0.25f),
        size = Size(u * 4.4f * shadowScale, u * 0.5f),
    )

    drawRunningCat(Offset(catX, groundY - jumpPx), u, run * 13f, jump > 0.02f, t, beat)
}

private fun DrawScope.drawNote(c: Offset, r: Float, color: Color) {
    drawOval(color, topLeft = Offset(c.x - r, c.y - r * 0.7f), size = Size(r * 2f, r * 1.4f))
    drawLine(color, Offset(c.x + r * 0.85f, c.y), Offset(c.x + r * 0.85f, c.y - r * 3f), strokeWidth = r * 0.35f)
    drawLine(color, Offset(c.x + r * 0.85f, c.y - r * 3f), Offset(c.x + r * 2f, c.y - r * 2.2f), strokeWidth = r * 0.35f, cap = StrokeCap.Round)
}

/** Бегущий кот, вид сбоку (смотрит вправо). feet — нижняя точка лап. */
private fun DrawScope.drawRunningCat(feet: Offset, u: Float, phase: Float, inAir: Boolean, t: Float, beat: Float) {
    val fur = Color(0xFF9BA6B8)
    val furDark = Color(0xFF7D879A)
    val belly = Color(0xFFEDE8E2)
    val legLen = u * 1.25f
    val bodyY = feet.y - legLen - u * 0.55f
    val bob = if (inAir) 0f else abs(sin(phase)) * u * 0.18f
    val body = Offset(feet.x, bodyY - bob)

    // хвост — волнится
    val tail = Path().apply {
        moveTo(body.x - u * 1.7f, body.y - u * 0.1f)
        cubicTo(
            body.x - u * 2.6f, body.y - u * (0.8f + 0.4f * sin(phase * 0.5f)),
            body.x - u * 3.0f, body.y - u * (1.8f + 0.5f * sin(phase * 0.5f + 1f)),
            body.x - u * 2.5f, body.y - u * (2.4f + 0.3f * beat),
        )
    }
    drawPath(tail, furDark, style = Stroke(width = u * 0.45f, cap = StrokeCap.Round))

    // лапы: в беге — галоп, в прыжке — вытянуты
    val frontHip = body + Offset(u * 1.15f, u * 0.35f)
    val backHip = body + Offset(-u * 1.1f, u * 0.35f)
    val swing = sin(phase)
    val swing2 = sin(phase + 1.2f)
    if (inAir) {
        limb(backHip, 150f, legLen, u * 0.42f, furDark)
        limb(backHip + Offset(u * 0.25f, 0f), 140f, legLen, u * 0.42f, fur)
        limb(frontHip, 35f, legLen, u * 0.42f, furDark)
        limb(frontHip + Offset(-u * 0.25f, 0f), 25f, legLen, u * 0.42f, fur)
    } else {
        limb(backHip, 90f + 40f * swing2, legLen, u * 0.42f, furDark)
        limb(frontHip, 90f - 40f * swing, legLen, u * 0.42f, furDark)
        limb(backHip + Offset(u * 0.25f, 0f), 90f - 40f * swing2, legLen, u * 0.42f, fur)
        limb(frontHip + Offset(-u * 0.25f, 0f), 90f + 40f * swing, legLen, u * 0.42f, fur)
    }

    // тело
    withTransform({ rotate(if (inAir) -8f else 2f * swing, body) }) {
        ovalAt(body, u * 3.6f, u * 1.75f, fur)
        ovalAt(body + Offset(u * 0.2f, u * 0.45f), u * 2.4f, u * 0.8f, belly)
        // полоски
        for (k in 0 until 3) {
            val sx = body.x - u * 0.9f + k * u * 0.6f
            drawLine(furDark, Offset(sx, body.y - u * 0.85f), Offset(sx + u * 0.15f, body.y - u * 0.35f), strokeWidth = u * 0.18f, cap = StrokeCap.Round)
        }
    }

    // голова
    val head = body + Offset(u * 1.9f, -u * 0.75f - (if (inAir) u * 0.1f else 0f))
    drawCircle(fur, u * 1.05f, head)
    // вислые ушки (шотландская вислоухая)
    for (side in listOf(-1f, 1f)) {
        val ear = Path().apply {
            val e = head + Offset(side * u * 0.45f, -u * 0.75f)
            moveTo(e.x - u * 0.35f, e.y + u * 0.15f)
            quadraticBezierTo(e.x, e.y - u * 0.45f, e.x + u * 0.4f, e.y + u * 0.05f)
            quadraticBezierTo(e.x + u * 0.1f, e.y + u * 0.3f, e.x - u * 0.35f, e.y + u * 0.15f)
            close()
        }
        drawPath(ear, furDark)
    }
    // мордочка
    ovalAt(head + Offset(u * 0.55f, u * 0.35f), u * 0.95f, u * 0.65f, belly)
    drawCircle(Color(0xFFFF8FA3), u * 0.12f, head + Offset(u * 0.85f, u * 0.15f))
    cuteEye(head + Offset(u * 0.35f, -u * 0.15f), u * 0.27f, Color(0xFFE8A33D), t, lookX = 1f)
    // усы
    for (k in -1..1) {
        drawLine(
            Color.White.copy(alpha = 0.8f),
            head + Offset(u * 0.9f, u * 0.35f),
            head + Offset(u * 1.7f, u * (0.25f + 0.18f * k)),
            strokeWidth = u * 0.05f,
        )
    }
    // наушники — он же под музыку бежит
    val band = Path().apply {
        moveTo(head.x - u * 0.95f, head.y)
        quadraticBezierTo(head.x - u * 0.2f, head.y - u * 1.7f, head.x + u * 0.55f, head.y - u * 0.8f)
    }
    drawPath(band, Neon.Pink, style = Stroke(width = u * 0.16f, cap = StrokeCap.Round))
    drawCircle(Neon.Pink, u * (0.32f + 0.06f * beat), head + Offset(-u * 0.92f, u * 0.05f))
    drawCircle(Neon.Cyan.copy(alpha = 0.5f + 0.5f * beat), u * 0.14f, head + Offset(-u * 0.92f, u * 0.05f))
}
