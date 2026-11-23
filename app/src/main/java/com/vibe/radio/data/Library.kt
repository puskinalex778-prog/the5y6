package com.vibe.radio.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Track(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val folder: String,
    val fromMediaStore: Boolean,
    val albumId: Long,
    val dateAdded: Long,
    val trackNo: Int,
    val key: String,
    val format: String = "",
    val onSdCard: Boolean = false,
) {
    val artistName: String
        get() = artist.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Неизвестный исполнитель"
    val albumName: String
        get() = album.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Без альбома"
    val formatBadge: String
        get() = format.ifBlank { LibraryRepository.extFromName(title) }
}

private class Meta(val title: String, val artist: String, val album: String, val duration: Long, val trackNo: Int)

/**
 * Полноценная медиатека:
 * 1) Системный индекс MediaStore по ВСЕМ томам (память телефона + все SD-карты / OTG),
 *    включая отдельный добор MP3 и WAV из MediaStore.Files.
 * 2) Прямое рекурсивное сканирование доступных томов (Телефон и SD-карта) на MP3, WAV и др.
 * 3) Папки и файлы, добавленные пользователем вручную (Storage Access Framework — работает на любой SD-карте).
 * Результат кэшируется в library.json для мгновенного открытия списка.
 */
class LibraryRepository(private val context: Context) {

    private val file = File(context.filesDir, "library.json")
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    // ---------- Папки и файлы, добавленные вручную ----------
    fun folders(): List<String> = readList("folders")
    fun addFolder(uri: String) = writeList("folders", (folders() + uri).distinct())
    fun removeFolder(uri: String) = writeList("folders", folders() - uri)

    fun files(): List<String> = readList("files")
    fun addFiles(uris: List<String>) = writeList("files", (files() + uris).distinct())

    fun hidden(): Set<String> = readList("hidden").toSet()
    fun hide(id: String) {
        writeList("hidden", (readList("hidden") + id).distinct())
        writeList("files", files().filter { it != id })
    }
    fun clearHidden() = writeList("hidden", emptyList())

    private fun readList(key: String): List<String> =
        (prefs.getString(key, "") ?: "").split("\n").filter { it.isNotBlank() }

    private fun writeList(key: String, list: List<String>) =
        prefs.edit().putString(key, list.joinToString("\n")).apply()

    // ---------- Кэш ----------
    suspend fun load(): List<Track> = withContext(Dispatchers.IO) { readJson() }

    private fun readJson(): List<Track> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val folder = o.optString("folder")
                val fmt = o.optString("fmt").ifBlank { extFromName(o.optString("key").substringBefore('|')) }
                val sd = if (o.has("sd")) o.optBoolean("sd") else folder.startsWith("SD-карта")
                Track(
                    id = o.getString("id"), uri = o.getString("uri"),
                    title = o.optString("title"), artist = o.optString("artist"), album = o.optString("album"),
                    durationMs = o.optLong("dur"), folder = folder,
                    fromMediaStore = o.optBoolean("ms"), albumId = o.optLong("albumId"),
                    dateAdded = o.optLong("added"), trackNo = o.optInt("no"), key = o.optString("key"),
                    format = fmt, onSdCard = sd,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeJson(list: List<Track>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id); put("uri", t.uri); put("title", t.title); put("artist", t.artist)
                put("album", t.album); put("dur", t.durationMs); put("folder", t.folder); put("ms", t.fromMediaStore)
                put("albumId", t.albumId); put("added", t.dateAdded); put("no", t.trackNo); put("key", t.key)
                put("fmt", t.format); put("sd", t.onSdCard)
            })
        }
        runCatching { file.writeText(arr.toString()) }
    }

    /** Убрать трек из медиатеки (файл на устройстве не удаляется). */
    suspend fun removeAndSave(id: String, current: List<Track>): List<Track> = withContext(Dispatchers.IO) {
        hide(id)
        val list = current.filter { it.id != id }
        writeJson(list)
        list
    }

    // ---------- Сканирование ----------
    suspend fun scan(withMediaStore: Boolean, progress: (String) -> Unit): List<Track> = withContext(Dispatchers.IO) {
        val old = readJson().associateBy { it.uri }
        val result = LinkedHashMap<String, Track>()

        if (withMediaStore) {
            progress("Сканирую MP3 и WAV (телефон + SD-карта)…")
            runCatching { scanMediaStore(progress) }
                .getOrDefault(emptyList())
                .forEach { result.putIfAbsent(it.key, it) }

            progress("Проверяю папки телефона и SD-карты…")
            runCatching { scanStorageVolumes(old, result, progress) }
        }

        val folders = folders()
        folders.forEachIndexed { i, f ->
            progress("Папка ${i + 1} из ${folders.size}…")
            runCatching { scanTree(Uri.parse(f), old, result, progress) }
        }

        files().forEach { u ->
            runCatching { singleFile(Uri.parse(u), old) }.getOrNull()?.let { result.putIfAbsent(it.key, it) }
        }

        val hidden = hidden()
        val minMs = AppSettings.minDurationSec * 1000L
        val list = result.values.filter { t ->
            val manual = t.folder == MANUAL
            t.id !in hidden &&
                (manual || minMs <= 0L || t.durationMs <= 0L || t.durationMs >= minMs) &&
                (manual || !(AppSettings.hideMessengers && isJunkFolder(t.folder)))
        }
        writeJson(list)
        list
    }

    /**
     * Сканирует MediaStore по ВСЕМ доступным томам (память телефона + все SD-карты).
     * Дополнительно проверяет MediaStore.Files для MP3 и WAV, если система не отнесла их к Audio.
     */
    private fun scanMediaStore(progress: (String) -> Unit): List<Track> {
        val out = ArrayList<Track>()
        val seenKeys = HashSet<String>()

        if (Build.VERSION.SDK_INT >= 29) {
            val volumes = runCatching { MediaStore.getExternalVolumeNames(context) }
                .getOrDefault(setOf("external_primary"))
                .ifEmpty { setOf("external_primary") }

            for (vol in volumes) {
                val isSd = vol != "external_primary"
                if (isSd) progress("Сканирую SD-карту ($vol)…")
                queryAudioVolume(vol, isSd, seenKeys, out)
                queryMp3WavFilesVolume(vol, isSd, seenKeys, out)
            }
        } else {
            queryAudioLegacy(seenKeys, out)
            queryMp3WavFilesLegacy(seenKeys, out)
        }
        return out
    }

    private fun queryAudioVolume(vol: String, isSd: Boolean, seenKeys: MutableSet<String>, out: MutableList<Track>) {
        if (Build.VERSION.SDK_INT < 29) return
        val base = MediaStore.Audio.Media.getContentUri(vol)
        val cols = arrayOf(
            "_id", "title", "artist", "album", "album_id", "duration",
            "_data", "_display_name", "_size", "date_added", "track",
            "relative_path", "volume_name", "mime_type",
        )
        val cursor = runCatching {
            context.contentResolver.query(base, cols, null, null, null)
        }.getOrNull() ?: return

        cursor.use { c ->
            val iId = c.getColumnIndex("_id")
            val iTitle = c.getColumnIndex("title")
            val iArtist = c.getColumnIndex("artist")
            val iAlbum = c.getColumnIndex("album")
            val iAlbumId = c.getColumnIndex("album_id")
            val iDur = c.getColumnIndex("duration")
            val iData = c.getColumnIndex("_data")
            val iName = c.getColumnIndex("_display_name")
            val iSize = c.getColumnIndex("_size")
            val iAdded = c.getColumnIndex("date_added")
            val iTrack = c.getColumnIndex("track")
            val iRel = c.getColumnIndex("relative_path")
            val iVol = c.getColumnIndex("volume_name")
            val iMime = c.getColumnIndex("mime_type")

            while (c.moveToNext()) {
                if (iId < 0) continue
                val id = c.getLong(iId)
                val volName = if (iVol >= 0) c.getString(iVol) ?: vol else vol
                val sd = isSd || (volName != "external_primary")
                val name = if (iName >= 0) c.getString(iName).orEmpty() else ""
                val mime = if (iMime >= 0) c.getString(iMime) else null
                val dataPath = if (iData >= 0) c.getString(iData) else null
                val fileName = name.ifBlank { dataPath?.substringAfterLast('/') ?: "" }
                val size = if (iSize >= 0) c.getLong(iSize) else 0L
                val key = fileName.lowercase() + "|" + size
                if (fileName.isNotBlank() && !seenKeys.add(key)) continue

                val itemUri = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(volName), id)
                val rel = if (iRel >= 0) c.getString(iRel) else null
                val folder = if (rel != null) {
                    val prefix = if (sd) "SD-карта" else "Телефон"
                    if (rel.isBlank()) prefix else "$prefix/" + rel.trimEnd('/')
                } else pathFolder(dataPath)

                val fmt = detectFormat(fileName, mime)
                val titleRaw = if (iTitle >= 0) c.getString(iTitle) else null
                out += Track(
                    id = "ms:$volName:$id",
                    uri = itemUri.toString(),
                    title = titleRaw?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.').ifBlank { "Трек" },
                    artist = if (iArtist >= 0) c.getString(iArtist).orEmpty() else "",
                    album = if (iAlbum >= 0) c.getString(iAlbum).orEmpty() else "",
                    durationMs = if (iDur >= 0) c.getLong(iDur) else 0L,
                    folder = folder,
                    fromMediaStore = true,
                    albumId = if (iAlbumId >= 0) c.getLong(iAlbumId) else 0L,
                    dateAdded = if (iAdded >= 0) c.getLong(iAdded) else 0L,
                    trackNo = (if (iTrack >= 0) c.getInt(iTrack) else 0) % 1000,
                    key = key,
                    format = fmt,
                    onSdCard = sd || folder.startsWith("SD-карта"),
                )
            }
        }
    }

    /** Добирает .mp3 и .wav из MediaStore.Files на конкретном томе (важно для WAV!). */
    private fun queryMp3WavFilesVolume(vol: String, isSd: Boolean, seenKeys: MutableSet<String>, out: MutableList<Track>) {
        if (Build.VERSION.SDK_INT < 29) return
        val base = MediaStore.Files.getContentUri(vol)
        val cols = arrayOf("_id", "_display_name", "_size", "date_added", "relative_path", "_data", "mime_type")
        val sel = "LOWER(_display_name) LIKE '%.mp3' OR LOWER(_display_name) LIKE '%.wav'"
        val cursor = runCatching {
            context.contentResolver.query(base, cols, sel, null, null)
        }.getOrNull() ?: return

        cursor.use { c ->
            val iId = c.getColumnIndex("_id")
            val iName = c.getColumnIndex("_display_name")
            val iSize = c.getColumnIndex("_size")
            val iAdded = c.getColumnIndex("date_added")
            val iRel = c.getColumnIndex("relative_path")
            val iData = c.getColumnIndex("_data")
            val iMime = c.getColumnIndex("mime_type")

            while (c.moveToNext()) {
                if (iId < 0) continue
                val id = c.getLong(iId)
                val name = if (iName >= 0) c.getString(iName).orEmpty() else ""
                val size = if (iSize >= 0) c.getLong(iSize) else 0L
                if (name.isBlank() || size <= 0L) continue
                val key = name.lowercase() + "|" + size
                if (!seenKeys.add(key)) continue

                val itemUri = ContentUris.withAppendedId(base, id)
                val rel = if (iRel >= 0) c.getString(iRel) else null
                val dataPath = if (iData >= 0) c.getString(iData) else null
                val folder = if (rel != null) {
                    val prefix = if (isSd) "SD-карта" else "Телефон"
                    if (rel.isBlank()) prefix else "$prefix/" + rel.trimEnd('/')
                } else pathFolder(dataPath)
                val mime = if (iMime >= 0) c.getString(iMime) else null
                val m = readMeta(itemUri)

                out += Track(
                    id = "msf:$vol:$id",
                    uri = itemUri.toString(),
                    title = m.title.ifBlank { name.substringBeforeLast('.') },
                    artist = m.artist,
                    album = m.album,
                    durationMs = m.duration,
                    folder = folder,
                    fromMediaStore = false,
                    albumId = 0L,
                    dateAdded = if (iAdded >= 0) c.getLong(iAdded) else 0L,
                    trackNo = m.trackNo,
                    key = key,
                    format = detectFormat(name, mime),
                    onSdCard = isSd || folder.startsWith("SD-карта"),
                )
            }
        }
    }

    private fun queryAudioLegacy(seenKeys: MutableSet<String>, out: MutableList<Track>) {
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(
            "_id", "title", "artist", "album", "album_id", "duration",
            "_data", "_display_name", "_size", "date_added", "track", "mime_type",
        )
        val cursor = runCatching {
            context.contentResolver.query(base, cols, null, null, null)
        }.getOrNull() ?: return

        cursor.use { c ->
            val iId = c.getColumnIndex("_id")
            val iTitle = c.getColumnIndex("title")
            val iArtist = c.getColumnIndex("artist")
            val iAlbum = c.getColumnIndex("album")
            val iAlbumId = c.getColumnIndex("album_id")
            val iDur = c.getColumnIndex("duration")
            val iData = c.getColumnIndex("_data")
            val iName = c.getColumnIndex("_display_name")
            val iSize = c.getColumnIndex("_size")
            val iAdded = c.getColumnIndex("date_added")
            val iTrack = c.getColumnIndex("track")
            val iMime = c.getColumnIndex("mime_type")

            while (c.moveToNext()) {
                if (iId < 0) continue
                val id = c.getLong(iId)
                val dataPath = if (iData >= 0) c.getString(iData) else null
                val name = (if (iName >= 0) c.getString(iName) else null)
                    ?.takeIf { it.isNotBlank() } ?: dataPath?.substringAfterLast('/').orEmpty()
                val size = if (iSize >= 0) c.getLong(iSize) else 0L
                val key = name.lowercase() + "|" + size
                if (name.isNotBlank() && !seenKeys.add(key)) continue

                val folder = pathFolder(dataPath)
                val sd = folder.startsWith("SD-карта")
                val mime = if (iMime >= 0) c.getString(iMime) else null
                val itemUri = ContentUris.withAppendedId(base, id)
                val titleRaw = if (iTitle >= 0) c.getString(iTitle) else null

                out += Track(
                    id = "ms:$id",
                    uri = itemUri.toString(),
                    title = titleRaw?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.').ifBlank { "Трек" },
                    artist = if (iArtist >= 0) c.getString(iArtist).orEmpty() else "",
                    album = if (iAlbum >= 0) c.getString(iAlbum).orEmpty() else "",
                    durationMs = if (iDur >= 0) c.getLong(iDur) else 0L,
                    folder = folder,
                    fromMediaStore = true,
                    albumId = if (iAlbumId >= 0) c.getLong(iAlbumId) else 0L,
                    dateAdded = if (iAdded >= 0) c.getLong(iAdded) else 0L,
                    trackNo = (if (iTrack >= 0) c.getInt(iTrack) else 0) % 1000,
                    key = key,
                    format = detectFormat(name, mime),
                    onSdCard = sd,
                )
            }
        }
    }

    private fun queryMp3WavFilesLegacy(seenKeys: MutableSet<String>, out: MutableList<Track>) {
        val base = MediaStore.Files.getContentUri("external")
        val cols = arrayOf("_id", "_display_name", "_size", "date_added", "_data", "mime_type")
        val sel = "LOWER(_display_name) LIKE '%.mp3' OR LOWER(_display_name) LIKE '%.wav'"
        val cursor = runCatching {
            context.contentResolver.query(base, cols, sel, null, null)
        }.getOrNull() ?: return

        cursor.use { c ->
            val iId = c.getColumnIndex("_id")
            val iName = c.getColumnIndex("_display_name")
            val iSize = c.getColumnIndex("_size")
            val iAdded = c.getColumnIndex("date_added")
            val iData = c.getColumnIndex("_data")
            val iMime = c.getColumnIndex("mime_type")

            while (c.moveToNext()) {
                if (iId < 0) continue
                val id = c.getLong(iId)
                val dataPath = if (iData >= 0) c.getString(iData) else null
                val name = (if (iName >= 0) c.getString(iName) else null)
                    ?.takeIf { it.isNotBlank() } ?: dataPath?.substringAfterLast('/').orEmpty()
                val size = if (iSize >= 0) c.getLong(iSize) else 0L
                if (name.isBlank() || size <= 0L) continue
                val key = name.lowercase() + "|" + size
                if (!seenKeys.add(key)) continue

                val itemUri = ContentUris.withAppendedId(base, id)
                val folder = pathFolder(dataPath)
                val mime = if (iMime >= 0) c.getString(iMime) else null
                val m = readMeta(itemUri)

                out += Track(
                    id = "msf:$id",
                    uri = itemUri.toString(),
                    title = m.title.ifBlank { name.substringBeforeLast('.') },
                    artist = m.artist,
                    album = m.album,
                    durationMs = m.duration,
                    folder = folder,
                    fromMediaStore = false,
                    albumId = 0L,
                    dateAdded = if (iAdded >= 0) c.getLong(iAdded) else 0L,
                    trackNo = m.trackNo,
                    key = key,
                    format = detectFormat(name, mime),
                    onSdCard = folder.startsWith("SD-карта"),
                )
            }
        }
    }

    /**
     * Прямое сканирование смонтированных томов (Телефон + SD-карта) — находит MP3/WAV,
     * даже если системный MediaStore ещё не проиндексировал карту памяти.
     */
    private fun scanStorageVolumes(
        old: Map<String, Track>,
        out: MutableMap<String, Track>,
        progress: (String) -> Unit,
    ) {
        val roots = LinkedHashMap<String, Pair<File, Boolean>>() // path -> (dir, isSdCard)

        // 1. StorageManager (включает съёмные SD-карты на Android 7..14+)
        runCatching {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            for (v in sm.storageVolumes) {
                val dir: File? = if (Build.VERSION.SDK_INT >= 30) {
                    v.directory
                } else {
                    if (v.isPrimary) Environment.getExternalStorageDirectory()
                    else v.uuid?.let { File("/storage/$it") }
                }
                if (dir != null && dir.exists() && dir.canRead()) {
                    roots[dir.absolutePath] = dir to (v.isRemovable || !v.isPrimary)
                }
            }
        }

        // 2. Fallback через getExternalFilesDirs — надёжно определяет корни SD-карт
        runCatching {
            context.getExternalFilesDirs(null).forEachIndexed { idx, appDir ->
                val path = appDir?.absolutePath ?: return@forEachIndexed
                val rootPath = path.substringBefore("/Android/data", "")
                if (rootPath.isNotBlank()) {
                    val f = File(rootPath)
                    if (f.exists() && f.canRead()) {
                        val isSd = idx > 0 && !rootPath.startsWith("/storage/emulated")
                        roots.putIfAbsent(f.absolutePath, f to isSd)
                    }
                }
            }
        }

        var found = 0
        for ((_, pair) in roots) {
            val (rootDir, isSd) = pair
            val label = if (isSd) "SD-карта" else "Телефон"
            fun walk(dir: File, depth: Int) {
                if (depth > 9) return
                val entries = runCatching { dir.listFiles() }.getOrNull() ?: return
                for (f in entries) {
                    val name = f.name
                    if (name.startsWith(".")) continue
                    if (f.isDirectory) {
                        if (depth == 0 && (name.equals("Android", ignoreCase = true) || name.equals("LOST.DIR", ignoreCase = true))) {
                            continue
                        }
                        walk(f, depth + 1)
                    } else if (f.isFile && f.length() > 0L && isAudio(name, null)) {
                        val key = name.lowercase() + "|" + f.length()
                        if (out.containsKey(key)) continue
                        val fileUri = Uri.fromFile(f).toString()
                        val relPath = f.parentFile?.absolutePath
                            ?.removePrefix(rootDir.absolutePath)
                            ?.trimStart('/')
                            .orEmpty()
                        val folder = if (relPath.isBlank()) label else "$label/$relPath"
                        val cached = old[fileUri]
                        val track = if (cached != null && cached.folder == folder) {
                            cached
                        } else {
                            val m = readMeta(Uri.fromFile(f))
                            Track(
                                id = "fs:${f.absolutePath}",
                                uri = fileUri,
                                title = m.title.ifBlank { name.substringBeforeLast('.') },
                                artist = m.artist,
                                album = m.album,
                                durationMs = m.duration,
                                folder = folder,
                                fromMediaStore = false,
                                albumId = 0L,
                                dateAdded = f.lastModified() / 1000L,
                                trackNo = m.trackNo,
                                key = key,
                                format = detectFormat(name, null),
                                onSdCard = isSd,
                            )
                        }
                        out.putIfAbsent(key, track)
                        found++
                        if (found % 25 == 0) progress("$label: найдено $found…")
                    }
                }
            }
            walk(rootDir, 0)
        }
    }

    private fun scanTree(tree: Uri, old: Map<String, Track>, out: MutableMap<String, Track>, progress: (String) -> Unit) {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return
        val decoded = Uri.decode(tree.toString())
        val volPart = decoded.substringAfterLast("/tree/").substringBefore("/document/").substringBefore(':')
        val isSd = volPart.isNotBlank() && volPart != "primary"
        val prefix = if (isSd) "SD-карта/" else ""
        val rootName = prefix + (root.name ?: "Папка")
        var count = 0

        fun walk(dir: DocumentFile, path: String, depth: Int) {
            if (depth > 12) return
            val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray<DocumentFile>())
            for (f in children) {
                if (f.isDirectory) {
                    walk(f, path + "/" + (f.name ?: ""), depth + 1)
                } else if (isAudio(f.name, f.type)) {
                    val t = docTrack(f, path, old, isSd)
                    out.putIfAbsent(t.key, t)
                    count++
                    if (count % 20 == 0) progress("$rootName: найдено $count…")
                }
            }
        }
        walk(root, rootName, 0)
    }

    private fun docTrack(f: DocumentFile, folder: String, old: Map<String, Track>, onSdCard: Boolean): Track {
        val uri = f.uri.toString()
        val name = f.name ?: "track"
        old[uri]?.let { if (it.folder == folder) return it }
        val m = readMeta(f.uri)
        return Track(
            id = uri, uri = uri,
            title = m.title.ifBlank { name.substringBeforeLast('.') },
            artist = m.artist, album = m.album, durationMs = m.duration,
            folder = folder, fromMediaStore = false, albumId = 0,
            dateAdded = f.lastModified() / 1000, trackNo = m.trackNo,
            key = name.lowercase() + "|" + f.length(),
            format = detectFormat(name, f.type),
            onSdCard = onSdCard,
        )
    }

    private fun singleFile(uri: Uri, old: Map<String, Track>): Track? {
        val f = DocumentFile.fromSingleUri(context, uri) ?: return null
        if (!f.exists()) return null
        return docTrack(f, MANUAL, old, onSdCard = false)
    }

    /** Чтение тегов (название, исполнитель, альбом, длительность). */
    private fun readMeta(uri: Uri): Meta {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            Meta(
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                trackNo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0,
            )
        } catch (_: Exception) {
            Meta("", "", "", 0L, 0)
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }

    /** Метаданные для файла, открытого из другого приложения. */
    suspend fun describe(uri: Uri): Track = withContext(Dispatchers.IO) {
        val doc = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
        val name = doc?.name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Аудиофайл"
        val m = readMeta(uri)
        Track(
            id = "ext", uri = uri.toString(),
            title = m.title.ifBlank { name.substringBeforeLast('.') },
            artist = m.artist, album = m.album, durationMs = m.duration,
            folder = "Открыт из другого приложения", fromMediaStore = false, albumId = 0,
            dateAdded = System.currentTimeMillis() / 1000, trackNo = 0, key = name,
            format = detectFormat(name, doc?.type),
            onSdCard = false,
        )
    }

    companion object {
        const val MANUAL = "Добавленные файлы"

        private val audioExt = setOf(
            "mp3", "wav", "wave", "m4a", "aac", "flac", "ogg", "oga", "opus", "amr", "mka", "webm", "3gp", "m4b", "wma",
        )

        fun isAudio(name: String?, mime: String?): Boolean {
            if (mime?.startsWith("audio/") == true) return true
            val ext = name?.substringAfterLast('.', "")?.lowercase() ?: return false
            return ext in audioExt
        }

        fun extFromName(name: String): String {
            val ext = name.substringAfterLast('.', "").uppercase()
            return if (ext.lowercase() in audioExt) {
                if (ext == "WAVE") "WAV" else ext
            } else ""
        }

        fun detectFormat(name: String?, mime: String?): String {
            val byName = name?.let { extFromName(it) }.orEmpty()
            if (byName.isNotBlank()) return byName
            val m = mime?.lowercase().orEmpty()
            return when {
                "mpeg" in m || "mp3" in m -> "MP3"
                "wav" in m || "wave" in m -> "WAV"
                "flac" in m -> "FLAC"
                "ogg" in m || "vorbis" in m -> "OGG"
                "opus" in m -> "OPUS"
                "aac" in m || "mp4" in m -> "M4A"
                else -> "AUDIO"
            }
        }

        private val junk = listOf(
            "whatsapp", "telegram", "viber", "/recordings", "callrec", "call recordings", "/call",
            "ringtones", "notifications", "alarms", "sound recorder", "voice recorder", "диктофон", "/recorder",
        )

        fun isJunkFolder(folder: String): Boolean {
            val f = folder.lowercase()
            return junk.any { f.contains(it) }
        }

        private fun pathFolder(data: String?): String {
            if (data.isNullOrBlank()) return "Телефон"
            val parent = data.substringBeforeLast('/')
            return when {
                parent.startsWith("/storage/emulated/0") -> "Телефон" + parent.removePrefix("/storage/emulated/0")
                parent.startsWith("/storage/") -> "SD-карта" + parent.removePrefix("/storage/").dropWhile { it != '/' }
                else -> parent
            }
        }
    }
}
