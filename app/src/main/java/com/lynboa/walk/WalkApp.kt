package com.lynboa.walk

import android.app.Application
import com.lynboa.walk.data.FitnessRepository
import com.lynboa.walk.notifications.CoachNotifications
import com.lynboa.walk.notifications.HydrationWorker
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class WalkApp : Application() {
    val repository by lazy { FitnessRepository(this) }

    override fun onCreate() {
        super.onCreate()
        // Named requests, 4 maximum parallel requests to the map host per provider policy.
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder()
                    .header("User-Agent", "LynBoaWalk/0.1 (personal Android walking tracker)").build())
            }
            .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 4; maxRequests = 4 })
            .connectTimeout(20, TimeUnit.SECONDS).build()
        HttpRequestUtil.setOkHttpClient(client)
        MapLibre.getInstance(this)
        CoachNotifications.createChannels(this)
        HydrationWorker.schedule(this)
    }
}
