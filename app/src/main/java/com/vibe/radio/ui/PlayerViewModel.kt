package com.vibe.radio.ui

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.vibe.radio.PlaybackService
import com.vibe.radio.audio.AudioFx
import com.vibe.radio.audio.VoiceEffect
import com.vibe.radio.data.AppSettings
import com.vibe.radio.data.LibraryRepository
import com.vibe.radio.data.RadioRepository
import com.vibe.radio.data.StartSection
import com.vibe.radio.data.Station
import com.vibe.radio.data.StreamVariant
import com.vibe.radio.data.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

enum class MainTab(val title: String) { MUSIC("Музыка"), RADIO("Радио"), GAMES("Игры"), SETTINGS("Настройки") }

enum class MusicTab(val title: String) {
    TRACKS("Треки"), ALBUMS("Альбомы"), ARTISTS("Исполнители"), FOLDERS("Папки"), FAVS("Избранное")
}

enum class TrackSort(val title: String) {
    TITLE("По названию"), ARTIST("По исполнителю"), ADDED("Сначала новые"), DURATION("По длительности")
}

enum class FormatFilter(val title: String) {
    ALL("Все"), MP3("MP3"), WAV("WAV"), SD("SD-карта")
}

enum class GroupType { ALBUM, ARTIST, FOLDER }

data class TrackGroup(val type: GroupType, val key: String, val title: String, val subtitle: String, val tracks: List<Track>)

data class QueueEntry(val index: Int, val title: String, val subtitle: String, val isCurrent: Boolean)

enum class Game(val title: String, val emoji: String, val about: String) {
    SNAKE("Змейка", "🐍", "Собирай ноты и расти. Свайпы или кнопки."),
    NOTES("Лови ноты", "🎵", "Лови ноты, избегай бомб. Под музыку ноты падают быстрее!"),
}

const val FAV_TAB = "FAV"

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication<Application>()
    private val radio = RadioRepository(app)
    private val lib = LibraryRepository(app)
    private val prefs = app.getSharedPreferences("vibe", Context.MODE_PRIVATE)
    private var controller: MediaController? = null

    // ---------- Навигация ----------
    var mainTab by mutableStateOf(
        if (AppSettings.startSection == StartSection.RADIO) MainTab.RADIO else MainTab.MUSIC
    )
    var playerOpen by mutableStateOf(false)
    var queueOpen by mutableStateOf(false)
    var eqOpen by mutableStateOf(false)
    var activeGame by mutableStateOf<Game?>(null)
    var message by mutableStateOf<String?>(null)

    // ---------- Медиатека ----------
    var tracks by mutableStateOf<List<Track>>(emptyList()); private set
    var libraryLoaded by mutableStateOf(false); private set
    var scanning by mutableStateOf(false); private set
    var scanStatus by mutableStateOf(""); private set
    var musicTab by mutableStateOf(MusicTab.TRACKS)
    var formatFilter by mutableStateOf(FormatFilter.ALL)
    var musicQuery by mutableStateOf("")
    var sort by mutableStateOf(TrackSort.TITLE); private set
    var openGroup by mutableStateOf<TrackGroup?>(null)
    var folders by mutableStateOf<List<String>>(emptyList()); private set
    var askedPermission = false
    val favTrackIds = mutableStateListOf<String>()

    val sortedTracks: List<Track> by derivedStateOf {
        when (sort) {
            TrackSort.TITLE -> tracks.sortedBy { it.title.lowercase() }
            TrackSort.ARTIST -> tracks.sortedWith(compareBy({ it.artistName.lowercase() }, { it.album.lowercase() }, { it.trackNo }))
            TrackSort.ADDED -> tracks.sortedByDescending { it.dateAdded }
            TrackSort.DURATION -> tracks.sortedByDescending { it.durationMs }
        }
    }
    val filteredTracks: List<Track> by derivedStateOf {
        when (formatFilter) {
            FormatFilter.ALL -> sortedTracks
            FormatFilter.MP3 -> sortedTracks.filter { it.formatBadge.equals("MP3", ignoreCase = true) }
            FormatFilter.WAV -> sortedTracks.filter { it.formatBadge.equals("WAV", ignoreCase = true) }
            FormatFilter.SD -> sortedTracks.filter { it.onSdCard }
        }
    }
    val mp3Count: Int by derivedStateOf { tracks.count { it.formatBadge.equals("MP3", ignoreCase = true) } }
    val wavCount: Int by derivedStateOf { tracks.count { it.formatBadge.equals("WAV", ignoreCase = true) } }
    val sdCount: Int by derivedStateOf { tracks.count { it.onSdCard } }
    private val trackById: Map<String, Track> by derivedStateOf { tracks.associateBy { it.id } }
    val favTracks: List<Track> by derivedStateOf {
        val set = favTrackIds.toSet()
        filteredTracks.filter { it.id in set }
    }
    val searchResults: List<Track> by derivedStateOf {
        val q = musicQuery.trim().lowercase()
        if (q.isEmpty()) emptyList() else filteredTracks.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q) || it.folder.lowercase().contains(q) ||
                it.formatBadge.lowercase().contains(q)
        }
    }
    val albums: List<TrackGroup> by derivedStateOf { buildGroups(GroupType.ALBUM) }
    val artists: List<TrackGroup> by derivedStateOf { buildGroups(GroupType.ARTIST) }
    val folderGroups: List<TrackGroup> by derivedStateOf { buildGroups(GroupType.FOLDER) }

    // ---------- Радио ----------
    var radioTab by mutableStateOf(prefs.getString("tab", "RU") ?: "RU"); private set
    var stations by mutableStateOf<List<Station>>(emptyList()); private set
    var radioLoading by mutableStateOf(false); private set
    var radioQuery by mutableStateOf("")
    val favorites = mutableStateListOf<Station>()
    private val knownStations = HashMap<String, Station>()

    // ---------- Воспроизведение ----------
    var currentTrack by mutableStateOf<Track?>(null); private set
    var currentStation by mutableStateOf<Station?>(null); private set
    var isPlaying by mutableStateOf(false); private set
    var isBuffering by mutableStateOf(false); private set
    var nowPlaying by mutableStateOf(""); private set
    var positionMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    var shuffle by mutableStateOf(false); private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF); private set
    var speed by mutableFloatStateOf(1f); private set
    private var externalTrack: Track? = null

    val hasCurrent: Boolean get() = currentTrack != null || currentStation != null

    var effect by mutableStateOf(VoiceEffect.NONE); private set
    var dancer by mutableStateOf(Dancer.CAT); private set
    var sleepMinutesLeft by mutableIntStateOf(0); private set

    private var sleepJob: Job? = null
    private var searchJob: Job? = null
    private var restored = false
    private var lastItemId: String? = null
    private var pendingExternal: Uri? = null

    init {
        favorites.addAll(radio.favorites())
        favorites.forEach { knownStations[it.id] = it }
        favTrackIds.addAll((prefs.getString("favTracks", "") ?: "").split("\n").filter { it.isNotBlank() })
        effect = runCatching { VoiceEffect.valueOf(prefs.getString("effect", "NONE") ?: "NONE") }.getOrDefault(VoiceEffect.NONE)
        AudioFx.effect = effect
        dancer = runCatching { Dancer.valueOf(prefs.getString("dancer", "CAT") ?: "CAT") }.getOrDefault(Dancer.CAT)
        sort = runCatching { TrackSort.valueOf(prefs.getString("sort", "TITLE") ?: "TITLE") }.getOrDefault(TrackSort.TITLE)
        folders = lib.folders()
        if (radioTab != FAV_TAB && radioTab !in AppSettings.regions) radioTab = AppSettings.regions.firstOrNull() ?: FAV_TAB

        connect()
        loadRadioTab(radioTab)

        viewModelScope.launch {
            tracks = lib.load()
            libraryLoaded = true
            maybeRestore()
            if (AppSettings.scanOnStart && (hasAudioPermission() || folders.isNotEmpty() || lib.files().isNotEmpty())) {
                rescan(silent = true)
            }
        }
    }

    // =====================================================================
    // Подключение к сервису
    // =====================================================================
    private fun connect() {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        viewModelScope.launch {
            try {
                val c = MediaController.Builder(ctx, token).buildAsync().await()
                controller = c
                c.addListener(listener)
                syncFromPlayer(c)
                applyPlaybackParams()
                applyRadioBitrateCap()
                maybeRestore()
                pendingExternal?.let { pendingExternal = null; playExternal(it) }
            } catch (e: Exception) {
                message = "Не удалось запустить плеер: ${e.message}"
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncFromPlayer(player)

        override fun onPlayerError(error: PlaybackException) {
            val c = controller ?: return
            if (currentStation != null) {
                if (!AppSettings.autoNextStation) {
                    message = "Станция «${currentStation?.name ?: ""}» недоступна"
                    return
                }
                message = "Станция «${currentStation?.name ?: ""}» недоступна — переключаю"
            } else {
                message = "Не удалось воспроизвести «${currentTrack?.title ?: "файл"}» — следующий"
            }
            if (c.hasNextMediaItem()) {
                c.seekToNextMediaItem(); c.prepare(); c.play()
            }
        }
    }

    private fun syncFromPlayer(p: Player) {
        isPlaying = p.isPlaying
        isBuffering = p.playbackState == Player.STATE_BUFFERING
        shuffle = p.shuffleModeEnabled
        repeatMode = p.repeatMode
        durationMs = p.duration.takeIf { it > 0 } ?: 0L
        positionMs = p.currentPosition.coerceAtLeast(0L)

        val item = p.currentMediaItem
        val id = item?.mediaId.orEmpty()
        when {
            id.startsWith("t:") -> {
                currentStation = null
                currentTrack = trackById[id.removePrefix("t:")] ?: item?.let { trackFromItem(it) }
            }
            id.startsWith("x:") -> {
                currentStation = null
                currentTrack = externalTrack ?: item?.let { trackFromItem(it) }
            }
            id.startsWith("r:") -> {
                currentTrack = null
                currentStation = knownStations[id.removePrefix("r:")] ?: item?.let { stationFromItem(it) }
            }
            else -> {
                currentTrack = null
                currentStation = null
            }
        }
        val title = p.mediaMetadata.title?.toString().orEmpty()
        nowPlaying = if (currentStation != null && title.isNotBlank() && title != currentStation?.name) title else ""

        if (id != lastItemId) {
            lastItemId = id
            applyPlaybackParams()
            saveSession()
        }
        if (!p.isPlaying) saveSession()
    }

    private fun trackFromItem(item: MediaItem) = Track(
        id = item.mediaId.removePrefix("t:").removePrefix("x:"),
        uri = item.requestMetadata.mediaUri?.toString() ?: "",
        title = item.mediaMetadata.title?.toString() ?: "Трек",
        artist = item.mediaMetadata.artist?.toString() ?: "",
        album = item.mediaMetadata.albumTitle?.toString() ?: "",
        durationMs = 0, folder = "", fromMediaStore = false, albumId = 0, dateAdded = 0, trackNo = 0, key = "",
    )

    private fun stationFromItem(item: MediaItem) = Station(
        id = item.mediaId.removePrefix("r:"),
        name = item.mediaMetadata.artist?.toString() ?: "Радио",
        url = item.requestMetadata.mediaUri?.toString() ?: "",
        favicon = item.mediaMetadata.artworkUri?.toString() ?: "",
    ).also { knownStations[it.id] = it }

    /** Позиция для полосы прокрутки — вызывается из UI раз в полсекунды. */
    fun tick() {
        val c = controller ?: return
        positionMs = c.currentPosition.coerceAtLeast(0L)
        durationMs = c.duration.takeIf { it > 0 } ?: 0L
    }

    // =====================================================================
    // Медиатека
    // =====================================================================
    fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, audioPermission()) == PackageManager.PERMISSION_GRANTED

    fun onPermissionResult(granted: Boolean) {
        if (granted) rescan()
        else message = "Без разрешения можно добавить папки и файлы вручную через «+»"
    }

    fun rescan(silent: Boolean = false) {
        if (scanning) return
        viewModelScope.launch {
            scanning = true
            scanStatus = "Ищу музыку…"
            val res = runCatching {
                lib.scan(hasAudioPermission()) { s -> viewModelScope.launch { scanStatus = s } }
            }
            res.onSuccess { list ->
                tracks = list
                if (!silent) {
                    val mp3 = list.count { it.formatBadge.equals("MP3", ignoreCase = true) }
                    val wav = list.count { it.formatBadge.equals("WAV", ignoreCase = true) }
                    val sd = list.count { it.onSdCard }
                    message = "Найдено: ${list.size} (MP3: $mp3 · WAV: $wav · SD: $sd)"
                }
            }.onFailure { e ->
                message = "Ошибка сканирования: ${e.message}"
            }
            scanning = false
            scanStatus = ""
            maybeRestore()
        }
    }

    fun addFolder(uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        lib.addFolder(uri.toString())
        folders = lib.folders()
        message = "Папка добавлена — сканирую"
        rescan()
    }

    fun removeFolder(uri: String) {
        lib.removeFolder(uri)
        try {
            ctx.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        folders = lib.folders()
        rescan(silent = true)
    }

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        uris.forEach { u ->
            try {
                ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        lib.addFiles(uris.map { it.toString() })
        message = "Добавлено файлов: ${uris.size}"
        rescan(silent = true)
    }

    fun removeFromLibrary(t: Track) {
        viewModelScope.launch {
            tracks = lib.removeAndSave(t.id, tracks)
            favTrackIds.remove(t.id)
            saveFavTracks()
            openGroup = openGroup?.let { g -> g.copy(tracks = g.tracks.filter { it.id != t.id }) }
            message = "«${t.title}» убран из медиатеки"
        }
    }

    fun resetHidden() {
        lib.clearHidden()
        message = "Скрытые треки возвращены"
        rescan(silent = true)
    }

    fun selectSort(s: TrackSort) {
        sort = s
        prefs.edit().putString("sort", s.name).apply()
    }

    fun isFavTrack(t: Track) = favTrackIds.contains(t.id)

    fun toggleFavTrack(t: Track) {
        if (!favTrackIds.remove(t.id)) favTrackIds.add(0, t.id)
        saveFavTracks()
    }

    private fun saveFavTracks() = prefs.edit().putString("favTracks", favTrackIds.joinToString("\n")).apply()

    private fun buildGroups(type: GroupType): List<TrackGroup> {
        val grouped = filteredTracks.groupBy {
            when (type) {
                GroupType.ALBUM -> it.albumName.lowercase()
                GroupType.ARTIST -> it.artistName.lowercase()
                GroupType.FOLDER -> it.folder
            }
        }
        return grouped.map { (key, list) ->
            val first = list.first()
            val ordered = if (type == GroupType.ALBUM) list.sortedWith(compareBy({ it.trackNo }, { it.title.lowercase() })) else list
            when (type) {
                GroupType.ALBUM -> TrackGroup(type, key, first.albumName, "${first.artistName} · ${countLabel(list.size)}", ordered)
                GroupType.ARTIST -> {
                    val albumsCount = list.map { it.albumName }.distinct().size
                    TrackGroup(type, key, first.artistName, "альбомов: $albumsCount · ${countLabel(list.size)}", ordered)
                }
                GroupType.FOLDER -> TrackGroup(type, key, first.folder.substringAfterLast('/').ifBlank { first.folder }, "${first.folder} · ${countLabel(list.size)}", ordered)
            }
        }.sortedBy { it.title.lowercase() }
    }

    // =====================================================================
    // Воспроизведение музыки
    // =====================================================================
    fun playTracks(list: List<Track>, start: Track?, shuffleOn: Boolean = false) {
        val c = controller ?: run { message = "Плеер ещё запускается…"; return }
        if (list.isEmpty()) return
        if (start != null && currentTrack?.id == start.id && !shuffleOn) {
            togglePlay(); return
        }
        val index = if (start == null) {
            if (shuffleOn) list.indices.random() else 0
        } else list.indexOfFirst { it.id == start.id }.coerceAtLeast(0)
        c.setMediaItems(list.map { it.toMediaItem() }, index, 0L)
        c.shuffleModeEnabled = shuffleOn
        c.prepare()
        c.play()
        currentStation = null
        currentTrack = list[index]
        nowPlaying = ""
    }

    fun playNext(t: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0 || currentStation != null) { playTracks(listOf(t), t); return }
        c.addMediaItem(c.currentMediaItemIndex + 1, t.toMediaItem())
        message = "«${t.title}» сыграет следующим"
    }

    fun addToQueue(t: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0 || currentStation != null) { playTracks(listOf(t), t); return }
        c.addMediaItem(t.toMediaItem())
        message = "«${t.title}» добавлен в очередь"
    }

    /** Файл, открытый из другого приложения («Открыть с помощью»). */
    fun playExternal(uri: Uri) {
        val c = controller
        if (c == null) { pendingExternal = uri; return }
        viewModelScope.launch {
            val t = lib.describe(uri)
            externalTrack = t
            val meta = MediaMetadata.Builder().setTitle(t.title).setArtist(t.artistName).setAlbumTitle(t.album).build()
            val item = MediaItem.Builder()
                .setMediaId("x:" + uri)
                .setUri(uri)
                .setMediaMetadata(meta)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .build()
            c.setMediaItem(item)
            c.prepare()
            c.play()
            currentStation = null
            currentTrack = t
            playerOpen = true
        }
    }

    fun seekTo(ms: Long) {
        val target = ms.coerceIn(0L, durationMs.coerceAtLeast(ms.coerceAtLeast(0L)))
        controller?.seekTo(target)
        positionMs = target
    }

    fun seekRelative(deltaMs: Long) {
        val c = controller ?: return
        val maxDur = c.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = (c.currentPosition + deltaMs).coerceIn(0L, maxDur)
        c.seekTo(target)
        positionMs = target
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun cycleSpeed() {
        val steps = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        val i = steps.indexOfFirst { it >= speed - 0.01f }
        speed = steps[(i + 1) % steps.size]
        applyPlaybackParams()
    }

    fun queue(): List<QueueEntry> {
        val c = controller ?: return emptyList()
        val cur = c.currentMediaItemIndex
        return (0 until c.mediaItemCount).map { i ->
            val m = c.getMediaItemAt(i).mediaMetadata
            val title = m.title?.toString() ?: m.artist?.toString() ?: "—"
            val sub = if (m.title != null) m.artist?.toString().orEmpty() else m.albumTitle?.toString().orEmpty()
            QueueEntry(i, title, sub, i == cur)
        }
    }

    fun playQueueIndex(i: Int) {
        val c = controller ?: return
        c.seekTo(i, 0L)
        c.play()
    }

    fun removeFromQueue(i: Int) {
        val c = controller ?: return
        if (i != c.currentMediaItemIndex) c.removeMediaItem(i)
    }

    private fun Track.toMediaItem(): MediaItem {
        val u = Uri.parse(uri)
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artistName)
            .setAlbumTitle(albumName)
            .apply {
                if (fromMediaStore && albumId != 0L) setArtworkUri(Uri.parse("content://media/external/audio/albumart/$albumId"))
            }
            .build()
        return MediaItem.Builder()
            .setMediaId("t:$id")
            .setUri(u)
            .setMediaMetadata(meta)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(u).build())
            .build()
    }

    // ---------- Сохранение / восстановление очереди ----------
    private fun saveSession() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        val first = c.getMediaItemAt(0).mediaId
        if (!first.startsWith("t:")) return
        val ids = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId.removePrefix("t:") }
        prefs.edit()
            .putString("lastQueue", ids.joinToString("\n"))
            .putInt("lastIndex", c.currentMediaItemIndex)
            .putLong("lastPos", c.currentPosition.coerceAtLeast(0L))
            .apply()
    }

    private fun maybeRestore() {
        val c = controller ?: return
        if (!libraryLoaded || restored || tracks.isEmpty()) return
        restored = true
        if (c.mediaItemCount > 0 || !AppSettings.restoreQueue) return
        val ids = (prefs.getString("lastQueue", "") ?: "").split("\n").filter { it.isNotBlank() }
        val items = ids.mapNotNull { trackById[it] }
        if (items.isEmpty()) return
        val idx = prefs.getInt("lastIndex", 0).coerceIn(0, items.size - 1)
        c.setMediaItems(items.map { it.toMediaItem() }, idx, prefs.getLong("lastPos", 0L))
        c.prepare()
    }

    // =====================================================================
    // Радио
    // =====================================================================
    fun selectRadioTab(t: String) {
        radioTab = t
        radioQuery = ""
        prefs.edit().putString("tab", t).apply()
        loadRadioTab(t)
    }

    fun reloadRadio() {
        if (radioQuery.isNotBlank()) onRadioQuery(radioQuery) else loadRadioTab(radioTab)
    }

    private fun loadRadioTab(t: String) {
        if (t == FAV_TAB) {
            stations = favorites.map { radio.applyQuality(it) }; return
        }
        viewModelScope.launch {
            radioLoading = true
            val list = radio.byRegion(t).map { radio.applyQuality(it) }
            list.forEach { knownStations[it.id] = it }
            if (radioTab == t && radioQuery.isBlank()) stations = list
            if (list.isEmpty()) message = "Нет соединения с каталогом станций"
            radioLoading = false
        }
    }

    fun onRadioQuery(q: String) {
        radioQuery = q
        searchJob?.cancel()
        if (q.isBlank()) { loadRadioTab(radioTab); return }
        searchJob = viewModelScope.launch {
            delay(450)
            radioLoading = true
            val list = radio.search(q)
            list.forEach { knownStations[it.id] = it }
            stations = list
            radioLoading = false
        }
    }

    fun playStation(station: Station) {
        val c = controller ?: run { message = "Плеер ещё запускается…"; return }
        if (currentStation?.id == station.id) { togglePlay(); return }
        val list = stations.ifEmpty { listOf(station) }
        val index = list.indexOfFirst { it.id == station.id }.coerceAtLeast(0)
        list.forEach { knownStations[it.id] = it }
        c.setMediaItems(list.map { it.toMediaItem() }, index, 0L)
        c.shuffleModeEnabled = false
        c.prepare()
        c.play()
        currentTrack = null
        currentStation = station
        nowPlaying = ""
    }

    /** Переключить поток текущей станции на другой битрейт. */
    fun selectVariant(v: StreamVariant) {
        val c = controller ?: return
        val st = currentStation ?: return
        if (st.url == v.url) return
        val updated = st.withVariant(v)
        knownStations[updated.id] = updated
        stations = stations.map { if (it.id == updated.id) updated else it }
        val i = c.currentMediaItemIndex
        c.replaceMediaItem(i, updated.toMediaItem())
        c.prepare()
        c.play()
        currentStation = updated
        message = "Качество: ${v.bitrate.takeIf { it > 0 }?.let { "$it kbps" } ?: "авто"}"
    }

    fun isFavorite(s: Station) = favorites.any { it.id == s.id }

    fun toggleFavorite(s: Station) {
        val i = favorites.indexOfFirst { it.id == s.id }
        if (i >= 0) favorites.removeAt(i) else favorites.add(0, s)
        radio.saveFavorites(favorites)
        if (radioTab == FAV_TAB) stations = favorites.map { radio.applyQuality(it) }
    }

    /** Применить новые настройки качества радио к спискам и HLS-потокам. */
    fun onRadioSettingsChanged() {
        val limit = radio.bitrateLimit()
        stations = stations.map { radio.applyQuality(it, limit) }
        stations.forEach { knownStations[it.id] = it }
        applyRadioBitrateCap()
    }

    private fun applyRadioBitrateCap() {
        val c = controller ?: return
        val limit = radio.bitrateLimit()
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .setMaxAudioBitrate(if (limit == Int.MAX_VALUE) Int.MAX_VALUE else limit * 1000)
            .build()
    }

    private fun Station.toMediaItem(): MediaItem {
        val uri = Uri.parse(url)
        val meta = MediaMetadata.Builder()
            .setArtist(name)
            .setAlbumTitle(listOf(country, tags, bitrate.takeIf { it > 0 }?.let { "$it kbps" } ?: "")
                .filter { it.isNotBlank() }.joinToString(" · "))
            .apply { if (favicon.isNotBlank()) setArtworkUri(Uri.parse(favicon)) }
            .build()
        return MediaItem.Builder()
            .setMediaId("r:$id")
            .setUri(uri)
            .setMediaMetadata(meta)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .build()
    }

    // =====================================================================
    // Общие кнопки
    // =====================================================================
    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(c.currentMediaItemIndex, 0L)
            c.play()
        }
    }

    fun next() {
        val c = controller ?: return
        if (c.hasNextMediaItem()) { c.seekToNextMediaItem(); c.play() }
    }

    fun prev() {
        val c = controller ?: return
        if (currentTrack != null && c.currentPosition > 3000) { c.seekTo(0L); return }
        if (c.hasPreviousMediaItem()) { c.seekToPreviousMediaItem(); c.play() }
    }

    // ---------- Эффекты ----------
    fun selectEffect(e: VoiceEffect) {
        effect = e
        AudioFx.effect = e
        prefs.edit().putString("effect", e.name).apply()
        applyPlaybackParams()
    }

    private fun applyPlaybackParams() {
        val s = if (currentStation != null) 1f else speed
        controller?.playbackParameters = PlaybackParameters(s, effect.pitch)
    }

    fun selectDancer(d: Dancer) {
        dancer = d
        prefs.edit().putString("dancer", d.name).apply()
    }

    // ---------- Настройки ----------
    fun onPlaybackSettingsChanged() {
        AudioFx.settingsHook?.invoke() ?: AudioFx.syncEqFromSettings()
    }

    // ---------- Таймер сна ----------
    fun setSleep(minutes: Int) {
        sleepJob?.cancel()
        sleepMinutesLeft = minutes
        if (minutes <= 0) return
        sleepJob = viewModelScope.launch {
            while (sleepMinutesLeft > 0) {
                delay(60_000)
                sleepMinutesLeft--
            }
            controller?.pause()
            message = "Таймер сна: воспроизведение остановлено"
        }
    }

    override fun onCleared() {
        saveSession()
        controller?.removeListener(listener)
        controller?.release()
        super.onCleared()
    }
}

fun countLabel(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    val word = when {
        m10 == 1 && m100 != 11 -> "трек"
        m10 in 2..4 && m100 !in 12..14 -> "трека"
        else -> "треков"
    }
    return "$n $word"
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
