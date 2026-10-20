package org.hostapptv.player

import android.app.Application
import android.content.ComponentCallbacks2
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

class HostAppTVApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        internal set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /** One image stack: 24 MiB memory, 128 MiB disk, shared OkHttp (cleartext policy applies). */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
            .memoryCache { MemoryCache.Builder().maxSizeBytes(24L * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork")).maxSizeBytes(128L * 1024 * 1024).build() }
            .crossfade(false)
            .build()

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            SingletonImageLoader.get(this).memoryCache?.trimToSize(0)
        }
    }
}

val android.content.Context.container: AppContainer get() = (applicationContext as HostAppTVApplication).container
