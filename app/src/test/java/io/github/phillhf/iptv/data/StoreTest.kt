package io.github.phillhf.iptv.data

import io.github.phillhf.iptv.transfer.FolderStore
import io.github.phillhf.iptv.transfer.LocalServer
import io.github.phillhf.iptv.transfer.SmbStore
import io.github.phillhf.iptv.transfer.Store
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Speicherorte und der kleine Server, der Aufnahmen vom Netzlaufwerk an den Player gibt. */
class StoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val data = ByteArray(700_000) { (it * 31 % 253).toByte() }

    private fun exercise(store: Store) {
        store.delete("a.ts")
        store.output("a.ts", append = false).use { it.write(data, 0, 300_000) }
        store.output("a.ts", append = true).use { it.write(data, 300_000, 400_000) }
        assertEquals(700_000L, store.size("a.ts"))
        assertArrayEquals(data, store.input("a.ts", 0).use { it.readBytes() })
        assertArrayEquals(data.copyOfRange(650_000, 700_000), store.input("a.ts", 650_000).use { it.readBytes() })
        store.output("a.ts", append = false).use { it.write(data, 0, 10) }
        assertEquals(10L, store.size("a.ts"))
        store.rename("a.ts", "b.ts")
        assertEquals(0L, store.size("a.ts"))
        assertEquals(10L, store.size("b.ts"))
        store.delete("b.ts")
        assertEquals(0L, store.size("b.ts"))
    }

    @Test
    fun folder() {
        val store = FolderStore(File(temp.root, "neu/unter"))
        exercise(store)
        assertTrue(store.free() > 0)
    }

    @Test
    fun parseAddresses() {
        val a = SmbStore.parse("smb://fritz.box/FRITZ.NAS/Aufnahmen/TV", "", "")!!
        assertEquals(listOf("fritz.box", "FRITZ.NAS", "Aufnahmen\\TV"), listOf(a.host, a.share, a.folder))
        assertEquals("smb://fritz.box/FRITZ.NAS/Aufnahmen/TV/x.ts", a.location("x.ts"))
        val b = SmbStore.parse("\\\\nas\\videos", "u", "p")!!
        assertEquals(listOf("nas", "videos", ""), listOf(b.host, b.share, b.folder))
        assertEquals(null, SmbStore.parse("smb://nur-server", "", ""))
    }

    @Test
    fun serverSendsRanges() {
        val f = File(temp.root, "film.ts").apply { writeBytes(data) }
        val url = LocalServer.url(f.path)
        fun get(range: String?): Pair<Int, ByteArray> {
            val c = URL(url).openConnection() as HttpURLConnection
            range?.let { c.setRequestProperty("Range", it) }
            return c.responseCode to c.inputStream.use { it.readBytes() }
        }
        assertEquals(200 to data.size, get(null).let { it.first to it.second.size })
        val (code, part) = get("bytes=100000-100099")
        assertEquals(206, code)
        assertArrayEquals(data.copyOfRange(100_000, 100_100), part)
        assertArrayEquals(data.copyOfRange(699_000, 700_000), get("bytes=699000-").second)
        assertArrayEquals(data.copyOfRange(699_990, 700_000), get("bytes=-10").second)
        // Ohne den Schlüssel in der Adresse gibt es nichts
        val other = URL(url.replace(Regex("/[0-9a-f]{32}/"), "/falsch/")).openConnection() as HttpURLConnection
        assertEquals(404, other.responseCode)
    }

    /** Nur mit einem Samba-Server: KLARTV_SMB="smb://127.0.0.1/klartv|Benutzer|Passwort". */
    @Test
    fun smb() {
        val env = System.getenv("KLARTV_SMB").orEmpty()
        assumeTrue("ohne Samba", env.isNotBlank())
        val (address, user, password) = env.split('|')
        val store = SmbStore.parse("$address/Klar TV/Aufnahmen", user, password)!!
        exercise(store)
        assertTrue(store.free() > 0)
        // Aufnahme aufs Netzlaufwerk und über den kleinen Server wieder abspielen
        AppSettings.smbUser = user
        AppSettings.smbPassword = password
        store.output("c.ts", append = false).use { it.write(data) }
        val url = LocalServer.url(store.location("c.ts"))
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Range", "bytes=500000-")
        assertEquals(206, c.responseCode)
        assertArrayEquals(data.copyOfRange(500_000, 700_000), c.inputStream.use { it.readBytes() })
        store.delete("c.ts")
    }
}
