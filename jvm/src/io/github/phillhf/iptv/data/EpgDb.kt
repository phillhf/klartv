package io.github.phillhf.iptv.data

import io.github.phillhf.iptv.platform.AppContext
import java.io.File
import java.io.InputStream
import java.sql.Connection
import java.sql.DriverManager

/** Programmführer auf dem Gerät, aus XMLTV eingelesen. Getrennt nach Anbieter. Gegenstück zur Android-Fassung. */
class EpgDb(context: AppContext) {
    private val url: String
    private val reader: Connection by lazy { connect() }

    init {
        System.setProperty("org.sqlite.tmpdir", File(context.dataDir, "native").apply { mkdirs() }.path)
        url = "jdbc:sqlite:" + File(context.dataDir, "epg.db").path
        connect().use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE IF NOT EXISTS prog (acc TEXT, ch TEXT, start INTEGER, stop INTEGER, title TEXT, descr TEXT)")
                s.execute("CREATE INDEX IF NOT EXISTS prog_idx ON prog (acc, ch, start)")
                s.execute("CREATE TABLE IF NOT EXISTS info (acc TEXT PRIMARY KEY, time INTEGER, count INTEGER)")
            }
        }
    }

    private fun connect(): Connection = DriverManager.getConnection(url).also { c ->
        c.createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("PRAGMA busy_timeout=10000")
        }
    }

    data class Info(val time: Long, val count: Int)

    @Synchronized
    fun info(account: String): Info? =
        reader.prepareStatement("SELECT time, count FROM info WHERE acc = ?").use { s ->
            s.setString(1, account)
            s.executeQuery().use { if (it.next()) Info(it.getLong(1), it.getInt(2)) else null }
        }

    /** Wie auf Android: Bricht das Einlesen ab, bleibt das alte Programm stehen. */
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
        // Kennen mehrere Quellen einen Sender, gilt die erste
        val owner = HashMap<String, Int>()
        var count = 0
        var failed: Exception? = null
        var worked = 0
        connect().use { db ->
            db.autoCommit = false
            try {
                db.prepareStatement("DELETE FROM prog WHERE acc = ?").use {
                    it.setString(1, account)
                    it.executeUpdate()
                }
                db.prepareStatement("INSERT INTO prog VALUES (?, ?, ?, ?, ?, ?)").use { insert ->
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
                                                insert.setString(1, account)
                                                insert.setString(2, key)
                                                insert.setLong(3, p.start)
                                                insert.setLong(4, p.stop)
                                                insert.setString(5, p.title)
                                                insert.setString(6, p.description)
                                                insert.executeUpdate()
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
                }
                db.prepareStatement("INSERT OR REPLACE INTO info VALUES (?, ?, ?)").use {
                    it.setString(1, account)
                    it.setLong(2, System.currentTimeMillis())
                    it.setInt(3, count)
                    it.executeUpdate()
                }
                db.commit()
            } catch (e: Throwable) {
                db.rollback()
                throw e
            }
        }
        return count
    }

    @Synchronized
    fun programmes(account: String, channel: String, from: Long, to: Long): List<EpgEntry> =
        reader.prepareStatement("SELECT title, descr, start, stop FROM prog WHERE acc = ? AND ch = ? AND stop > ? AND start < ? ORDER BY start").use { s ->
            s.setString(1, account)
            s.setString(2, channel)
            s.setLong(3, from)
            s.setLong(4, to)
            s.executeQuery().use { c ->
                val out = ArrayList<EpgEntry>()
                while (c.next()) {
                    val e = EpgEntry(c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3), c.getLong(4))
                    // Manche Dateien enthalten dieselbe Sendung doppelt
                    if (out.lastOrNull()?.start != e.start) out += e
                }
                out
            }
        }

    @Synchronized
    fun remove(account: String) {
        connect().use { db ->
            listOf("DELETE FROM prog WHERE acc = ?", "DELETE FROM info WHERE acc = ?").forEach { sql ->
                db.prepareStatement(sql).use {
                    it.setString(1, account)
                    it.executeUpdate()
                }
            }
        }
    }
}
