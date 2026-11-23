package com.vibe.radio.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
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
) {
    val artistName: String
        get() = artist.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Неизвестный исполнитель"
    val albumName: String
        get() = album.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Без альбома"
}

private class Meta(val title: String, val artist: String, val album: String, val duration: Long, val trackNo: Int)

/**
 * Медиатека: системный индекс MediaStore (память телефона + SD-карта)
 * и папки/файлы, добавленные пользователем вручную (Storage Access Framework).
 * Результат кэшируется в library.json, чтобы список открывался мгновенно.
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
                Track(
                    id = o.getString("id"), uri = o.getString("uri"),
                    title = o.optString("title"), artist = o.optString("artist"), album = o.optString("album"),
                    durationMs = o.optLong("dur"), folder = o.optString("folder"),
                    fromMediaStore = o.optBoolean("ms"), albumId = o.optLong("albumId"),
                    dateAdded = o.optLong("added"), trackNo = o.optInt("no"), key = o.optString("key"),
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
            progress("Сканирую память телефона и SD-карту…")
            runCatching { scanMediaStore() }.getOrDefault(emptyList<Track>()).forEach { result.putIfAbsent(it.key, it) }
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
                (manual || t.durationMs <= 0 || t.durationMs >= minMs) &&
                (manual || !(AppSettings.hideMessengers && isJunkFolder(t.folder)))
        }
        writeJson(list)
        list
    }

    private fun scanMediaStore(): List<Track> {
        val out = ArrayList<Track>()
        val q = Build.VERSION.SDK_INT >= 29
        val base = if (q) MediaStore.Audio.Media.getContentUri("external")
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = arrayListOf(
            "_id", "title", "artist", "album", "album_id", "duration",
            "_data", "_display_name", "_size", "date_added", "track",
        )
        if (q) { cols += "relative_path"; cols += "volume_name" }

        val cursor = context.contentResolver.query(base, cols.toTypedArray(), null, null, null) ?: return out
        cursor.use { c ->
            val iId = c.getColumnIndexOrThrow("_id")
            val iTitle = c.getColumnIndexOrThrow("title")
            val iArtist = c.getColumnIndexOrThrow("artist")
            val iAlbum = c.getColumnIndexOrThrow("album")
            val iAlbumId = c.getColumnIndexOrThrow("album_id")
            val iDur = c.getColumnIndexOrThrow("duration")
            val iData = c.getColumnIndexOrThrow("_data")
            val iName = c.getColumnIndexOrThrow("_display_name")
            val iSize = c.getColumnIndexOrThrow("_size")
            val iAdded = c.getColumnIndexOrThrow("date_added")
            val iTrack = c.getColumnIndexOrThrow("track")
            val iRel = if (q) c.getColumnIndex("relative_path") else -1
            val iVol = if (q) c.getColumnIndex("volume_name") else -1

            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val vol = if (iVol >= 0) c.getString(iVol) ?: "external_primary" else "external"
                val itemUri = if (q) ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(vol), id)
                else ContentUris.withAppendedId(base, id)
                val name = c.getString(iName).orEmpty()
                val rel = if (iRel >= 0) c.getString(iRel) else null
                val folder = if (rel != null) {
                    val prefix = if (vol == "external_primary") "Телефон" else "SD-карта"
                    if (rel.isBlank()) prefix else "$prefix/" + rel.trimEnd('/')
                } else pathFolder(c.getString(iData))

                out += Track(
                    id = "ms:$id",
                    uri = itemUri.toString(),
                    title = c.getString(iTitle)?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
                    artist = c.getString(iArtist).orEmpty(),
                    album = c.getString(iAlbum).orEmpty(),
                    durationMs = c.getLong(iDur),
                    folder = folder,
                    fromMediaStore = true,
                    albumId = c.getLong(iAlbumId),
                    dateAdded = c.getLong(iAdded),
                    trackNo = c.getInt(iTrack) % 1000,
                    key = name.lowercase() + "|" + c.getLong(iSize),
                )
            }
        }
        return out
    }

    private fun scanTree(tree: Uri, old: Map<String, Track>, out: MutableMap<String, Track>, progress: (String) -> Unit) {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return
        val rootName = root.name ?: "Папка"
        var count = 0

        fun walk(dir: DocumentFile, path: String, depth: Int) {
            if (depth > 12) return
            val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray<DocumentFile>())
            for (f in children) {
                if (f.isDirectory) {
                    walk(f, path + "/" + (f.name ?: ""), depth + 1)
                } else if (isAudio(f.name, f.type)) {
                    val t = docTrack(f, path, old)
                    out.putIfAbsent(t.key, t)
                    count++
                    if (count % 20 == 0) progress("$rootName: найдено $count…")
                }
            }
        }
        walk(root, rootName, 0)
    }

    private fun docTrack(f: DocumentFile, folder: String, old: Map<String, Track>): Track {
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
        )
    }

    private fun singleFile(uri: Uri, old: Map<String, Track>): Track? {
        val f = DocumentFile.fromSingleUri(context, uri) ?: return null
        if (!f.exists()) return null
        return docTrack(f, MANUAL, old)
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
        val name = runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Аудиофайл"
        val m = readMeta(uri)
        Track(
            id = "ext", uri = uri.toString(),
            title = m.title.ifBlank { name.substringBeforeLast('.') },
            artist = m.artist, album = m.album, durationMs = m.duration,
            folder = "Открыт из другого приложения", fromMediaStore = false, albumId = 0,
            dateAdded = System.currentTimeMillis() / 1000, trackNo = 0, key = name,
        )
    }

    companion object {
        const val MANUAL = "Добавленные файлы"

        private val audioExt = setOf(
            "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "amr", "mka", "webm", "3gp", "m4b", "wma",
        )

        fun isAudio(name: String?, mime: String?): Boolean {
            if (mime?.startsWith("audio/") == true) return true
            val ext = name?.substringAfterLast('.', "")?.lowercase() ?: return false
            return ext in audioExt
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
