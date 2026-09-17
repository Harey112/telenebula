package com.telenebula.app

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder

class TeleNebulaApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.runtime.boot()
    }

    /**
     * Teaches the shared image loader to decode a frame out of a video file, so a video
     * attachment shows a real poster instead of a blank tile. The dependency was already on the
     * classpath; without registering the decoder here nothing ever used it.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).components { add(VideoFrameDecoder.Factory()) }.build()
}

val Context.appGraph: AppGraph get() = (applicationContext as TeleNebulaApp).graph
