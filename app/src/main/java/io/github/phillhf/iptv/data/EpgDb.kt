package io.github.phillhf.iptv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.InputStream

/** Programmführer auf dem Gerät, aus XMLTV eingelesen. Getrennt nach Anbieter. */
class EpgDb(context: Context) : SQLiteOpenHelper(context, "epg.db", null, 1) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE prog (acc TEXT, ch TEXT, start INTEGER, stop INTEGER, title TEXT, descr TEXT)")
        db.execSQL("CREATE INDEX prog_idx ON prog (acc, ch, start)")
        db.execSQL("CREATE TABLE info (acc TEXT PRIMARY KEY, time INTEGER, count INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS prog")
        db.execSQL("DROP TABLE IF EXISTS info")
        onCreate(db)
    }

    data class Info(val time: Long, val count: Int)

    fun info(account: String): Info? =
        readableDatabase.rawQuery("SELECT time, count FROM info WHERE acc = ?", arrayOf(account)).use {
            if (it.moveToFirst()) Info(it.getLong(0), it.getInt(1)) else null
        }

    /**
     * Ersetzt das Programm eines Anbieters. [keyFor] sagt, unter welchem Schlüssel ein XMLTV-Sender
     * abgelegt wird; null heißt: gehört zu keinem Sender des Anbieters und wird übersprungen.
     * Bricht das Einlesen ab, bleibt das alte Programm stehen.
     */
    fun import(
        account: String,
        sources: List<() -> InputStream>,
        from: Long,
        to: Long,
        keyFor: (id: String, names: List<String>) -> List<String>,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
        onSourceFailed: (index: Int, error: Exception) -> Unit = { _, _ -> },
    ): Int {
        val db = writableDatabase
        // Kennen mehrere Quellen einen Sender, gilt die erste
        val owner = HashMap<String, Int>()
        var count = 0
        var failed: Exception? = null
        var worked = 0
        db.beginTransactionNonExclusive()
        try {
            db.delete("prog", "acc = ?", arrayOf(account))
            val insert = db.compileStatement("INSERT INTO prog VALUES (?, ?, ?, ?, ?, ?)")
            sources.forEachIndexed { index, source ->
                val names = HashMap<String, MutableList<String>>()
                val keys = HashMap<String, List<String>>()
                try {
                    source().use { input ->
                        Xmltv.parse(
                            input,
                            onChannel = { id, name -> names.getOrPut(id) { ArrayList(1) }.add(name) },
                            onProgramme = { p ->
                                if (isCancelled()) throw InterruptedException()
                                if (p.stop >= from && p.start <= to) {
                                    val targets = keys.getOrPut(p.channel) {
                                        keyFor(p.channel, names[p.channel].orEmpty()).filter { owner.getOrPut(it) { index } == index }
                                    }
                                    for (key in targets) {
                                        insert.bindString(1, account)
                                        insert.bindString(2, key)
                                        insert.bindLong(3, p.start)
                                        insert.bindLong(4, p.stop)
                                        insert.bindString(5, p.title)
                                        insert.bindString(6, p.description)
                                        insert.executeInsert()
                                        if (++count % 2000 == 0) onProgress(count)
                                    }
                                }
                            },
                        )
                    }
                    worked++
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    if (isCancelled()) throw InterruptedException()
                    failed = e
                    onSourceFailed(index, e)
                }
            }
            // Keine Quelle ging: Das alte Programm bleibt stehen
            if (worked == 0) failed?.let { throw it }
            db.insertWithOnConflict(
                "info",
                null,
                ContentValues().apply {
                    put("acc", account)
                    put("time", System.currentTimeMillis())
                    put("count", count)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    fun programmes(account: String, channel: String, from: Long, to: Long): List<EpgEntry> =
        readableDatabase.rawQuery(
            "SELECT title, descr, start, stop FROM prog WHERE acc = ? AND ch = ? AND stop > ? AND start < ? ORDER BY start",
            arrayOf(account, channel, from.toString(), to.toString()),
        ).use { c ->
            val out = ArrayList<EpgEntry>(c.count)
            while (c.moveToNext()) {
                val e = EpgEntry(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3))
                // Manche Dateien enthalten dieselbe Sendung doppelt
                if (out.lastOrNull()?.start != e.start) out += e
            }
            out
        }

    fun remove(account: String) {
        writableDatabase.delete("prog", "acc = ?", arrayOf(account))
        writableDatabase.delete("info", "acc = ?", arrayOf(account))
    }
}
