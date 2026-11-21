package io.github.phillhf.iptv

import io.github.phillhf.iptv.data.AccountStatus
import io.github.phillhf.iptv.data.Backend
import io.github.phillhf.iptv.data.Catalog
import io.github.phillhf.iptv.data.Category
import io.github.phillhf.iptv.data.EpgEntry
import io.github.phillhf.iptv.data.Episode
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.SeriesInfo
import io.github.phillhf.iptv.data.Stream
import io.github.phillhf.iptv.data.VodInfo

/** Erfundener Anbieter für Screenshots. Bilder sind „demo://…“-Adressen, die FakeImages zeichnet. */
class DemoBackend : Backend {
    private val start = 1_760_000_000L

    private val channels = listOf(
        "Alpha HD" to "1", "Nord Eins" to "1", "Süd Drei" to "1", "Kanal Plus" to "1", "Doku Welt" to "1", "Kino Plus" to "1",
        "Sport Arena 1" to "2", "Sport Arena 2" to "2", "Motor TV" to "2", "Fußball Live" to "2",
        "Nachrichten 24" to "3", "Welt Info" to "3", "Wirtschaft Aktuell" to "3",
        "Kids Star" to "4", "Toon Time" to "4", "Musik Live" to "5", "Retro Hits" to "5", "Natur Pur" to "1",
    )

    private val shows = listOf(
        "Tagesblick", "Der große Check", "Expedition Nordmeer", "Krimi am Abend", "Live: Bundesliga", "Sportschau kompakt",
        "Wetter und Verkehr", "Die Küchenprofis", "Planet der Wüsten", "Comedy Club", "Late Night Talk", "Spielfilm: Sternenstaub",
    )

    private val movies = listOf(
        "Sternenstaub", "Die letzte Grenze", "Nachtzug nach Lissabon", "Eiskalt", "Das Erbe der Wölfe", "Neonstadt",
        "Sommer in Valencia", "Der stille Zeuge", "Tiefsee", "Code Rot", "Wolkenkratzer", "Die Insel", "Fernweh",
        "Am Ende des Tunnels", "Goldrausch", "Der Uhrmacher", "Funkstille", "Mondlicht", "Gegenwind", "Spurlos",
        "Der Pakt", "Feuerprobe", "Lautlos", "Bergfieber",
    )

    private val series = listOf(
        "Die Akte Nord", "Hafenwache", "Kronen und Klingen", "Stadt der Lichter", "Labor 7", "Familie Sonnenberg",
        "Grenzland", "Der Kurier", "Stille Wasser", "Galaxis", "Notaufnahme", "Wilde Jahre",
    )

    private val genres = listOf("Drama", "Thriller", "Abenteuer", "Komödie", "Science-Fiction", "Krimi", "Action")

    override fun login() = AccountStatus("Active", start * 1000 + 300L * 86_400_000, "2", "0")

    override fun catalog(kind: Kind, liveFormat: String): Catalog = when (kind) {
        Kind.LIVE -> Catalog.build(
            listOf(Category("1", "Unterhaltung"), Category("2", "Sport"), Category("3", "Nachrichten"), Category("4", "Kinder"), Category("5", "Musik")),
            channels.mapIndexed { i, (name, cat) ->
                Stream(Kind.LIVE, "c$i", name, "demo://logo/$name", cat, "http://demo.invalid/live/$i.ts", number = i + 1)
            },
        )
        Kind.MOVIE -> Catalog.build(
            listOf(Category("10", "Neu im Kino"), Category("11", "Thriller"), Category("12", "Drama"), Category("13", "Familie")),
            movies.mapIndexed { i, name ->
                Stream(
                    Kind.MOVIE, "m$i", name, "demo://poster/m$i", (10 + i % 4).toString(), "http://demo.invalid/movie/$i.mkv",
                    rating = "%.1f".format(5.8 + (i * 37 % 35) / 10.0),
                    year = (2026 - i % 9).toString(),
                    added = start + (movies.size - i) * 3600L,
                )
            },
        )
        Kind.SERIES -> Catalog.build(
            listOf(Category("20", "Krimi"), Category("21", "Drama"), Category("22", "Science-Fiction")),
            series.mapIndexed { i, name ->
                Stream(
                    Kind.SERIES, "s$i", name, "demo://poster/s$i", (20 + i % 3).toString(), "",
                    plot = plotFor(name),
                    rating = "%.1f".format(6.5 + (i * 13 % 30) / 10.0),
                    backdrop = "demo://backdrop/s$i",
                    genre = genres[(i + 2) % genres.size],
                    year = (2025 - i % 6).toString(),
                    added = start + (series.size - i) * 7200L,
                )
            },
        )
    }

    private fun plotFor(name: String) =
        "„$name“ erzählt eine Geschichte voller Wendungen: Als ein altes Geheimnis ans Licht kommt, müssen " +
            "alle Beteiligten entscheiden, wem sie noch trauen können. Spannend, atmosphärisch und mit großartigen Bildern."

    override fun vodInfo(id: String): VodInfo {
        val i = id.removePrefix("m").toInt()
        return VodInfo(
            plot = plotFor(movies[i]),
            genre = genres[i % genres.size] + ", " + genres[(i + 3) % genres.size],
            year = (2026 - i % 9).toString(),
            duration = "${1 + i % 2} Std. ${10 + i * 7 % 50} Min.",
            cover = "demo://poster/m$i",
            backdrop = "demo://backdrop/m$i",
            cast = "Lena Berger, Tom Hartmann, Mira Okafor, Jonas Weil",
            director = "Sara Lindqvist",
            rating = "%.1f".format(5.8 + (i * 37 % 35) / 10.0),
        )
    }

    override fun seriesInfo(id: String): SeriesInfo {
        val i = id.removePrefix("s").toInt()
        val seasons = (1..3).associateWith { season ->
            (1..8).map { e ->
                Episode(
                    id = "$id-$season-$e",
                    season = season,
                    number = e,
                    title = listOf("Der Anfang", "Spuren im Schnee", "Doppeltes Spiel", "Nachtschicht", "Die Wahrheit", "Unter Druck", "Verrat", "Finale")[e - 1],
                    url = "http://demo.invalid/series/$id/$season/$e.mkv",
                    plot = "Eine neue Spur führt das Team an einen Ort, an dem nichts so ist, wie es scheint.",
                    image = "demo://backdrop/$id-$season-$e",
                    duration = "${42 + e} Min.",
                )
            }
        }
        return SeriesInfo(
            plot = plotFor(series[i]),
            cover = "demo://poster/$id",
            seasons = seasons,
            backdrop = "demo://backdrop/$id",
            genre = genres[(i + 2) % genres.size],
            cast = "Anna Kessler, David Brandt, Yuki Tanaka",
            director = "Marco Vidal",
            year = (2025 - i % 6).toString(),
        )
    }

    override fun epg(streamId: String): List<EpgEntry> {
        val i = streamId.removePrefix("c").toInt()
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        val begin = now - (hour * ((i * 7) % 10) / 10) - 10 * 60_000
        return (0 until 4).map { k ->
            val s = begin + k * hour
            EpgEntry(
                title = shows[(i + k) % shows.size],
                description = "Aktuelle Ausgabe mit Berichten, Hintergründen und Gästen im Studio. Heute mit einem Schwerpunkt auf den Themen der Woche.",
                start = s,
                end = s + hour,
            )
        }
    }

    override val hasEpg = true
}
