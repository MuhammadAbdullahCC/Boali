package com.lynboa.walk.maps

import android.content.Context
import com.lynboa.walk.data.FitnessRepository
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/** One modest city download. Map data © OpenStreetMap contributors. */
object OfflineCityMap {
    fun download(context: Context, repository: FitnessRepository) {
        val manager = OfflineManager.getInstance(context)
        repository.offlineStatus.value = "Checking existing offline map…"
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val existing = offlineRegions?.firstOrNull {
                    String(it.metadata, Charsets.UTF_8) == "lynboa-jalalpur-v1"
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
                } else create(manager, repository)
            }
            override fun onError(error: String) {
                repository.offlineStatus.value = "Offline map lookup failed: $error"
            }
        })
    }

    private fun create(manager: OfflineManager, repo: FitnessRepository) {
        // ~8 km × 6 km, zoom 12..15. Offline map tile quota: <=3000 tiles / <=50 MB.
        val bounds = LatLngBounds.Builder()
            .include(LatLng(32.035, 73.335))
            .include(LatLng(32.090, 73.420)).build()
        val definition = OfflineTilePyramidRegionDefinition(MapConfig.STYLE,
            bounds, 12.0, 15.0, 1f)
        manager.createOfflineRegion(definition, "lynboa-jalalpur-v1".toByteArray(),
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
