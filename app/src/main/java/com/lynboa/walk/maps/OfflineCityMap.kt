package com.lynboa.walk.maps

import android.content.Context
import com.lynboa.walk.data.FitnessRepository
import com.lynboa.walk.data.RoutePoint
import kotlin.math.roundToInt
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/** One modest city download. Map data © OpenStreetMap contributors. */
object OfflineCityMap {
    fun download(context: Context, repository: FitnessRepository, center: RoutePoint? = null) {
        val lat = center?.lat ?: MapConfig.CITY_LAT
        val lon = center?.lon ?: MapConfig.CITY_LON
        val regionName = "boali-local-v2-" + (lat * 100).roundToInt() +
            "-" + (lon * 100).roundToInt()
        val manager = try {
            MapRuntime.ensure(context)
            OfflineManager.getInstance(context)
        } catch (error: Throwable) {
            repository.offlineStatus.value = "Map unavailable: ${error.message}"
            return
        }
        repository.offlineStatus.value = "Checking existing offline map…"
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val existing = offlineRegions?.firstOrNull {
                    String(it.metadata, Charsets.UTF_8) == regionName
                }
                if (existing != null) {
                    existing.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            if (status?.isComplete == true) repository.offlineStatus.value =
                                "City map downloaded · available offline"
                            else watch(existing, repository)
                        }
                        override fun onError(error: String?) {
                            repository.offlineStatus.value = "Map status error: $error"
                        }
                    })
                } else create(manager, repository, lat, lon, regionName)
            }
            override fun onError(error: String) {
                repository.offlineStatus.value = "Offline map lookup failed: $error"
            }
        })
    }

    private fun create(manager: OfflineManager, repo: FitnessRepository,
                       lat: Double, lon: Double, regionName: String) {
        // About 5 km around the chosen GPS fix, zoom 12..16, below provider caps.
        val bounds = LatLngBounds.Builder()
            .include(LatLng((lat - .022).coerceAtLeast(-85.0), (lon - .030).coerceAtLeast(-180.0)))
            .include(LatLng((lat + .022).coerceAtMost(85.0), (lon + .030).coerceAtMost(180.0)))
            .build()
        val definition = OfflineTilePyramidRegionDefinition(MapConfig.STYLE,
            bounds, 12.0, 16.0, 1f)
        manager.createOfflineRegion(definition, regionName.toByteArray(),
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    watch(offlineRegion, repo)
                }
                override fun onError(error: String) {
                    repo.offlineStatus.value = "Map download setup failed: $error"
                }
            })
    }

    private fun watch(region: OfflineRegion, repo: FitnessRepository) {
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                val tiles = status.completedTileCount
                val size = status.completedResourceSize
                if (tiles > 3000 || size > 50L * 1024 * 1024) {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    repo.offlineStatus.value = "Map limit reached. Contact map provider."
                } else if (status.isComplete) {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    repo.offlineStatus.value = "Jalalpur map ready offline ✓"
                } else {
                    repo.offlineStatus.value = "Saving city map: $tiles tiles, ${size / 1024} KB"
                }
            }
            override fun onError(error: OfflineRegionError) {
                repo.offlineStatus.value = "Map download error: ${error.message}"
            }
            override fun mapboxTileCountLimitExceeded(limit: Long) {
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                repo.offlineStatus.value = "Offline tile limit exceeded ($limit)"
            }
        })
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
        repo.offlineStatus.value = "Downloading Jalalpur city map…"
    }
}
