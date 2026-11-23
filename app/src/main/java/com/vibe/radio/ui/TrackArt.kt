package com.vibe.radio.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Модель для загрузки обложки трека через Coil. */
data class TrackArt(val uri: String, val fromMediaStore: Boolean, val albumId: Long)

class TrackArtKeyer : Keyer<TrackArt> {
    override fun key(data: TrackArt, options: Options): String =
        if (data.fromMediaStore && data.albumId != 0L) "album:${data.albumId}" else "art:${data.uri}"
}

/**
 * Обложка: системная миниатюра (Android 10+), обложка альбома из MediaStore
 * или картинка, встроенная в сам файл (ID3 / FLAC / MP4).
 */
class TrackArtFetcher(private val data: TrackArt, private val options: Options) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val ctx = options.context
        val bmp = withContext(Dispatchers.IO) { load(ctx) } ?: throw IOException("Нет обложки")
        return DrawableResult(
            drawable = BitmapDrawable(ctx.resources, bmp),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    private fun load(ctx: Context): Bitmap? {
        val uri = Uri.parse(data.uri)
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return ctx.contentResolver.loadThumbnail(uri, Size(512, 512), null)
            } catch (_: Exception) {
            }
        } else if (data.fromMediaStore && data.albumId != 0L) {
            try {
                val art = Uri.parse("content://media/external/audio/albumart/${data.albumId}")
                ctx.contentResolver.openInputStream(art)?.use { s ->
                    BitmapFactory.decodeStream(s)?.let { return it }
                }
            } catch (_: Exception) {
            }
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val bytes = r.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 512 && bounds.outHeight / (sample * 2) >= 512) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) {
            return null
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }

    class Factory : Fetcher.Factory<TrackArt> {
        override fun create(data: TrackArt, options: Options, imageLoader: ImageLoader): Fetcher =
            TrackArtFetcher(data, options)
    }
}
