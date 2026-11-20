package com.aron.chatbot.update

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 查 GitHub 最新 Release。只读、离线/限流/仓库没 Release 一律返回
 * [CheckOutcome.Failed]（reason 是给人看的话），绝不把异常抛到 UI 层。
 *
 * api.github.com 被限流（403/429）或被网络挡掉时，自动切到 github.com 的
 * 网页入口兜底（[checkViaWeb]），那条路不消耗 API 配额，国内网络常见
 * 「检查更新一直 403」就靠它救。
 */
object UpdateChecker {

    private const val USER_AGENT = "JevUpdate/1.0 (Android)"

    /** 查一次最新版。current 是本机版本名（PackageManager 拿到的）。 */
    fun check(current: String, timeoutMs: Int = 10_000): CheckOutcome {
        val api = checkViaApi(current, timeoutMs)
        if (api !is CheckOutcome.Failed) return api
        return checkViaWeb(current, timeoutMs) ?: api
    }

    private fun checkViaApi(current: String, timeoutMs: Int): CheckOutcome {
        return try {
            val (status, body) = httpGet(UpdateConfig.API_LATEST_URL, timeoutMs)
            when {
                status == 404 -> CheckOutcome.Failed("这个仓库还没有发布正式 Release，没法在线更新")
                status == 401 -> CheckOutcome.Failed("GitHub 拒绝访问（HTTP 401），检查仓库是否存在")
                status == 403 || status == 429 -> CheckOutcome.Failed(
                    "GitHub 接口限流（HTTP " + status + "），过一会儿再查"
                )
                status != 200 -> CheckOutcome.Failed("查询更新失败：HTTP " + status)
                else -> {
                    val release = parseRelease(body)
                        ?: return CheckOutcome.Failed("Release 数据解析失败，字段不对")
                    outcomeOf(release, current)
                }
            }
        } catch (e: CheckError) {
            CheckOutcome.Failed(e.message ?: "查询更新失败")
        } catch (e: Exception) {
            CheckOutcome.Failed(human(e))
        }
    }

    private fun outcomeOf(release: ReleaseInfo, current: String): CheckOutcome = when {
        !release.hasApk -> CheckOutcome.Failed(
            "最新 Release（" + release.tag + "）没有 .apk 附件，只能去 Release 页手动下载"
        )
        UpdateVersion.isNewer(release.versionName, current) -> CheckOutcome.Newer(release)
        else -> CheckOutcome.UpToDate(release)
    }

    /**
     * 兜底通道：GET github.com 的 /releases/latest，302 的 Location 里就带
     * 最新 tag，再按发版命名约定（chat-bot-<tag>.apk）推出 apk 地址。
     * 不走 api.github.com，也就没有那 60 次/小时的配额问题。
     */
    private fun checkViaWeb(current: String, timeoutMs: Int): CheckOutcome? {
        val tag = probeLatestTag(timeoutMs) ?: return null
        return outcomeOf(releaseFromTag(tag), current)
    }

    private fun probeLatestTag(timeoutMs: Int): String? {
        var url = "https://github.com/" + UpdateConfig.REPO_OWNER + "/" +
            UpdateConfig.REPO_NAME + "/releases/latest"
        var hops = 0
        while (hops <= 5) {
            var conn: HttpURLConnection? = null
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.instanceFollowRedirects = false
                c.requestMethod = "GET"
                c.setRequestProperty("User-Agent", USER_AGENT)
                when (val code = c.responseCode) {
                    in 301..308 -> {
                        val loc = c.getHeaderField("Location") ?: return null
                        tagFromUrl(loc)?.let { return it }
                        url = loc
                        hops++
                    }
                    200 -> {
                        val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        return tagFromHtml(body)
                    }
                    else -> return null
                }
            } catch (e: Exception) {
                return null
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    /** 从 /releases/tag/<tag> 这样的地址里取 tag。 */
    internal fun tagFromUrl(url: String): String? {
        val marker = "/releases/tag/"
        val i = url.indexOf(marker)
        if (i < 0) return null
        val tag = url.substring(i + marker.length).substringBefore('?').substringBefore('#').trim()
        return tag.ifBlank { null }
    }

    /** 302 没抓到时，从页面 HTML 里抓第一个 /releases/tag/<tag>。 */
    internal fun tagFromHtml(body: String): String? =
        Regex("/releases/tag/(v?[0-9][0-9A-Za-z.\\-_]*)").find(body)?.groupValues?.get(1)

    /**
     * 按发版命名约定从 tag 推出整个 Release 信息。
     * apk 名字必须和发版脚本一致（chat-bot-<tag>.apk），改一处就要改两处。
     */
    internal fun releaseFromTag(tag: String): ReleaseInfo {
        val base = "https://github.com/" + UpdateConfig.REPO_OWNER + "/" + UpdateConfig.REPO_NAME
        return ReleaseInfo(
            tag = tag,
            versionName = tag.trim().removePrefix("v").removePrefix("V"),
            notes = "",
            pageUrl = "$base/releases/tag/$tag",
            apkUrl = "$base/releases/download/$tag/chat-bot-$tag.apk",
            apkName = "chat-bot-$tag.apk",
            apkSize = 0L
        )
    }

    /**
     * 从 Releases/latest 的 JSON 里取一个 .apk 附件。没有 apk 附件也能返回 Release，
     * 由调用方决定怎么提示（去页面手动下）。
     */
    fun parseRelease(body: String): ReleaseInfo? {
        return try {
            val root = JSONObject(body)
            val tag = root.optString("tag_name", "").ifBlank { root.optString("tag", "") }
            if (tag.isBlank()) return null
            val assets = root.optJSONArray("assets")
            var url = ""
            var name = ""
            var size = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val assetName = a.optString("name", "")
                    if (!assetName.endsWith(".apk", ignoreCase = true)) continue
                    url = a.optString("browser_download_url", "")
                    name = assetName
                    size = a.optLong("size", 0L)
                    break
                }
            }
            ReleaseInfo(
                tag = tag,
                versionName = tag.trim().removePrefix("v").removePrefix("V"),
                notes = root.optString("body", ""),
                pageUrl = root.optString("html_url", ""),
                apkUrl = url,
                apkName = name,
                apkSize = size
            )
        } catch (e: Exception) {
            null
        }
    }

    // --------------------------------------------------------------

    /** 自带 message 的内部异常：message 就是给人看的那句话，直接透传给 UI。 */
    private class CheckError(message: String) : IOException(message)

    /**
     * GET 一个地址，返回 (HTTP 状态码, 响应体)。手动跟随 301..308，
     * 因为 GitHub 的资源地址会跳到 objects.githubusercontent.com。
     */
    private fun httpGet(url: String, timeoutMs: Int): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        var current = url
        var hops = 0
        try {
            while (true) {
                val c = URL(current).openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.instanceFollowRedirects = false
                c.requestMethod = "GET"
                c.setRequestProperty("User-Agent", USER_AGENT)
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val code = c.responseCode
                if (code in 301..308) {
                    val loc = c.getHeaderField("Location")
                    if (loc.isNullOrBlank()) throw CheckError("更新地址重定向异常")
                    if (++hops > 5) throw CheckError("更新地址重定向次数过多")
                    current = loc
                    c.disconnect()
                    conn = null
                    continue
                }
                val body = if (code in 200..299) {
                    c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } else {
                    val stream = c.errorStream
                    if (stream != null) {
                        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }.take(300)
                    } else {
                        ""
                    }
                }
                return Pair(code, body)
            }
        } catch (e: CheckError) {
            throw e
        } catch (e: Exception) {
            throw CheckError(human(e))
        } finally {
            conn?.disconnect()
        }
    }

    /** 把底层异常翻译成用户能动手的人话。 */
    private fun human(e: Exception): String {
        val m = e.message ?: ""
        return when {
            m.contains("UnknownHost", ignoreCase = true) -> "域名解析失败，检查网络连接"
            m.contains("timeout", ignoreCase = true) -> "连接超时，请检查网络"
            m.contains("SSL", ignoreCase = true) || m.contains("Cert", ignoreCase = true) ->
                "HTTPS 证书校验失败"
            else -> "网络异常：" + m.take(120)
        }
    }
}