package com.vibe.radio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibe.radio.data.Track

/** Лаунчеры: разрешение на музыку, выбор папки (в т.ч. на SD-карте), выбор файлов. */
class LibraryActions(
    val scan: () -> Unit,
    val addFolder: () -> Unit,
    val addFiles: () -> Unit,
)

@Composable
fun rememberLibraryActions(vm: PlayerViewModel): LibraryActions {
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onPermissionResult(granted)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addFolder(uri)
    }
    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.addFiles(uris)
    }
    return remember(vm) {
        LibraryActions(
            scan = { if (vm.hasAudioPermission()) vm.rescan() else permLauncher.launch(vm.audioPermission()) },
            addFolder = { folderLauncher.launch(null) },
            addFiles = { filesLauncher.launch(arrayOf("audio/*")) },
        )
    }
}

@Composable
fun MusicScreen(vm: PlayerViewModel) {
    val actions = rememberLibraryActions(vm)
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onPermissionResult(granted)
    }
    // при первом открытии спрашиваем доступ к музыке
    LaunchedEffect(Unit) {
        if (!vm.hasAudioPermission() && !vm.askedPermission) {
            vm.askedPermission = true
            permLauncher.launch(vm.audioPermission())
        }
    }

    val group = vm.openGroup
    if (group != null) {
        GroupDetail(vm, group)
        return
    }

    var addMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("PLAY pay", "Моя музыка · ${countLabel(vm.tracks.size)}") {
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Rounded.Sort, null, tint = Neon.Text) }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    TrackSort.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text((if (s == vm.sort) "✓  " else "     ") + s.title) },
                            onClick = { vm.selectSort(s); sortMenu = false },
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { addMenu = true }) { Icon(Icons.Rounded.Add, null, tint = Neon.Text) }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Сканировать устройство") },
                        leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                        onClick = { addMenu = false; actions.scan() },
                    )
                    DropdownMenuItem(
                        text = { Text("Добавить папку (телефон / SD-карта)") },
                        leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                        onClick = { addMenu = false; actions.addFolder() },
                    )
                    DropdownMenuItem(
                        text = { Text("Добавить файлы") },
                        leadingIcon = { Icon(Icons.Rounded.AudioFile, null) },
                        onClick = { addMenu = false; actions.addFiles() },
                    )
                }
            }
        }

        SearchField(vm.musicQuery, "Трек, исполнитель, альбом…") { vm.musicQuery = it }

        if (vm.musicQuery.isBlank()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 6.dp),
            ) {
                items(MusicTab.entries.toList()) { t ->
                    Chip(t.title, active = t == vm.musicTab) { vm.musicTab = t }
                }
            }
        }

        if (vm.scanning) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)), color = Neon.Pink, trackColor = Neon.Line)
                Text(vm.scanStatus, color = Neon.Text2, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                vm.musicQuery.isNotBlank() -> {
                    if (vm.searchResults.isEmpty()) EmptyHint("🔍", "Ничего не найдено", Modifier.align(Alignment.Center))
                    else TrackList(vm, vm.searchResults, header = false)
                }
                vm.tracks.isEmpty() && !vm.scanning && vm.libraryLoaded -> EmptyLibrary(actions)
                vm.tracks.isEmpty() -> Unit
                else -> when (vm.musicTab) {
                    MusicTab.TRACKS -> TrackList(vm, vm.sortedTracks, header = true)
                    MusicTab.FAVS ->
                        if (vm.favTracks.isEmpty()) EmptyHint("❤️", "Нажмите ♥ в плеере или ⋮ у трека,\nчтобы добавить в избранное", Modifier.align(Alignment.Center))
                        else TrackList(vm, vm.favTracks, header = true)
                    MusicTab.ALBUMS -> GroupList(vm, vm.albums, Icons.Rounded.Album)
                    MusicTab.ARTISTS -> GroupList(vm, vm.artists, Icons.Rounded.Person)
                    MusicTab.FOLDERS -> GroupList(vm, vm.folderGroups, Icons.Rounded.Folder)
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(actions: LibraryActions) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🎶", fontSize = 56.sp)
        Spacer(Modifier.height(8.dp))
        Text("Медиатека пуста", color = Neon.Text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(
            "Найдите музыку на телефоне и карте памяти или добавьте папку вручную",
            color = Neon.Text2, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        BigAction("🔎", "Сканировать устройство", "Память телефона и SD-карта", actions.scan)
        BigAction("📁", "Добавить папку", "Выберите папку, в том числе на SD-карте", actions.addFolder)
        BigAction("🎵", "Добавить файлы", "Отдельные аудиофайлы", actions.addFiles)
    }
}

@Composable
private fun BigAction(emoji: String, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Neon.Card)
            .border(1.dp, Neon.Line, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 26.sp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Neon.Text, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Neon.Text2, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TrackList(vm: PlayerViewModel, list: List<Track>, header: Boolean) {
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (header) {
            item {
                PlayAllRow(
                    count = list.size,
                    onPlay = { vm.playTracks(list, null, shuffleOn = false) },
                    onShuffle = { vm.playTracks(list, null, shuffleOn = true) },
                )
            }
        }
        items(list, key = { it.id }) { t ->
            TrackRow(vm, t) { vm.playTracks(list, t) }
        }
    }
}

@Composable
private fun PlayAllRow(count: Int, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Neon.accent).clickable(onClick = onShuffle)
                .padding(vertical = 11.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Shuffle, null, tint = Neon.OnAccent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("Перемешать · $count", color = Neon.OnAccent, fontWeight = FontWeight.Bold)
        }
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Neon.Card).clickable(onClick = onPlay)
                .padding(vertical = 11.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.PlayArrow, null, tint = Neon.Text, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("Слушать по порядку", color = Neon.Text, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun TrackRow(vm: PlayerViewModel, t: Track, onClick: () -> Unit) {
    val isCurrent = vm.currentTrack?.id == t.id
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isCurrent) Neon.Pink.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackArtImage(t, 50.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title, color = if (isCurrent) Neon.Pink else Neon.Text,
                fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val dur = if (t.durationMs > 0) " · " + formatTime(t.durationMs) else ""
            Text(t.artistName + dur, color = Neon.Text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isCurrent && vm.isPlaying) MiniEq(Modifier.padding(horizontal = 6.dp))
        if (vm.isFavTrack(t)) Text("♥", color = Neon.Pink, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp))
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, null, tint = Neon.Text2) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (vm.isFavTrack(t)) "Убрать из избранного" else "В избранное ♥") },
                    onClick = { vm.toggleFavTrack(t); menu = false },
                )
                DropdownMenuItem(text = { Text("Играть следующим") }, onClick = { vm.playNext(t); menu = false })
                DropdownMenuItem(text = { Text("Добавить в очередь") }, onClick = { vm.addToQueue(t); menu = false })
                DropdownMenuItem(text = { Text("Убрать из медиатеки") }, onClick = { vm.removeFromLibrary(t); menu = false })
                DropdownMenuItem(
                    text = { Text("📁 " + t.folder, fontSize = 12.sp, color = Neon.Text2) },
                    enabled = false,
                    onClick = { },
                )
            }
        }
    }
}

@Composable
private fun GroupList(vm: PlayerViewModel, groups: List<TrackGroup>, icon: ImageVector) {
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(groups, key = { it.type.name + it.key }) { g ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Neon.Card)
                    .clickable { vm.openGroup = g }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    TrackArtImage(g.tracks.firstOrNull(), 54.dp, 14.dp)
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(20.dp).clip(RoundedCornerShape(6.dp)).background(Neon.Bg.copy(alpha = 0.8f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, null, tint = Neon.Cyan, modifier = Modifier.size(14.dp)) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.title, color = Neon.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(g.subtitle, color = Neon.Text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { vm.playTracks(g.tracks, null) }) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Neon.Pink)
                }
            }
        }
    }
}

@Composable
private fun GroupDetail(vm: PlayerViewModel, g: TrackGroup) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.openGroup = null }) { Icon(Icons.Rounded.ArrowBack, null, tint = Neon.Text) }
            Text(
                when (g.type) {
                    GroupType.ALBUM -> "Альбом"
                    GroupType.ARTIST -> "Исполнитель"
                    GroupType.FOLDER -> "Папка"
                },
                color = Neon.Text2,
            )
        }
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            TrackArtImage(g.tracks.firstOrNull(), 96.dp, 20.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(g.title, color = Neon.Text, fontWeight = FontWeight.Black, fontSize = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(g.subtitle, color = Neon.Text2, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f)) { TrackList(vm, g.tracks, header = true) }
    }
}
