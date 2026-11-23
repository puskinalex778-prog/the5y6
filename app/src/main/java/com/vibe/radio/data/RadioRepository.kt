package com.vibe.radio.data

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Один поток станции (у многих станций есть несколько потоков с разным битрейтом). */
data class StreamVariant(val url: String, val bitrate: Int, val codec: String)

data class Station(
    val id: String,
    val name: String,
    val url: String,
    val favicon: String = "",
    val country: String = "",
    val tags: String = "",
    val bitrate: Int = 0,
    val codec: String = "",
    val variants: List<StreamVariant> = emptyList(),
) {
    val allVariants: List<StreamVariant>
        get() = variants.ifEmpty { listOf(StreamVariant(url, bitrate, codec)) }

    fun withVariant(v: StreamVariant) = copy(url = v.url, bitrate = v.bitrate, codec = v.codec)
}

enum class Region(val code: String, val title: String, val flag: String) {
    RU("RU", "Россия", "🇷🇺"),
    US("US", "США", "🇺🇸"),
    KR("KR", "Корея", "🇰🇷"),
    DE("DE", "Германия", "🇩🇪"),
    BY("BY", "Беларусь", "🇧🇾"),
    KZ("KZ", "Казахстан", "🇰🇿"),
    UA("UA", "Украина", "🇺🇦"),
    GB("GB", "Британия", "🇬🇧"),
    FR("FR", "Франция", "🇫🇷"),
    IT("IT", "Италия", "🇮🇹"),
    ES("ES", "Испания", "🇪🇸"),
    TR("TR", "Турция", "🇹🇷"),
    JP("JP", "Япония", "🇯🇵"),
    BR("BR", "Бразилия", "🇧🇷"),
}

/**
 * Станции берутся из открытого каталога Radio Browser. Потоки одной станции
 * с разным битрейтом объединяются, чтобы можно было выбрать качество.
 */
class RadioRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("vibe", Context.MODE_PRIVATE)

    private val servers = listOf(
        "https://de1.api.radio-browser.info",
        "https://fi1.api.radio-browser.info",
        "https://de2.api.radio-browser.info",
        "https://all.api.radio-browser.info",
    )

    suspend fun byRegion(code: String): List<Station> = withContext(Dispatchers.IO) {
        val path = "/json/stations/search?countrycode=$code" +
            "&order=clickcount&reverse=true&limit=250&hidebroken=true"
        val list = fetch(path)
        list.ifEmpty { fallback[code].orEmpty() }
    }

    suspend fun search(query: String): List<Station> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val codes = AppSettings.regions
        fetch("/json/stations/search?name=$q&order=clickcount&reverse=true&limit=150&hidebroken=true")
            .sortedByDescending { st -> st.country.uppercase() in codes }
    }

    private fun normName(name: String): String = name.lowercase()
        .replace(Regex("\\d{2,3}\\s*(k|kb|kbps|kbit|кбит)\\b"), " ")
        .replace(Regex("\\b(hq|lq|hd|high|low|mp3|aac|aacp|ogg|opus)\\b"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun fetch(path: String): List<Station> {
        for (base in servers) {
            try {
                val conn = URL(base + path).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "PLAYpay/2.0")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val arr = JSONArray(body)

                val groups = LinkedHashMap<String, MutableList<JSONObject>>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val url = o.optString("url_resolved").ifBlank { o.optString("url") }
                    val name = o.optString("name").trim()
                    if (url.isBlank() || name.isBlank()) continue
                    val key = o.optString("countrycode") + "|" + normName(name).ifBlank { name.lowercase() }
                    groups.getOrPut(key) { ArrayList() }.add(o)
                }

                val out = groups.values.map { list ->
                    val first = list.first()
                    val variants = list.map { o ->
                        StreamVariant(
                            url = o.optString("url_resolved").ifBlank { o.optString("url") },
                            bitrate = o.optInt("bitrate"),
                            codec = o.optString("codec"),
                        )
                    }.distinctBy { it.url }.sortedBy { it.bitrate }
                    val st = Station(
                        id = first.optString("stationuuid", variants.first().url),
                        name = first.optString("name").trim(),
                        url = variants.first().url,
                        favicon = list.firstNotNullOfOrNull { o -> o.optString("favicon").takeIf { it.isNotBlank() } } ?: "",
                        country = first.optString("countrycode"),
                        tags = first.optString("tags").split(",").filter { it.isNotBlank() }.take(3).joinToString(" · "),
                        variants = variants,
                    )
                    applyQuality(st)
                }
                if (out.isNotEmpty()) return out
            } catch (_: Exception) {
                // пробуем следующий сервер
            }
        }
        return emptyList()
    }

    // ---------- Выбор битрейта ----------
    fun isMetered(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.isActiveNetworkMetered
    } catch (_: Exception) {
        false
    }

    /** Максимальный битрейт (kbps) по текущим настройкам, Int.MAX_VALUE — без ограничений. */
    fun bitrateLimit(): Int {
        val metered = isMetered()
        return when {
            AppSettings.saveDataOnMobile && metered -> 64
            AppSettings.radioQuality == RadioQuality.LOW -> 64
            AppSettings.radioQuality == RadioQuality.MEDIUM -> 128
            AppSettings.radioQuality == RadioQuality.AUTO -> if (metered) 128 else Int.MAX_VALUE
            else -> Int.MAX_VALUE
        }
    }

    fun applyQuality(st: Station, limit: Int = bitrateLimit()): Station {
        val vars = st.allVariants
        if (vars.size <= 1) return st
        val known = vars.filter { it.bitrate > 0 }.sortedBy { it.bitrate }
        if (known.isEmpty()) return st.withVariant(vars.first())
        val pick = known.lastOrNull { it.bitrate <= limit } ?: known.first()
        return st.withVariant(pick)
    }

    // ---------- Избранное ----------
    fun favorites(): List<Station> {
        val raw = prefs.getString("favs", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val va = o.optJSONArray("variants")
                val variants = if (va == null) emptyList() else (0 until va.length()).map { j ->
                    val v = va.getJSONObject(j)
                    StreamVariant(v.getString("url"), v.optInt("bitrate"), v.optString("codec"))
                }
                Station(
                    id = o.getString("id"), name = o.getString("name"), url = o.getString("url"),
                    favicon = o.optString("favicon"), country = o.optString("country"),
                    tags = o.optString("tags"), bitrate = o.optInt("bitrate"), codec = o.optString("codec"),
                    variants = variants,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveFavorites(list: List<Station>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("name", s.name); put("url", s.url); put("favicon", s.favicon)
                put("country", s.country); put("tags", s.tags); put("bitrate", s.bitrate); put("codec", s.codec)
                put("variants", JSONArray().apply {
                    s.variants.forEach { v ->
                        put(JSONObject().apply { put("url", v.url); put("bitrate", v.bitrate); put("codec", v.codec) })
                    }
                })
            })
        }
        prefs.edit().putString("favs", arr.toString()).apply()
    }

    private val fallback = mapOf(
        "RU" to listOf(
            Station("fb-ru-1", "Европа Плюс", "https://ep256.hostingradio.ru:8052/europaplus256.mp3", country = "RU", tags = "pop", bitrate = 256),
            Station("fb-ru-2", "Русское Радио", "https://rusradio.hostingradio.ru/rusradio128.mp3", country = "RU", tags = "pop · russian", bitrate = 128),
            Station(
                "fb-ru-3", "Radio Record", "https://radiorecord.hostingradio.ru/rr_main96.aacp", country = "RU", tags = "dance · edm",
                variants = listOf(
                    StreamVariant("https://radiorecord.hostingradio.ru/rr_main32.aacp", 32, "AAC+"),
                    StreamVariant("https://radiorecord.hostingradio.ru/rr_main96.aacp", 96, "AAC+"),
                    StreamVariant("https://radiorecord.hostingradio.ru/rr_main320.mp3", 320, "MP3"),
                ),
            ),
        ),
        "US" to listOf(
            Station(
                "fb-us-1", "KEXP 90.3 Seattle", "https://kexp-mp3-128.streamguys1.com/kexp128.mp3", country = "US", tags = "indie · rock",
                variants = listOf(
                    StreamVariant("https://kexp-mp3-128.streamguys1.com/kexp128.mp3", 128, "MP3"),
                    StreamVariant("https://kexp-aacPlus-64.streamguys1.com/kexp64.aac", 64, "AAC+"),
                ).sortedBy { it.bitrate },
            ),
            Station(
                "fb-us-2", "SomaFM Groove Salad", "https://ice1.somafm.com/groovesalad-128-mp3", country = "US", tags = "chill · ambient",
                variants = listOf(
                    StreamVariant("https://ice1.somafm.com/groovesalad-64-aac", 64, "AAC"),
                    StreamVariant("https://ice1.somafm.com/groovesalad-128-mp3", 128, "MP3"),
                    StreamVariant("https://ice1.somafm.com/groovesalad-256-mp3", 256, "MP3"),
                ),
            ),
            Station("fb-us-3", "SomaFM Drone Zone", "https://ice1.somafm.com/dronezone-128-mp3", country = "US", tags = "ambient", bitrate = 128),
        ),
        "DE" to listOf(
            Station("fb-de-1", "1LIVE", "https://wdr-1live-live.icecastssl.wdr.de/wdr/1live/live/mp3/128/stream.mp3", country = "DE", tags = "pop", bitrate = 128),
            Station("fb-de-2", "Deutschlandfunk", "https://st01.sslstream.dlf.de/dlf/01/128/mp3/stream.mp3", country = "DE", tags = "news", bitrate = 128),
            Station("fb-de-3", "SWR3", "https://liveradio.swr.de/sw282p3/swr3/play.mp3", country = "DE", tags = "pop · rock"),
        ),
    )
}
