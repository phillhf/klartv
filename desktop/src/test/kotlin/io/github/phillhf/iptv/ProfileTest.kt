package io.github.phillhf.iptv

import io.github.phillhf.iptv.data.Account
import io.github.phillhf.iptv.data.AccountStore
import io.github.phillhf.iptv.data.Custom
import io.github.phillhf.iptv.data.Library
import io.github.phillhf.iptv.data.Profile
import io.github.phillhf.iptv.data.ProfileStore
import io.github.phillhf.iptv.data.SourceType
import io.github.phillhf.iptv.platform.AppContext
import io.github.phillhf.iptv.sync.SyncData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Profile: Persönliches getrennt, Sperren und Senderordnung gemeinsam, alles im Abgleich dabei. */
class ProfileTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun personalDataIsSeparate() {
        val context = AppContext(temp.newFolder("a"))
        val store = ProfileStore(context)
        assertEquals(listOf(Profile("", "")), store.all())
        store.save(store.all() + Profile("kind", "Lena", 2))
        val main = Library(context, "acc")
        val kid = Library(context, "acc", "kind")
        main.toggleFavorite("LIVE:1")
        main.setWatched(listOf("MOVIE:5"), true)
        kid.toggleFavorite("LIVE:2")
        kid.savePosition("MOVIE:7", 42_000)
        assertEquals(listOf("LIVE:1"), main.favorites())
        assertEquals(listOf("LIVE:2"), kid.favorites())
        assertTrue(kid.watched().isEmpty())
        assertEquals(0L, main.position("MOVIE:7"))
        // Gemeinsam: Sperren und eigene Senderordnung
        main.saveLockedCategories(setOf("LIVE:9"))
        kid.saveCustom(Custom(hiddenStreams = setOf("LIVE:3")))
        assertEquals(setOf("LIVE:9"), kid.lockedCategories())
        assertEquals(setOf("LIVE:3"), main.custom().hiddenStreams)
        // Das erste Profil liest dieselben Daten wie vor den Profilen
        assertEquals(listOf("LIVE:1"), Library(AppContext(context.dataDir), "acc", "").favorites())
        // Gewähltes Profil merkt sich das Gerät; ein gelöschtes fällt auf das erste zurück
        store.activeId = "kind"
        assertEquals("kind", ProfileStore(AppContext(context.dataDir)).activeId)
        store.save(listOf(Profile("", "")))
        assertEquals("", store.activeId)
    }

    @Test
    fun syncCarriesProfiles() {
        val phone = AppContext(temp.newFolder("handy"))
        AccountStore(phone).save(listOf(Account("acc", "Anbieter", SourceType.M3U, m3uUrl = "http://x/list.m3u")))
        ProfileStore(phone).save(listOf(Profile("", "Papa"), Profile("kind", "Lena", 2)))
        Library(phone, "acc", "kind").toggleFavorite("LIVE:2")
        Library(phone, "acc").toggleFavorite("LIVE:1")

        val tv = AppContext(temp.newFolder("tv"))
        SyncData.import(tv, SyncData.export(phone, "Handy"))
        assertEquals(listOf("Papa", "Lena"), ProfileStore(tv).all().map { it.name })
        assertEquals(listOf("LIVE:2"), Library(tv, "acc", "kind").favorites())
        assertEquals(listOf("LIVE:1"), Library(tv, "acc").favorites())
        // Welches Profil das Gerät nutzt, wird nicht mit übertragen
        assertEquals("", ProfileStore(tv).activeId)
    }
}
