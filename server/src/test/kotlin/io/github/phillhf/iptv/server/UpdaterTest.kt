package io.github.phillhf.iptv.server

import io.github.phillhf.iptv.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class UpdaterTest {
    private fun resource(name: String) = File(javaClass.getResource("/update/$name")!!.toURI())

    /** probe.exe ist mit dem echten Release-Schlüssel signiert (tools/UpdateSign.java). */
    @Test
    fun acceptsOnlySignedFiles() {
        val sig = Base64.getDecoder().decode(resource("probe.exe.sig").readText().trim())
        assertTrue(Updater.verify(resource("probe.exe"), sig))
        val forged = File.createTempFile("probe", ".exe").apply {
            writeBytes(resource("probe.exe").readBytes() + 'x'.code.toByte())
            deleteOnExit()
        }
        assertFalse(Updater.verify(forged, sig))
        assertFalse(Updater.verify(resource("probe.exe"), ByteArray(512)))
    }

    @Test
    fun comparesVersions() {
        assertTrue(Updater.newer("0.9.0", "0.8.1"))
        assertTrue(Updater.newer("0.10.0", "0.9.3"))
        assertTrue(Updater.newer("1.0", "0.99.99"))
        assertFalse(Updater.newer("0.8.1", "0.8.1"))
        assertFalse(Updater.newer("0.8.0", "0.8.1"))
        assertFalse(Updater.newer("0.9.0", "0.9.0-test"))
    }

    @Test
    fun commandKeepsPathsWithSpaces() {
        val cmd = AutoUpdate.setupCommand(File("C:\\ProgramData\\Klar TV Server\\updates\\x.exe"), File("C:\\ProgramData\\Klar TV Server\\logs\\update.log"))
        assertEquals(
            "\\\"C:\\ProgramData\\Klar TV Server\\updates\\x.exe\\\" /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /SP- /LOG=\\\"C:\\ProgramData\\Klar TV Server\\logs\\update.log\\\"",
            cmd,
        )
    }

    /** Echte Aufgabenplanung: nur in der CI, dort laufen die Tests mit Administratorrechten. */
    @Test
    fun scheduledTaskRunsAsSystem() {
        assumeTrue(System.getenv("CI") == "true" && System.getProperty("os.name").startsWith("Windows"))
        val dir = File(System.getProperty("klartv.data"), "mit Leerzeichen").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "out.txt")
        val script = File(dir, "probe.cmd")
        script.writeText("@echo off\r\nwhoami > \"%~dp0out.txt\"\r\necho %* >> \"%~dp0out.txt\"\r\n")
        val task = "Klar TV Server Update Test"
        try {
            assertTrue(AutoUpdate.schedule(AutoUpdate.setupCommand(script, File(dir, "update.log")), File(dir, "schtasks.log"), task))
            val until = System.currentTimeMillis() + 30_000
            while (!(out.exists() && out.readText().contains("/LOG=")) && System.currentTimeMillis() < until) Thread.sleep(500)
            val text = out.takeIf { it.exists() }?.readText().orEmpty()
            assertTrue(text + File(dir, "schtasks.log").takeIf { it.exists() }?.readText(), text.contains("/VERYSILENT") && text.contains("update.log"))
            // „nt authority\system“, auf deutschem Windows „nt-autorität\system“
            assertTrue(text, text.lowercase().contains("\\system"))
        } finally {
            ProcessBuilder("schtasks.exe", "/Delete", "/TN", task, "/F").start().waitFor()
        }
    }
}
