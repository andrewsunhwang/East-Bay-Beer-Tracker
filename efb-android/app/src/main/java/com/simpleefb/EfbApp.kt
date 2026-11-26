package com.simpleefb

import android.app.Application
import android.content.Context
import com.simpleefb.map.TileMath
import com.simpleefb.map.TileStore
import com.simpleefb.position.PositionManager
import com.simpleefb.store.ChartDb
import com.simpleefb.store.ChartRepository
import com.simpleefb.store.Settings
import com.simpleefb.update.UpdateWorker
import com.simpleefb.update.Updater
import org.osmdroid.config.Configuration
import java.io.File

/** Manual dependency container; one per process. */
class AppGraph(context: Context) {
    val settings = Settings(context)
    val db = ChartDb(context)
    val repo = ChartRepository(context, db)
    val tiles = TileStore(db, repo.http)
    val updater = Updater(repo, tiles)
    val position = PositionManager(context, settings)
}

class EfbApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        configureMapCache()
        graph = AppGraph(this)
        UpdateWorker.schedule(this, graph.settings)
        // First launch (or data wiped): fetch the chart index right away.
        if (graph.db.importedCycles().isEmpty()) UpdateWorker.runNow(this)
    }

    fun configureMapCache() {
        Configuration.getInstance().apply {
            load(this@EfbApp, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = ChartRepository.USER_AGENT
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(filesDir, "osmdroid/tiles")
            // Offline regions live in this cache, so allow it to be large before osmdroid trims it.
            tileFileSystemCacheMaxBytes = 16L * 1024 * 1024 * 1024
            tileFileSystemCacheTrimBytes = 15L * 1024 * 1024 * 1024
            // Cached chart tiles expire just after the next 56-day edition starts.
            expirationOverrideDuration = TileMath.expiryMillis() - System.currentTimeMillis()
        }
    }
}
