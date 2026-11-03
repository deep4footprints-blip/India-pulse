package com.deep.indiapulse

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fetches a story's publisher page for the in-app reader.
 *  1. Google News links are redirects: resolve them to the real publisher URL (old links decode offline,
 *     new-style links need Google's own batchexecute call — fragile, so failures are reported, never hidden).
 *  2. Download the publisher HTML like a normal mobile browser would (no login, no paywall tricks).
 *  3. Return {u: finalUrl, h: html, pw: publisherMarksAsPaid}; text extraction happens in the page (Readability).
 */
object ArticleFetcher {
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val MAX_BYTES = 3_000_000
    private const val MAX_HTML_CHARS = 1_500_000

    private fun request(startUrl: String, method: String = "GET", body: String? = null, extra: Map<String, String> = emptyMap()): Pair<String, String> {
        var url = startUrl
        for (hop in 0 until 6) {
            if (url.startsWith("http://")) url = "https://" + url.removePrefix("http://")   // cleartext is blocked in this app
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.requestMethod = method
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            c.setRequestProperty("Accept-Language", "en-IN,en;q=0.9")
            extra.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw RuntimeException("redirect without location")
                url = URL(URL(url), loc).toString()
                c.disconnect()
                continue
            }
            if (code >= 400) throw RuntimeException("HTTP $code")
            val bytes = c.inputStream.use { readLimited(it) }
            val type = c.contentType ?: ""
            val text = String(bytes, charsetFor(type, bytes))
            return Pair(url, text)
        }
        throw RuntimeException("too many redirects")
    }

    private fun readLimited(s: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = s.read(buf); if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) break
        }
        return out.toByteArray()
    }

    private fun charsetFor(contentType: String, bytes: ByteArray): java.nio.charset.Charset {
        val fromHeader = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
        val sniff = fromHeader ?: Regex("<meta[^>]+charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
            .find(String(bytes, 0, minOf(bytes.size, 2000), Charsets.ISO_8859_1))?.groupValues?.get(1)
        return try { if (sniff != null) java.nio.charset.Charset.forName(sniff) else Charsets.UTF_8 } catch (e: Exception) { Charsets.UTF_8 }
    }

    private fun charsOf(vararg codes: Int): String = String(CharArray(codes.size) { codes[it].toChar() })

    /** Old-style Google News ids embed the publisher URL; new-style ("AU_yqL…") do not. */
    private fun offlineDecode(id: String): String? {
        val padded = id + "=".repeat((4 - id.length % 4) % 4)
        val raw = try { Base64.decode(padded, Base64.URL_SAFE) } catch (e: Exception) { return null }
        var s = String(raw, Charsets.ISO_8859_1)
        val prefix = charsOf(0x08, 0x13, 0x22)
        val suffix = charsOf(0xD2, 0x01, 0x00)
        if (s.startsWith(prefix)) s = s.substring(3)
        if (s.endsWith(suffix)) s = s.dropLast(3)
        if (s.isEmpty()) return null
        val len = s[0].code
        s = if (len >= 0x80) s.substring(minOf(2, s.length), minOf(s.length, len + 1)) else s.substring(1, minOf(s.length, len + 1))
        return if (s.startsWith("http")) s else null
    }

    private fun resolveGoogle(url: String): String {
        val u = URL(url)
        if (u.host != "news.google.com") return url
        val id = Regex("/articles/([^/?]+)").find(u.path)?.groupValues?.get(1)
            ?: throw RuntimeException("resolve failed: unrecognised Google link")
        offlineDecode(id)?.let { return it }

        var sig: String? = null; var ts: String? = null
        for (page in listOf("https://news.google.com/rss/articles/$id?hl=en-IN&gl=IN&ceid=IN:en", "https://news.google.com/articles/$id?hl=en-IN&gl=IN&ceid=IN:en")) {
            try {
                val html = request(page).second
                sig = Regex("data-n-a-sg=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                ts = Regex("data-n-a-ts=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                if (sig != null && ts != null) break
            } catch (e: Exception) { /* try next form */ }
        }
        if (sig == null || ts == null) throw RuntimeException("resolve failed: Google link not decodable")

        val inner = "[\"garturlreq\",[[\"X\",\"X\",[\"X\",\"X\"],null,null,1,1,\"US:en\",null,1,null,null,null,null,null,0,1],\"X\",\"X\",1,[1,1,1],1,1,null,0,0,null,0],\"$id\",$ts,\"$sig\"]"
        val req = JSONArray().put(JSONArray().put(JSONArray().put("Fbv4je").put(inner)))
        val resp = request(
            "https://news.google.com/_/DotsSplashUi/data/batchexecute", "POST",
            "f.req=" + URLEncoder.encode(req.toString(), "UTF-8"),
            mapOf("Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8")
        ).second
        val chunk = resp.split("\n\n").getOrNull(1) ?: throw RuntimeException("resolve failed: unexpected Google reply")
        return try {
            val payload = JSONArray(JSONArray(chunk).getJSONArray(0).getString(2))
            payload.getString(1).takeIf { it.startsWith("http") } ?: throw RuntimeException("no url")
        } catch (e: Exception) { throw RuntimeException("resolve failed: Google reply changed") }
    }

    /** Remove script/style/svg/noscript blocks so the page only receives readable markup. */
    private fun stripTag(h: String, tag: String): String {
        val sb = StringBuilder(); var i = 0
        val open = "<$tag"; val close = "</$tag>"
        while (true) {
            val s = h.indexOf(open, i, ignoreCase = true)
            if (s < 0) { sb.append(h, i, h.length); break }
            sb.append(h, i, s)
            val e = h.indexOf(close, s, ignoreCase = true)
            if (e < 0) break
            i = e + close.length
        }
        return sb.toString()
    }

    fun fetchArticle(googleUrl: String): String {
        val real = resolveGoogle(googleUrl)
        val (finalUrl, html) = request(real)
        val paywall = Regex("\"isAccessibleForFree\"\\s*:\\s*\"?false", RegexOption.IGNORE_CASE).containsMatchIn(html)
        var slim = html
        for (t in listOf("script", "style", "svg", "noscript")) slim = stripTag(slim, t)
        if (slim.length > MAX_HTML_CHARS) slim = slim.substring(0, MAX_HTML_CHARS)
        if (!slim.contains("<p", ignoreCase = true)) throw RuntimeException("page has no readable text (needs JavaScript or blocked)")
        return JSONObject().put("u", finalUrl).put("h", slim).put("pw", paywall).toString()
    }
}
