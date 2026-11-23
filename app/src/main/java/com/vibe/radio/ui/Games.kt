package com.vibe.radio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibe.radio.audio.AudioFx
import com.vibe.radio.data.AppSettings
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.random.Random

@Composable
fun GamesScreen(vm: PlayerViewModel) {
    when (vm.activeGame) {
        Game.SNAKE -> SnakeGame(onBack = { vm.activeGame = null })
        Game.NOTES -> NotesGame(isMusicPlaying = vm.isPlaying, onBack = { vm.activeGame = null })
        null -> Column(Modifier.fillMaxSize()) {
            ScreenTitle("Игры", "Пока играет музыка — можно поиграть")
            Spacer(Modifier.height(8.dp))
            Game.entries.forEach { g ->
                val best = if (g == Game.SNAKE) AppSettings.bestSnake else AppSettings.bestNotes
                Row(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Brush.linearGradient(listOf(Neon.Violet.copy(alpha = 0.35f), Neon.Pink.copy(alpha = 0.22f))))
                        .border(1.dp, Neon.Pink.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                        .clickable { vm.activeGame = g }
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(g.emoji, fontSize = 44.sp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(g.title, color = Neon.Text, fontWeight = FontWeight.Black, fontSize = 20.sp)
                        Text(g.about, color = Neon.Text2, fontSize = 13.sp)
                        Text("Рекорд: $best", color = Neon.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun GameHeader(title: String, score: Int, best: Int, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, null, tint = Neon.Text) }
        Text(title, color = Neon.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 12.dp)) {
            Text("Счёт: $score", color = Neon.Text, fontWeight = FontWeight.Bold)
            Text("Рекорд: $best", color = Neon.Text2, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Overlay(title: String, subtitle: String, button: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Neon.Bg.copy(alpha = 0.88f))
            .border(1.dp, Neon.Pink.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Neon.Text, fontWeight = FontWeight.Black, fontSize = 24.sp)
        Text(subtitle, color = Neon.Text2, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 10.dp))
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(Neon.accent).clickable(onClick = onClick)
                .padding(horizontal = 28.dp, vertical = 12.dp),
        ) { Text(button, color = Neon.OnAccent, fontWeight = FontWeight.Bold) }
    }
}

// =====================================================================
// Змейка
// =====================================================================
private data class Cell(val x: Int, val y: Int)

private class SnakeState(val cols: Int, val rows: Int) {
    var body by mutableStateOf(listOf<Cell>())
    var food by mutableStateOf(Cell(0, 0))
    var score by mutableIntStateOf(0)
    var over by mutableStateOf(false)
    var running by mutableStateOf(false)
    private var dir = Cell(1, 0)
    private var pending = Cell(1, 0)

    init { reset() }

    fun reset() {
        val y = rows / 2
        body = listOf(Cell(5, y), Cell(4, y), Cell(3, y))
        dir = Cell(1, 0)
        pending = dir
        score = 0
        over = false
        placeFood()
    }

    fun turn(d: Cell) {
        if (d.x == -dir.x && d.y == -dir.y) return
        pending = d
    }

    fun step() {
        dir = pending
        val h = body.first()
        // стены «прозрачные» — змейка выходит с другой стороны
        val n = Cell((h.x + dir.x + cols) % cols, (h.y + dir.y + rows) % rows)
        val grow = n == food
        val rest = if (grow) body else body.dropLast(1)
        if (rest.contains(n)) {
            over = true
            running = false
            return
        }
        body = listOf(n) + rest
        if (grow) {
            score++
            placeFood()
        }
    }

    private fun placeFood() {
        val occupied = body.toHashSet()
        val free = ArrayList<Cell>()
        for (x in 0 until cols) for (y in 0 until rows) {
            val c = Cell(x, y)
            if (c !in occupied) free += c
        }
        if (free.isNotEmpty()) food = free[Random.nextInt(free.size)]
    }
}

@Composable
private fun SnakeGame(onBack: () -> Unit) {
    val game = remember { SnakeState(16, 20) }

    LaunchedEffect(game.running) {
        while (game.running) {
            delay((190L - game.score * 4L).coerceAtLeast(70L))
            game.step()
        }
        if (game.over && game.score > AppSettings.bestSnake) {
            AppSettings.update { bestSnake = game.score }
        }
    }

    Column(Modifier.fillMaxSize()) {
        GameHeader("🐍 Змейка", game.score, AppSettings.bestSnake, onBack)
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            var drag by remember { mutableStateOf(Offset.Zero) }
            Canvas(
                Modifier
                    .aspectRatio(game.cols / game.rows.toFloat())
                    .clip(RoundedCornerShape(18.dp))
                    .background(Neon.Card)
                    .border(1.dp, Neon.Line, RoundedCornerShape(18.dp))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { drag = Offset.Zero },
                            onDrag = { change, amount ->
                                change.consume()
                                drag += amount
                                if (abs(drag.x) > 40f || abs(drag.y) > 40f) {
                                    if (abs(drag.x) > abs(drag.y)) game.turn(Cell(if (drag.x > 0) 1 else -1, 0))
                                    else game.turn(Cell(0, if (drag.y > 0) 1 else -1))
                                    drag = Offset.Zero
                                }
                            },
                        )
                    },
            ) { drawSnake(game) }

            if (!game.running) {
                if (game.over) Overlay("Игра окончена", "Счёт: ${game.score}", "Ещё раз") { game.reset(); game.running = true }
                else Overlay("Змейка", "Собирай ноты, не кусай себя.\nУправление — свайпы или кнопки.", "Играть") { game.running = true }
            }
        }
        // кнопки управления
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Pad(Icons.Rounded.KeyboardArrowUp) { game.turn(Cell(0, -1)) }
            Row(horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                Pad(Icons.Rounded.KeyboardArrowLeft) { game.turn(Cell(-1, 0)) }
                Pad(Icons.Rounded.KeyboardArrowRight) { game.turn(Cell(1, 0)) }
            }
            Pad(Icons.Rounded.KeyboardArrowDown) { game.turn(Cell(0, 1)) }
        }
    }
}

@Composable
private fun Pad(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(Neon.Card).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Neon.Text, modifier = Modifier.size(34.dp)) }
}

private fun DrawScope.drawSnake(game: SnakeState) {
    val cw = size.width / game.cols
    val ch = size.height / game.rows
    // сетка
    for (x in 0 until game.cols) for (y in 0 until game.rows) {
        if ((x + y) % 2 == 0) drawRect(Neon.Line, Offset(x * cw, y * ch), Size(cw, ch))
    }
    // еда — нота
    val f = game.food
    val fc = Offset(f.x * cw + cw / 2, f.y * ch + ch / 2)
    drawCircle(Neon.Cyan.copy(alpha = 0.35f), cw * 0.7f, fc)
    drawCircle(Neon.Cyan, cw * 0.28f, fc + Offset(-cw * 0.08f, ch * 0.12f))
    drawLine(Neon.Cyan, fc + Offset(cw * 0.18f, ch * 0.1f), fc + Offset(cw * 0.18f, -ch * 0.35f), strokeWidth = cw * 0.1f, cap = StrokeCap.Round)
    // змейка
    val n = game.body.size
    game.body.forEachIndexed { i, c ->
        val k = 1f - i / (n + 4f)
        val color = androidx.compose.ui.graphics.lerp(Neon.Violet, Neon.Pink, k)
        val pad = if (i == 0) cw * 0.04f else cw * 0.1f
        drawRoundRect(
            color,
            topLeft = Offset(c.x * cw + pad, c.y * ch + pad),
            size = Size(cw - pad * 2, ch - pad * 2),
            cornerRadius = CornerRadius(cw * 0.3f, cw * 0.3f),
        )
        if (i == 0) {
            drawCircle(Color.White, cw * 0.13f, Offset(c.x * cw + cw * 0.32f, c.y * ch + ch * 0.35f))
            drawCircle(Color.White, cw * 0.13f, Offset(c.x * cw + cw * 0.68f, c.y * ch + ch * 0.35f))
            drawCircle(Color.Black, cw * 0.06f, Offset(c.x * cw + cw * 0.32f, c.y * ch + ch * 0.37f))
            drawCircle(Color.Black, cw * 0.06f, Offset(c.x * cw + cw * 0.68f, c.y * ch + ch * 0.37f))
        }
    }
}

// =====================================================================
// Лови ноты
// =====================================================================
private class Falling(var x: Float, var y: Float, val speed: Float, val kind: Int) // 0 нота, 1 золотая, 2 бомба

private class NotesState {
    val items = ArrayList<Falling>()
    var basketX = 0.5f
    var score by mutableIntStateOf(0)
    var lives by mutableIntStateOf(3)
    var running by mutableStateOf(false)
    var over by mutableStateOf(false)
    var frame by mutableLongStateOf(0L)
    var flash = 0f
    private var spawn = 0f
    private var time = 0f

    fun reset() {
        items.clear()
        basketX = 0.5f
        score = 0
        lives = 3
        over = false
        spawn = 0f
        time = 0f
    }

    fun update(dt: Float, musicBoost: Float) {
        time += dt
        flash = (flash - dt * 2f).coerceAtLeast(0f)
        val difficulty = 1f + time / 45f
        spawn -= dt * difficulty * (1f + musicBoost * 0.6f)
        if (spawn <= 0f) {
            spawn = 0.75f
            val r = Random.nextFloat()
            val kind = when {
                r < 0.12f -> 2
                r < 0.22f -> 1
                else -> 0
            }
            items += Falling(0.08f + Random.nextFloat() * 0.84f, -0.05f, 0.22f + Random.nextFloat() * 0.12f, kind)
        }
        val speedK = difficulty * (1f + musicBoost)
        val it = items.iterator()
        while (it.hasNext()) {
            val f = it.next()
            f.y += f.speed * speedK * dt
            if (f.y > 0.86f && f.y < 0.95f && abs(f.x - basketX) < 0.11f) {
                when (f.kind) {
                    0 -> score += 1
                    1 -> score += 5
                    else -> { lives -= 1; flash = 1f }
                }
                it.remove()
            } else if (f.y > 1.05f) {
                if (f.kind == 0) { lives -= 1; flash = 1f }
                it.remove()
            }
        }
        if (lives <= 0) {
            over = true
            running = false
        }
        frame++
    }
}

@Composable
private fun NotesGame(isMusicPlaying: Boolean, onBack: () -> Unit) {
    val game = remember { NotesState() }
    var widthPx by remember { mutableStateOf(1f) }
    val playing by rememberUpdatedState(isMusicPlaying)

    LaunchedEffect(game.running) {
        var last = 0L
        while (game.running) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                last = now
                val boost = if (playing) (AudioFx.bass * 0.9f).coerceIn(0f, 0.9f) else 0f
                game.update(dt, boost)
            }
        }
        if (game.over && game.score > AppSettings.bestNotes) {
            AppSettings.update { bestNotes = game.score }
        }
    }

    Column(Modifier.fillMaxSize()) {
        GameHeader("🎵 Лови ноты", game.score, AppSettings.bestNotes, onBack)
        Text(
            "Жизни: " + "❤️".repeat(game.lives.coerceAtLeast(0)) + if (isMusicPlaying) "   · 🔊 музыка ускоряет ноты" else "",
            color = Neon.Text2, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, bottom = 6.dp),
        )
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Neon.Card)
                    .border(1.dp, Neon.Line, RoundedCornerShape(22.dp))
                    .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { off -> game.basketX = (off.x / widthPx).coerceIn(0.08f, 0.92f) },
                            onDrag = { change, _ ->
                                change.consume()
                                game.basketX = (change.position.x / widthPx).coerceIn(0.08f, 0.92f)
                            },
                        )
                    },
            ) {
                @Suppress("UNUSED_VARIABLE")
                val tick = game.frame // перерисовка каждый кадр
                drawNotesGame(game)
            }

            if (!game.running) {
                if (game.over) Overlay("Игра окончена", "Поймано очков: ${game.score}", "Ещё раз") { game.reset(); game.running = true }
                else Overlay(
                    "Лови ноты",
                    "Води пальцем, чтобы двигать корзину.\n🎵 +1   ⭐ +5   💣 — минус жизнь.\nПропущенная нота тоже отнимает жизнь.",
                    "Играть",
                ) { game.reset(); game.running = true }
            }
        }
    }
}

private fun DrawScope.drawNotesGame(game: NotesState) {
    val w = size.width
    val h = size.height
    if (game.flash > 0f) drawRect(Color.Red.copy(alpha = 0.18f * game.flash))
    // нотный стан на фоне
    for (i in 0 until 5) {
        val y = h * (0.15f + i * 0.06f)
        drawLine(Neon.Line, Offset(0f, y), Offset(w, y), strokeWidth = 2f)
    }
    val r = w * 0.035f
    for (f in game.items) {
        val c = Offset(f.x * w, f.y * h)
        when (f.kind) {
            2 -> {
                drawCircle(Color(0xFF222222), r * 1.2f, c)
                drawCircle(Color(0xFFFF3B3B), r * 0.45f, c + Offset(-r * 0.3f, -r * 0.3f))
                drawLine(Color(0xFFFFB13B), c + Offset(r * 0.6f, -r * 0.9f), c + Offset(r * 1.1f, -r * 1.6f), strokeWidth = r * 0.25f, cap = StrokeCap.Round)
            }
            else -> {
                val col = if (f.kind == 1) Color(0xFFFFD84F) else Neon.Cyan
                if (f.kind == 1) drawCircle(col.copy(alpha = 0.3f), r * 2.2f, c)
                drawOval(col, topLeft = Offset(c.x - r, c.y - r * 0.7f), size = Size(r * 2f, r * 1.4f))
                drawLine(col, Offset(c.x + r * 0.85f, c.y), Offset(c.x + r * 0.85f, c.y - r * 3f), strokeWidth = r * 0.35f)
                drawLine(col, Offset(c.x + r * 0.85f, c.y - r * 3f), Offset(c.x + r * 2f, c.y - r * 2.2f), strokeWidth = r * 0.35f, cap = StrokeCap.Round)
            }
        }
    }
    // корзина
    val bw = w * 0.22f
    val bh = h * 0.05f
    val bx = game.basketX * w - bw / 2
    val by = h * 0.90f
    drawRoundRect(
        Brush.linearGradient(listOf(Neon.Violet, Neon.Pink), Offset(bx, by), Offset(bx + bw, by)),
        topLeft = Offset(bx, by), size = Size(bw, bh), cornerRadius = CornerRadius(bh / 2, bh / 2),
    )
    drawRoundRect(
        Color.White.copy(alpha = 0.3f),
        topLeft = Offset(bx + bw * 0.1f, by + bh * 0.15f), size = Size(bw * 0.8f, bh * 0.25f),
        cornerRadius = CornerRadius(bh / 4, bh / 4),
    )
}
