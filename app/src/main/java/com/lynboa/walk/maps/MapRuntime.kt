package com.lynboa.walk.maps

import android.content.Context
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
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
                    .header("User-Agent", "LynBoaWalk/0.1 (personal Android walking tracker)")
                    .build())
            }
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 4; maxRequests = 4 })
            .connectTimeout(20, TimeUnit.SECONDS)
            .build()
        HttpRequestUtil.setOkHttpClient(client)
        ready = true
    }
}
