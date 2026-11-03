package com.deep.indiapulse

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Hourly background check. Reads the config the page saved (followed queries, watch terms, mute terms, alert mode),
 * fetches the feeds, and notifies for NEW stories that are on the watchlist or look high-impact.
 * The very first run only records a baseline so you are not flooded with old stories.
 */
class AlertWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {

    private data class Story(val title: String, val link: String, val source: String, val ts: Long)

    private fun strings(a: org.json.JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }

    private fun termsRegex(t: List<String>): Regex? =
        if (t.isEmpty()) null else Regex("\\b(?:" + t.joinToString("|") { Regex.escape(it.trim()) } + ")\\b", RegexOption.IGNORE_CASE)

    private val important = Regex(
        "\\b(rbi|repo|mpc|budget|gdp|cpi|inflation|fed|fomc|tariffs?|crude|brent|rupee|sebi|fiscal|gst|trade deal|war|sanctions?|downgrade|upgrade|default|crash|record|plunge|surge|ceasefire|opec)\\b",
        RegexOption.IGNORE_CASE
    )

    private fun tag(block: String, name: String): String {
        val m = Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(block) ?: return ""
        return m.groupValues[1].replace(Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL), "$1")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").trim()
    }

    private fun fetch(query: String): List<Story> {
        val url = "https://news.google.com/rss/search?q=" + URLEncoder.encode("$query when:1d", "UTF-8") + "&hl=en-IN&gl=IN&ceid=IN:en"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) IndiaPulse")
        if (c.responseCode !in 200..299) return emptyList()
        val xml = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH)
        return Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val b = m.groupValues[1]
            var title = tag(b, "title"); val link = tag(b, "link"); var src = tag(b, "source")
            if (src.isNotEmpty() && title.endsWith(" - $src")) title = title.dropLast(src.length + 3)
            else if (src.isEmpty()) { val i = title.lastIndexOf(" - "); if (i > 0) { src = title.substring(i + 3); title = title.substring(0, i) } }
            val ts = try { fmt.parse(tag(b, "pubDate"))?.time } catch (e: Exception) { null }
            if (title.isEmpty() || link.isEmpty() || ts == null) null else Story(title, link, src.ifEmpty { "News" }, ts)
        }.toList()
    }

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("ip", Context.MODE_PRIVATE)
        val cfg = try { JSONObject(prefs.getString("config", "{}") ?: "{}") } catch (e: Exception) { return Result.success() }
        val alerts = cfg.optJSONObject("alerts") ?: return Result.success()
        if (!alerts.optBoolean("on")) return Result.success()
        val mode = alerts.optString("mode", "both")
        val queries = strings(cfg.optJSONArray("queries")).take(12)
        if (queries.isEmpty()) return Result.success()
        val watch = termsRegex(strings(cfg.optJSONArray("watch")))
        val mute = termsRegex(strings(cfg.optJSONArray("mute")))

        val stories = LinkedHashMap<String, Story>()
        var failures = 0
        for (q in queries) {
            try { fetch(q).forEach { stories.putIfAbsent(it.link, it) } } catch (e: Exception) { failures++ }
        }
        if (stories.isEmpty()) return if (failures == queries.size) Result.retry() else Result.success()

        val notified = prefs.getStringSet("notified", emptySet())!!.toMutableSet()
        val baseline = prefs.getBoolean("baseline_done", false)
        val fresh = stories.values.filter { it.link !in notified && (mute == null || !mute.containsMatchIn(it.title)) }
            .sortedByDescending { it.ts }

        if (baseline) {
            val toNotify = fresh.filter { s ->
                val isWatch = watch?.containsMatchIn(s.title) == true
                val isHigh = important.findAll(s.title).map { it.value.lowercase() }.toSet().size >= 2
                (mode == "watch" && isWatch) || (mode == "high" && isHigh) || (mode == "both" && (isWatch || isHigh))
            }.take(4)
            toNotify.forEach { s ->
                val star = if (watch?.containsMatchIn(s.title) == true) "★ " else ""
                Notifier.post(applicationContext, s.link.hashCode(), star + s.title, s.source, s.link, s.title, s.source)
            }
        }
        // record everything seen (including ones we skipped) so they are never re-evaluated
        stories.keys.forEach { notified.add(it) }
        val trimmed = if (notified.size > 600) notified.toList().takeLast(500).toSet() else notified
        prefs.edit().putStringSet("notified", trimmed).putBoolean("baseline_done", true).apply()
        return Result.success()
    }
}
