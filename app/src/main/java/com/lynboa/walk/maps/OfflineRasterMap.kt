package com.lynboa.walk.maps

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Offline maps for the WORKING osmdroid renderer.
 *
 * Import a legally obtained raster MBTiles file. Vector (PBF) MBTiles cannot
 * be displayed by osmdroid and are rejected explicitly.
 * We never bulk-download from tile.openstreetmap.org (prohibited by OSMF).
 */
object OfflineRasterMap {
    private const val MAX_BYTES = 350L * 1024L * 1024L

    fun archive(context: Context): File =
        File(context.filesDir, "boali-map/street-offline.mbtiles")

    fun installed(context: Context): File? = archive(context).takeIf { it.isFile }

    fun import(context: Context, uri: Uri): String {
        val destination = archive(context)
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, "street-offline.tmp")
        temp.delete()
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Unable to open selected offline map file")
            var copied = 0L
            input.use { source ->
                FileOutputStream(temp).use { target ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        copied += n
                        if (copied > MAX_BYTES) error("MBTiles file exceeds 350 MB safety limit")
                        target.write(buffer, 0, n)
                    }
                    target.fd.sync()
                }
            }
            if (copied < 4096) error("Selected map file is too small")
            val db = SQLiteDatabase.openDatabase(
                temp.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val tileCount: Long
            try {
                val hasTiles = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='tiles'",
                    null).use { it.moveToFirst() }
                if (!hasTiles) error("Not an MBTiles file: no tiles table")
                val format = runCatching {
                    db.rawQuery("SELECT value FROM metadata WHERE name='format'", null).use {
                        if (it.moveToFirst()) it.getString(0).lowercase() else ""
                    }
                }.getOrDefault("")
                if (format == "pbf" || format == "mvt") {
                    error("This is a vector MBTiles package. Please import raster PNG/JPEG MBTiles.")
                }
                tileCount = db.rawQuery("SELECT COUNT(*) FROM tiles", null).use {
                    if (!it.moveToFirst()) 0L else it.getLong(0)
                }
                if (tileCount == 0L) error("This map package contains no tiles")
            } finally {
                db.close()
            }
            // Replace only after validating a fully copied database.
            if (destination.exists() && !destination.delete()) {
                error("Cannot replace old offline map")
            }
            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
                temp.delete()
            }
            return "Offline street map imported · $tileCount tiles"
        } finally {
            temp.delete()
        }
    }
}
