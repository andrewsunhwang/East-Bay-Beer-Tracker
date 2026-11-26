package com.simpleefb.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.simpleefb.core.TppAirport
import com.simpleefb.core.TppAirportCharts
import com.simpleefb.core.TppChart

data class SavedRegion(
    val id: Long,
    val name: String,
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
    val layers: List<String>,
    val tileCount: Int,
)

/** SQLite index of airports/charts (from the d-TPP metafile), favorites, CS page links, regions. */
class ChartDb(context: Context) : SQLiteOpenHelper(context, "charts.sqlite", null, 1) {

    init {
        // WAL lets the UI keep reading while a new cycle is being imported in the background.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE airports (faa_id TEXT PRIMARY KEY, icao_id TEXT, name TEXT, city TEXT,
               state TEXT, military INTEGER)"""
        )
        db.execSQL("CREATE INDEX airports_icao ON airports(icao_id)")
        db.execSQL(
            """CREATE TABLE charts (cycle TEXT NOT NULL, faa_id TEXT NOT NULL, seq INTEGER, code TEXT,
               name TEXT, pdf TEXT, action TEXT, amdt TEXT)"""
        )
        db.execSQL("CREATE INDEX charts_airport ON charts(cycle, faa_id)")
        db.execSQL("CREATE TABLE cycles (cycle TEXT PRIMARY KEY, from_date TEXT, to_date TEXT, imported_at INTEGER)")
        db.execSQL("CREATE TABLE favorites (faa_id TEXT PRIMARY KEY, added_at INTEGER)")
        db.execSQL("CREATE TABLE cs_pages (edition TEXT NOT NULL, ident TEXT NOT NULL, urls TEXT, PRIMARY KEY (edition, ident))")
        db.execSQL(
            """CREATE TABLE regions (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, north REAL, south REAL,
               east REAL, west REAL, layers TEXT, tiles INTEGER)"""
        )
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ------------------------------------------------------------------ d-TPP import

    /** Replaces everything stored for [cycle] with the parsed airports, inside one transaction. */
    fun importCycle(cycle: String, block: ((TppAirportCharts) -> Unit) -> Pair<String, String>) {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            db.delete("charts", "cycle = ?", arrayOf(cycle))
            val apt = db.compileStatement(
                "INSERT OR REPLACE INTO airports (faa_id, icao_id, name, city, state, military) VALUES (?,?,?,?,?,?)"
            )
            val ch = db.compileStatement(
                "INSERT INTO charts (cycle, faa_id, seq, code, name, pdf, action, amdt) VALUES (?,?,?,?,?,?,?,?)"
            )
            val (fromDate, toDate) = block { a ->
                apt.clearBindings()
                apt.bindString(1, a.airport.faaId)
                apt.bindString(2, a.airport.icaoId)
                apt.bindString(3, a.airport.name)
                apt.bindString(4, a.airport.city)
                apt.bindString(5, a.airport.state)
                apt.bindLong(6, if (a.airport.military) 1 else 0)
                apt.executeInsert()
                for (c in a.charts) {
                    ch.clearBindings()
                    ch.bindString(1, cycle)
                    ch.bindString(2, a.airport.faaId)
                    ch.bindLong(3, c.seq.toLong())
                    ch.bindString(4, c.code)
                    ch.bindString(5, c.name)
                    ch.bindString(6, c.pdfName)
                    ch.bindString(7, c.userAction)
                    ch.bindString(8, c.amendment)
                    ch.executeInsert()
                }
            }
            db.insertWithOnConflict("cycles", null, ContentValues().apply {
                put("cycle", cycle)
                put("from_date", fromDate)
                put("to_date", toDate)
                put("imported_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun importedCycles(): List<String> =
        readableDatabase.rawQuery("SELECT cycle FROM cycles ORDER BY cycle", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    fun deleteCycle(cycle: String) {
        writableDatabase.delete("charts", "cycle = ?", arrayOf(cycle))
        writableDatabase.delete("cycles", "cycle = ?", arrayOf(cycle))
    }

    // ------------------------------------------------------------------ queries

    fun airport(faaId: String): TppAirport? =
        readableDatabase.rawQuery(
            "SELECT faa_id, icao_id, name, city, state, military FROM airports WHERE faa_id = ? OR icao_id = ? LIMIT 1",
            arrayOf(faaId.uppercase(), faaId.uppercase()),
        ).use { c -> if (c.moveToFirst()) c.toAirport() else null }

    fun searchAirports(query: String, limit: Int = 60): List<TppAirport> {
        val q = query.trim().uppercase()
        if (q.isEmpty()) return emptyList()
        val like = "%$q%"
        val prefix = "$q%"
        return readableDatabase.rawQuery(
            """SELECT faa_id, icao_id, name, city, state, military FROM airports
               WHERE faa_id LIKE ? OR icao_id LIKE ? OR name LIKE ? OR city LIKE ?
               ORDER BY CASE WHEN faa_id = ? OR icao_id = ? THEN 0
                             WHEN faa_id LIKE ? OR icao_id LIKE ? THEN 1
                             WHEN city LIKE ? THEN 2 ELSE 3 END, faa_id
               LIMIT ?""",
            arrayOf(prefix, prefix, like, like, q, q, prefix, prefix, prefix, limit.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toAirport()) } }
    }

    fun charts(cycle: String, faaId: String): List<TppChart> =
        readableDatabase.rawQuery(
            "SELECT seq, code, name, pdf, action, amdt FROM charts WHERE cycle = ? AND faa_id = ? ORDER BY seq, rowid",
            arrayOf(cycle, faaId),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    TppChart(c.getInt(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))
                )
            }
        }

    fun airportCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM airports", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun android.database.Cursor.toAirport() = TppAirport(
        faaId = getString(0), icaoId = getString(1) ?: "", name = getString(2) ?: "",
        city = getString(3) ?: "", state = getString(4) ?: "", military = getInt(5) == 1,
    )

    // ------------------------------------------------------------------ favorites

    fun favorites(): List<String> =
        readableDatabase.rawQuery("SELECT faa_id FROM favorites ORDER BY faa_id", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    fun setFavorite(faaId: String, favorite: Boolean) {
        if (favorite) {
            writableDatabase.insertWithOnConflict("favorites", null, ContentValues().apply {
                put("faa_id", faaId)
                put("added_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            writableDatabase.delete("favorites", "faa_id = ?", arrayOf(faaId))
        }
    }

    // ------------------------------------------------------------------ Chart Supplement links

    fun csPages(edition: String, ident: String): List<String>? =
        readableDatabase.rawQuery(
            "SELECT urls FROM cs_pages WHERE edition = ? AND ident = ?", arrayOf(edition, ident),
        ).use { c -> if (c.moveToFirst()) c.getString(0).split('\n').filter { it.isNotBlank() } else null }

    fun setCsPages(edition: String, ident: String, urls: List<String>) {
        writableDatabase.insertWithOnConflict("cs_pages", null, ContentValues().apply {
            put("edition", edition)
            put("ident", ident)
            put("urls", urls.joinToString("\n"))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteCsPagesExcept(edition: String) {
        writableDatabase.delete("cs_pages", "edition <> ?", arrayOf(edition))
    }

    // ------------------------------------------------------------------ offline map regions

    fun regions(): List<SavedRegion> =
        readableDatabase.rawQuery("SELECT id, name, north, south, east, west, layers, tiles FROM regions ORDER BY id", null)
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        SavedRegion(
                            c.getLong(0), c.getString(1), c.getDouble(2), c.getDouble(3), c.getDouble(4),
                            c.getDouble(5), c.getString(6).split(',').filter { it.isNotBlank() }, c.getInt(7),
                        )
                    )
                }
            }

    fun addRegion(r: SavedRegion): Long =
        writableDatabase.insert("regions", null, ContentValues().apply {
            put("name", r.name)
            put("north", r.north)
            put("south", r.south)
            put("east", r.east)
            put("west", r.west)
            put("layers", r.layers.joinToString(","))
            put("tiles", r.tileCount)
        })

    fun deleteRegion(id: Long) {
        writableDatabase.delete("regions", "id = ?", arrayOf(id.toString()))
    }

    // ------------------------------------------------------------------ meta

    fun meta(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun setMeta(key: String, value: String?) {
        if (value == null) {
            writableDatabase.delete("meta", "key = ?", arrayOf(key))
        } else {
            writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply {
                put("key", key)
                put("value", value)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }
}
