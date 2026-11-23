@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.vibe.radio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.vibe.radio.audio.VoiceEffect
import com.vibe.radio.data.AppSettings
import kotlinx.coroutines.delay

@Composable
fun AppScreen(vm: PlayerViewModel) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snack.showSnackbar(it); vm.message = null }
    }
    // позиция трека для полосы прокрутки
    LaunchedEffect(Unit) {
        while (true) {
            vm.tick()
            delay(500)
        }
    }

    BackHandler(enabled = vm.playerOpen) { vm.playerOpen = false }
    BackHandler(enabled = !vm.playerOpen && vm.activeGame != null) { vm.activeGame = null }
    BackHandler(enabled = !vm.playerOpen && vm.activeGame == null && vm.openGroup != null && vm.mainTab == MainTab.MUSIC) {
        vm.openGroup = null
    }

    Box(Modifier.fillMaxSize().background(Neon.bgBrush)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) {
                when (vm.mainTab) {
                    MainTab.MUSIC -> MusicScreen(vm)
                    MainTab.RADIO -> RadioScreen(vm)
                    MainTab.GAMES -> GamesScreen(vm)
                    MainTab.SETTINGS -> SettingsScreen(vm)
                }
            }
            AnimatedVisibility(
                visible = vm.hasCurrent && !vm.playerOpen && vm.activeGame == null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                MiniPlayer(vm)
            }
            BottomBar(vm)
        }

        AnimatedVisibility(
            visible = vm.playerOpen,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            FullPlayer(vm)
        }

        SnackbarHost(snack, Modifier.align(Alignment.TopCenter).statusBarsPadding())
    }

    if (vm.queueOpen) QueueSheet(vm)
    if (vm.eqOpen) EqualizerSheet(vm)
}

@Composable
private fun BottomBar(vm: PlayerViewModel) {
    val items: List<Triple<MainTab, ImageVector, String>> = listOf(
        Triple(MainTab.MUSIC, Icons.Rounded.LibraryMusic, "Музыка"),
        Triple(MainTab.RADIO, Icons.Rounded.Radio, "Радио"),
        Triple(MainTab.GAMES, Icons.Rounded.SportsEsports, "Игры"),
        Triple(MainTab.SETTINGS, Icons.Rounded.Settings, "Настройки"),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(Neon.Bg.copy(alpha = 0.92f))
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        items.forEach { (tab, icon, label) ->
            val active = vm.mainTab == tab
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        vm.mainTab = tab
                        if (tab != MainTab.GAMES) vm.activeGame = null
                    }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (active) Neon.accent else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                ) {
                    Icon(icon, null, tint = if (active) Neon.OnAccent else Neon.Text2)
                }
                Text(
                    label, fontSize = 11.sp,
                    color = if (active) Neon.Text else Neon.Text2,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

// =====================================================================
// Мини-плеер
// =====================================================================
@Composable
private fun MiniPlayer(vm: PlayerViewModel) {
    val track = vm.currentTrack
    val station = vm.currentStation
    val progress = if (track != null && vm.durationMs > 0) (vm.positionMs.toFloat() / vm.durationMs).coerceIn(0f, 1f) else 0f
    Column(
        Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Neon.Violet.copy(alpha = 0.45f), Neon.Pink.copy(alpha = 0.30f))))
            .border(1.dp, Neon.Pink.copy(alpha = 0.4f), RoundedCornerShape(22.dp))
            .clickable { vm.playerOpen = true },
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (station != null) StationLogo(station, 46.dp) else TrackArtImage(track, 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    station?.name ?: track?.title ?: "—",
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Neon.Text,
                )
                val sub = when {
                    station != null -> vm.nowPlaying.ifBlank { "📻 Радио · ${vm.effect.emoji} ${vm.effect.title}" }
                    else -> track?.artistName.orEmpty()
                }
                Text(sub, color = Neon.Text2, fontSize = 12.sp, maxLines = 1, modifier = Modifier.basicMarquee())
            }
            IconButton(onClick = vm::next) { Icon(Icons.Rounded.SkipNext, null, tint = Neon.Text) }
            PlayButton(vm, 46.dp)
        }
        if (track != null) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(Neon.Line)) {
                Box(Modifier.fillMaxWidth(progress).height(3.dp).background(Neon.Cyan))
            }
        }
    }
}

// =====================================================================
// Полноэкранный плеер
// =====================================================================
@Composable
private fun FullPlayer(vm: PlayerViewModel) {
    val track = vm.currentTrack
    val station = vm.currentStation
    val isMusic = track != null
    var sleepMenu by remember { mutableStateOf(false) }

    val view = LocalView.current
    DisposableEffect(AppSettings.keepScreenOn) {
        view.keepScreenOn = AppSettings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize().background(Neon.bgBrush).statusBarsPadding().navigationBarsPadding()) {
        // верхняя панель
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.playerOpen = false }) {
                Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Neon.Text, modifier = Modifier.size(32.dp))
            }
            Text(
                if (isMusic) "Сейчас играет" else "Радио в эфире",
                Modifier.weight(1f), color = Neon.Text2, fontWeight = FontWeight.Medium,
            )
            IconButton(onClick = { vm.eqOpen = true }) {
                Icon(
                    Icons.Rounded.Equalizer, null,
                    tint = if (AppSettings.eqEnabled) Neon.Cyan else Neon.Text,
                )
            }
            if (isMusic) {
                TextButton(onClick = vm::cycleSpeed) {
                    Text("${trimSpeed(vm.speed)}×", color = if (vm.speed != 1f) Neon.Cyan else Neon.Text)
                }
                IconButton(onClick = { vm.queueOpen = true }) { Icon(Icons.Rounded.QueueMusic, null, tint = Neon.Text) }
            }
            Box {
                TextButton(onClick = { sleepMenu = true }) {
                    Icon(Icons.Rounded.Timer, null, tint = if (vm.sleepMinutesLeft > 0) Neon.Cyan else Neon.Text)
                    if (vm.sleepMinutesLeft > 0) Text(" ${vm.sleepMinutesLeft} мин", color = Neon.Cyan)
                }
                DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                    listOf(0 to "Выключить таймер", 15 to "15 минут", 30 to "30 минут", 60 to "1 час", 90 to "1,5 часа")
                        .forEach { (m, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { vm.setSleep(m); sleepMenu = false })
                        }
                }
            }
        }

        // сцена: 3D-персонажи (крутятся пальцем на 360°) или обложка
        val coverMode = isMusic && AppSettings.showCover
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            if (coverMode) {
                Box(
                    Modifier
                        .padding(28.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clickable { AppSettings.update { showCover = false } },
                ) {
                    TrackArtImage(track, 1000.dp, 28.dp)
                }
            } else {
                DancerStage(vm.dancer, vm.isPlaying, Modifier.fillMaxSize())
                Text(
                    "3D · крути пальцем",
                    color = Neon.Text2.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 14.dp, top = 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Neon.Bg.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            if (isMusic) {
                Text(
                    if (coverMode) "🎭 3D" else "🖼️ Обложка",
                    color = Neon.Text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 14.dp, top = 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Neon.Bg.copy(alpha = 0.65f))
                        .border(1.dp, Neon.Line, RoundedCornerShape(50))
                        .clickable { AppSettings.update { showCover = !showCover } }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        if (!coverMode) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(Dancer.entries.toList()) { d ->
                    val active = d == vm.dancer
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (active) Neon.accent else Neon.cardBrush)
                            .clickable { vm.selectDancer(d) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(d.emoji, fontSize = 22.sp)
                        Text(d.title, fontSize = 11.sp, color = if (active) Neon.OnAccent else Neon.Text)
                    }
                }
            }
        }

        // название
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    station?.name ?: track?.title ?: "—",
                    fontSize = 23.sp, fontWeight = FontWeight.Black, color = Neon.Text, maxLines = 1,
                    modifier = Modifier.basicMarquee(),
                )
                val sub = if (station != null) vm.nowPlaying.ifBlank { station.tags.ifBlank { "Прямой эфир" } }
                else listOfNotNull(
                    track?.artistName,
                    track?.album?.takeIf { it.isNotBlank() && it != "<unknown>" },
                    track?.formatBadge?.takeIf { it.isNotBlank() },
                    if (track?.onSdCard == true) "SD-карта" else null,
                ).joinToString(" · ")
                Text(sub, color = Neon.Text2, maxLines = 1, modifier = Modifier.basicMarquee())
            }
            val fav = if (station != null) vm.isFavorite(station) else track?.let { vm.isFavTrack(it) } == true
            IconButton(onClick = {
                if (station != null) vm.toggleFavorite(station) else track?.let { vm.toggleFavTrack(it) }
            }) {
                Icon(
                    if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null,
                    tint = Neon.Pink, modifier = Modifier.size(28.dp),
                )
            }
        }

        // прогресс / выбор битрейта
        if (isMusic) {
            SeekBar(vm)
        } else if (station != null) {
            BitrateSelector(vm)
        }

        // кнопки
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isMusic) {
                IconButton(onClick = vm::toggleShuffle) {
                    Icon(Icons.Rounded.Shuffle, null, tint = if (vm.shuffle) Neon.Cyan else Neon.Text2, modifier = Modifier.size(24.dp))
                }
            } else Spacer(Modifier.size(44.dp))
            IconButton(onClick = vm::prev) { Icon(Icons.Rounded.SkipPrevious, null, tint = Neon.Text, modifier = Modifier.size(36.dp)) }
            if (isMusic) {
                IconButton(onClick = { vm.seekRelative(-10_000L) }) {
                    Icon(Icons.Rounded.Replay10, null, tint = Neon.Text2, modifier = Modifier.size(28.dp))
                }
            }
            PlayButton(vm, 72.dp)
            if (isMusic) {
                IconButton(onClick = { vm.seekRelative(10_000L) }) {
                    Icon(Icons.Rounded.Forward10, null, tint = Neon.Text2, modifier = Modifier.size(28.dp))
                }
            }
            IconButton(onClick = vm::next) { Icon(Icons.Rounded.SkipNext, null, tint = Neon.Text, modifier = Modifier.size(36.dp)) }
            if (isMusic) {
                IconButton(onClick = vm::cycleRepeat) {
                    Icon(
                        if (vm.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, null,
                        tint = if (vm.repeatMode != Player.REPEAT_MODE_OFF) Neon.Cyan else Neon.Text2,
                        modifier = Modifier.size(24.dp),
                    )
                }
            } else Spacer(Modifier.size(44.dp))
        }

        Text("Эффекты звука", Modifier.padding(start = 24.dp, bottom = 6.dp), color = Neon.Text2, fontSize = 13.sp)
        LazyRow(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(VoiceEffect.entries.toList()) { e ->
                val active = e == vm.effect
                Column(
                    Modifier
                        .width(76.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (active) Neon.accent else Neon.cardBrush)
                        .clickable { vm.selectEffect(e) }
                        .padding(vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(e.emoji, fontSize = 22.sp)
                    Text(e.title, fontSize = 11.sp, color = if (active) Neon.OnAccent else Neon.Text, maxLines = 1)
                }
            }
        }
    }
}

private fun trimSpeed(s: Float): String =
    if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString().trimEnd('0')

@Composable
private fun SeekBar(vm: PlayerViewModel) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val dur = vm.durationMs.coerceAtLeast(1L)
    val value = dragging ?: (vm.positionMs.toFloat() / dur).coerceIn(0f, 1f)
    Column(Modifier.padding(horizontal = 20.dp)) {
        Slider(
            value = value,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { vm.seekTo((it * dur).toLong()) }
                dragging = null
            },
            colors = SliderDefaults.colors(
                thumbColor = Neon.Pink,
                activeTrackColor = Neon.Pink,
                inactiveTrackColor = Neon.Line,
            ),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime((value * dur).toLong()), color = Neon.Text2, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(if (vm.durationMs > 0) formatTime(vm.durationMs) else "--:--", color = Neon.Text2, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BitrateSelector(vm: PlayerViewModel) {
    val st = vm.currentStation ?: return
    val variants = st.allVariants
    Column(Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
        if (variants.size > 1) {
            Text("Качество потока", color = Neon.Text2, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(variants) { v ->
                    val label = (if (v.bitrate > 0) "${v.bitrate} kbps" else "Авто") +
                        (v.codec.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                    Chip(label, active = v.url == st.url) { vm.selectVariant(v) }
                }
            }
        } else {
            val info = listOfNotNull(
                st.bitrate.takeIf { it > 0 }?.let { "$it kbps" },
                st.codec.takeIf { it.isNotBlank() },
            ).joinToString(" · ").ifBlank { "Прямой эфир" }
            Text("Поток: $info · у станции один вариант качества", color = Neon.Text2, fontSize = 12.sp)
        }
    }
}

// =====================================================================
// Очередь
// =====================================================================
@Composable
private fun QueueSheet(vm: PlayerViewModel) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var version by remember { mutableStateOf(0) }
    val entries = remember(version, vm.currentTrack) { vm.queue() }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        val cur = entries.indexOfFirst { it.isCurrent }
        if (cur > 2) listState.scrollToItem(cur - 2)
    }
    ModalBottomSheet(
        onDismissRequest = { vm.queueOpen = false },
        sheetState = state,
        containerColor = Neon.Sheet,
    ) {
        Text(
            "Очередь · ${entries.size}",
            Modifier.padding(start = 20.dp, bottom = 8.dp),
            fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Neon.Text,
        )
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
            items(entries, key = { it.index }) { e ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (e.isCurrent) Neon.Pink.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { vm.playQueueIndex(e.index); version++ }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${e.index + 1}", color = Neon.Text2, fontSize = 12.sp, modifier = Modifier.width(32.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            e.title, color = if (e.isCurrent) Neon.Pink else Neon.Text,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (e.subtitle.isNotBlank()) Text(e.subtitle, color = Neon.Text2, fontSize = 12.sp, maxLines = 1)
                    }
                    if (e.isCurrent) MiniEq()
                    else IconButton(onClick = { vm.removeFromQueue(e.index); version++ }) {
                        Icon(Icons.Rounded.Close, null, tint = Neon.Text2)
                    }
                }
            }
        }
    }
}
