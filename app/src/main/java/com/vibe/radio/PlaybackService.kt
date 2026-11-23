package com.vibe.radio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.metadata.MetadataRenderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.vibe.radio.audio.AudioFx
import com.vibe.radio.audio.FxAudioProcessor
import com.vibe.radio.data.AppSettings

/**
 * Фоновый сервис: играет при свёрнутом приложении и заблокированном экране,
 * показывает уведомление и управление на экране блокировки (MediaSession).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var exo: ExoPlayer? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && AppSettings.pauseOnDisconnect) {
                exo?.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val sink = DefaultAudioSink.Builder(this)
            .setAudioProcessors(arrayOf(FxAudioProcessor()))
            .build()

        // Только аудио + метаданные (ICY: название трека). Видео радио не нужно.
        val renderersFactory = RenderersFactory { handler, _, audioListener, _, metadataOutput ->
            arrayOf<Renderer>(
                MediaCodecAudioRenderer(this, MediaCodecSelector.DEFAULT, handler, audioListener, sink),
                MetadataRenderer(metadataOutput, handler.looper),
            )
        }

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("PLAYpay/2.0 (Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.setSkipSilenceEnabled(AppSettings.skipSilence)
        exo = player

        // Системный эквалайзер на аудиосессии плеера
        AudioFx.attachEqualizer(player.audioSessionId)
        AudioFx.settingsHook = { applySettings() }

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                // URI станции передаём через requestMetadata — восстанавливаем его здесь
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> {
                    val resolved = mediaItems.map { item ->
                        val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
                        if (uri != null) item.buildUpon().setUri(uri).build() else item
                    }.toMutableList()
                    return Futures.immediateFuture(resolved)
                }
            })
            .build()
    }

    /** Применить изменённые настройки без перезапуска плеера. */
    private fun applySettings() {
        val p = exo ?: return
        p.setSkipSilenceEnabled(AppSettings.skipSilence)
        AudioFx.applyEqualizer()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(noisyReceiver) }
        AudioFx.settingsHook = null
        AudioFx.releaseEqualizer()
        exo = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
