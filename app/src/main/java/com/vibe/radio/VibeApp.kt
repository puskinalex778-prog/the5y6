package com.vibe.radio

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.vibe.radio.audio.AudioFx
import com.vibe.radio.data.AppSettings
import com.vibe.radio.ui.TrackArtFetcher
import com.vibe.radio.ui.TrackArtKeyer

class VibeApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        AudioFx.syncEqFromSettings()
    }

    /** Coil с поддержкой обложек из локальных аудиофайлов. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(TrackArtKeyer())
            add(TrackArtFetcher.Factory())
        }
        .crossfade(true)
        .build()
}
