package com.simpleefb.update

import com.simpleefb.core.Cycles
import com.simpleefb.map.TileStore
import com.simpleefb.store.ChartRepository
import com.simpleefb.store.NotFoundException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

data class UpdateStatus(
    val running: Boolean = false,
    val message: String = "",
    val lastSuccess: Long = 0,
    val lastError: String? = null,
)

/**
 * The auto-update routine (run by WorkManager twice a day and by "Update now"):
 * 1. d-TPP index for the current cycle, and the next cycle once the FAA has posted it;
 * 2. every chart of each favorite airport, for the current and next cycle;
 * 3. Chart Supplement pages for favorite airports (current 56-day edition);
 * 4. offline map regions (re-downloaded when a new chart edition starts);
 * 5. removal of expired data.
 * Everything already on disk is skipped, so an interrupted run simply continues next time.
 */
class Updater(private val repo: ChartRepository, private val tiles: TileStore) {

    private val mutex = Mutex()
    private val _status = MutableStateFlow(
        UpdateStatus(lastSuccess = repo.db.meta(KEY_LAST_SUCCESS)?.toLongOrNull() ?: 0)
    )
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private fun progress(msg: String) {
        _status.value = _status.value.copy(running = true, message = msg)
    }

    suspend fun run(): Boolean = mutex.withLock {
        progress("Checking for updates…")
        val problems = ArrayList<String>()
        try {
            val now = Instant.now()
            val current = Cycles.airacAt(now)
            val next = Cycles.nextAirac(current)
            val cycles = mutableListOf(current)

            // 1. d-TPP indexes
            repo.ensureTppIndex(current, ::progress)
            if (Duration.between(now, next.effectiveInstant).toDays() <= 21) {
                try {
                    repo.ensureTppIndex(next, ::progress)
                    cycles += next
                } catch (e: NotFoundException) {
                    // Not published yet.
                }
            }

            // 2. Favorite airports' charts
            val favorites = repo.db.favorites()
            for (cycle in cycles) {
                for (fav in favorites) {
                    val charts = repo.db.charts(cycle.ident, fav)
                    charts.forEachIndexed { i, chart ->
                        progress("Charts ${cycle.ident}: $fav ${i + 1}/${charts.size}")
                        try {
                            repo.ensureChart(cycle.ident, chart)
                        } catch (e: Exception) {
                            problems += "$fav ${chart.name}: ${e.message}"
                        }
                    }
                }
            }

            // 3. Chart Supplement for favorites
            val edition = Cycles.chartEditionAt(now)
            for (fav in favorites) {
                progress("Chart Supplement: $fav")
                try {
                    repo.ensureSupplement(fav, edition)
                } catch (e: Exception) {
                    problems += "$fav Chart Supplement: ${e.message}"
                }
            }

            // 4. Offline map regions
            val tileMsg = tiles.sync(::progress)
            if (tileMsg.contains("retry")) problems += tileMsg

            // 5. Cleanup: keep only the current and next cycle / current edition.
            repo.pruneTpp(cycles.map { it.ident }.toSet())
            repo.pruneSupplement(edition.ident)

            val t = System.currentTimeMillis()
            repo.db.setMeta(KEY_LAST_SUCCESS, t.toString())
            val summary = buildString {
                append("Up to date: d-TPP ${current.ident}")
                if (cycles.size > 1) append(" (+ ${next.ident} ready)")
                append(", ${favorites.size} favorite airport(s)")
                if (tileMsg.isNotEmpty()) append(". $tileMsg")
                if (problems.isNotEmpty()) append(". ${problems.size} item(s) failed and will be retried")
            }
            _status.value = UpdateStatus(false, summary, t, problems.firstOrNull())
            true
        } catch (e: Exception) {
            _status.value = _status.value.copy(
                running = false,
                message = "Update failed: ${e.message ?: e.javaClass.simpleName}",
                lastError = e.message,
            )
            false
        }
    }

    companion object {
        private const val KEY_LAST_SUCCESS = "last_update_success"
    }
}
