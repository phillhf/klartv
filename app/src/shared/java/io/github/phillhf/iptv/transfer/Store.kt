package io.github.phillhf.iptv.transfer

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.t
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** Wo Aufnahmen und Downloads liegen: ein Ordner (auch USB-Stick oder verbundenes Laufwerk) oder ein Netzlaufwerk. */
interface Store {
    /** 0, wenn es die Datei nicht gibt. */
    fun size(name: String): Long
    fun output(name: String, append: Boolean): OutputStream
    fun input(name: String, offset: Long): InputStream
    fun rename(from: String, to: String)
    fun delete(name: String)

    /** Freier Platz in Byte, 0 = unbekannt. */
    fun free(): Long
}

class FolderStore(private val dir: File) : Store {
    private fun f(name: String) = File(dir, name)

    override fun size(name: String) = f(name).length()

    override fun output(name: String, append: Boolean): OutputStream {
        dir.mkdirs()
        return FileOutputStream(f(name), append)
    }

    override fun input(name: String, offset: Long): InputStream = FileInputStream(f(name)).also { it.channel.position(offset) }

    override fun rename(from: String, to: String) {
        if (!f(from).renameTo(f(to))) throw IOException(t("Datei konnte nicht gespeichert werden."))
    }

    override fun delete(name: String) {
        f(name).delete()
    }

    override fun free() = dir.absoluteFile.let { d -> generateSequence(d) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: 0L }
}

/** Freigabe im Netz (SMB 2/3), z. B. Fritzbox, NAS oder ein Windows-Ordner. */
class SmbStore(val host: String, val share: String, val folder: String, private val user: String, private val password: String) : Store {
    private class Open(val connection: Connection, val session: Session, val disk: DiskShare) : Closeable {
        override fun close() {
            runCatching { disk.close() }
            runCatching { session.close() }
            runCatching { connection.close() }
        }
    }

    private fun open(): Open {
        val connection = client.connect(host)
        try {
            val auth = if (user.isBlank()) {
                AuthenticationContext.guest()
            } else {
                val domain = user.substringBefore('\\', "")
                AuthenticationContext(user.substringAfter('\\'), password.toCharArray(), domain.ifEmpty { null })
            }
            val session = connection.authenticate(auth)
            val disk = session.connectShare(share) as? DiskShare ?: throw IOException(t("„%1“ ist keine Ordnerfreigabe.", share))
            return Open(connection, session, disk)
        } catch (e: Exception) {
            connection.close()
            throw e as? IOException ?: IOException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    private fun path(name: String) = if (folder.isEmpty()) name else "$folder\\$name"

    private fun <T> use(block: (DiskShare) -> T): T = open().use { block(it.disk) }

    private fun makeFolder(disk: DiskShare) {
        var p = ""
        for (part in folder.split('\\').filter { it.isNotEmpty() }) {
            p = if (p.isEmpty()) part else "$p\\$part"
            if (!disk.folderExists(p)) disk.mkdir(p)
        }
    }

    override fun size(name: String): Long = use { d ->
        if (d.fileExists(path(name))) d.getFileInformation(path(name)).standardInformation.endOfFile else 0L
    }

    override fun output(name: String, append: Boolean): OutputStream {
        val o = open()
        try {
            makeFolder(o.disk)
            val file = o.disk.openFile(
                path(name),
                EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                if (append) SMB2CreateDisposition.FILE_OPEN_IF else SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            )
            val out = file.getOutputStream(append)
            return object : OutputStream() {
                override fun write(b: Int) = out.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                override fun flush() = out.flush()
                override fun close() {
                    try {
                        out.close()
                    } finally {
                        runCatching { file.close() }
                        o.close()
                    }
                }
            }
        } catch (e: Exception) {
            o.close()
            throw e as? IOException ?: IOException(e.message, e)
        }
    }

    override fun input(name: String, offset: Long): InputStream {
        val o = open()
        try {
            val file = o.disk.openFile(
                path(name), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            )
            return object : InputStream() {
                private var at = offset
                private val one = ByteArray(1)

                override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (len == 0) return 0
                    val n = file.read(b, at, off, minOf(len, 1 shl 20))
                    if (n <= 0) return -1
                    at += n
                    return n
                }

                override fun close() {
                    runCatching { file.close() }
                    o.close()
                }
            }
        } catch (e: Exception) {
            o.close()
            throw e as? IOException ?: IOException(e.message, e)
        }
    }

    override fun rename(from: String, to: String) = use { d ->
        if (d.fileExists(path(to))) d.rm(path(to))
        d.openFile(
            path(from), EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        ).use { it.rename(path(to)) }
    }

    override fun delete(name: String) = use { d -> if (d.fileExists(path(name))) d.rm(path(name)) }

    override fun free(): Long = runCatching { use { it.shareInformation.freeSpace } }.getOrDefault(0L)

    /** Adresse ohne Zugangsdaten, so steht sie beim Eintrag. */
    fun location(name: String) = "smb://$host/$share/" + (if (folder.isEmpty()) "" else folder.replace('\\', '/') + "/") + name

    companion object {
        private val client by lazy {
            SMBClient(SmbConfig.builder().withTimeout(30, TimeUnit.SECONDS).withSoTimeout(60, TimeUnit.SECONDS).build())
        }

        /** Liest „smb://server/freigabe/ordner“ oder „\\server\freigabe\ordner“. */
        fun parse(address: String, user: String, password: String): SmbStore? {
            val clean = address.trim().replace('\\', '/').removePrefix("smb:").trimStart('/')
            val parts = clean.split('/').filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            return SmbStore(parts[0], parts[1], parts.drop(2).joinToString("\\"), user.trim(), password)
        }
    }
}

/** Wählt den Speicher zu einem Eintrag und den für neue Aufnahmen. */
object Stores {
    /** Speicher und Dateiname zu dem, was bei einem Eintrag als Ort steht. */
    fun resolve(location: String): Pair<Store, String> {
        if (location.startsWith("smb://")) {
            val name = location.substringAfterLast('/')
            val store = SmbStore.parse(location.substringBeforeLast('/'), AppSettings.smbUser, AppSettings.smbPassword)
                ?: throw IOException(t("Netzlaufwerk nicht erreichbar."))
            return store to name
        }
        val f = File(location)
        return FolderStore(f.absoluteFile.parentFile ?: File(".")) to f.name
    }

    /** Ort für eine neue Datei. [local] ist der Standardordner auf dem Gerät. */
    fun newLocation(local: File, name: String): String {
        val target = AppSettings.storeTarget
        return when {
            target == "smb" -> SmbStore.parse(AppSettings.smbPath, AppSettings.smbUser, AppSettings.smbPassword)?.location(name)
                ?: File(local, name).path
            target.startsWith("file:") -> File(target.removePrefix("file:"), name).path
            else -> File(local, name).path
        }
    }

    /** Schreibt und löscht eine kleine Datei am gewählten Ort. null = in Ordnung, sonst die Fehlermeldung. */
    fun check(local: File): String? = runCatching {
        val (store, _) = resolve(newLocation(local, "klartv-test.txt"))
        store.output("klartv-test.txt", false).use { it.write("Klar TV".toByteArray()) }
        val size = store.size("klartv-test.txt")
        store.delete("klartv-test.txt")
        if (size != 7L) throw IOException(t("Schreiben hat nicht geklappt."))
        null
    }.getOrElse { it.message ?: it.javaClass.simpleName }

    /** Adresse zum Abspielen: Dateien direkt, alles im Netz über den kleinen Server in der App. */
    fun playUrl(location: String): String = if (location.startsWith("smb://")) {
        LocalServer.url(location)
    } else {
        File(location).toURI().toASCIIString().replaceFirst("file:/", "file:///")
    }
}
