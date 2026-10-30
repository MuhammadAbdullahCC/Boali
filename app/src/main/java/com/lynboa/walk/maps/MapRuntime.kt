package com.lynboa.walk.maps

import android.content.Context
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Cache
import java.io.File
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import java.util.concurrent.TimeUnit

/** Lazy MapLibre setup, independent from Boa's step counter. */
object MapRuntime {
    @Volatile private var ready = false
    @Synchronized fun ensure(context: Context) {
        if (ready) return
        MapLibre.getInstance(context.applicationContext)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder()
                    .header("User-Agent", "Boali/0.5.0 (+https://github.com/MuhammadAbdullahCC/Boali)")
                    .build())
            }
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 4; maxRequests = 4 })
            .connectTimeout(20, TimeUnit.SECONDS)
            .cache(Cache(File(context.cacheDir, "boali-map-http-cache"),
                96L * 1024 * 1024))
            .build()
        HttpRequestUtil.setOkHttpClient(client)
        ready = true
    }
}
