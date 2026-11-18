package com.jev.probe.update

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 查 GitHub 最新 Release。只读、不重试、离线/限流/仓库没 Release 一律返回
 * [CheckOutcome.Failed]（reason 是给人看的话），绝不把异常抛到 UI 层。
 */
object UpdateChecker {

    private const val USER_AGENT = "JevUpdate/1.0 (Android)"

    /** 查一次最新版。current 是本机版本名（PackageManager 拿到的）。 */
    fun check(current: String, timeoutMs: Int = 10_000): CheckOutcome {
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
                    if (!release.hasApk) {
                        CheckOutcome.Failed(
                            "最新 Release（" + release.tag + "）没有 .apk 附件，只能去 Release 页手动下载"
                        )
                    } else if (UpdateVersion.isNewer(release.versionName, current)) {
                        CheckOutcome.Newer(release)
                    } else {
                        CheckOutcome.UpToDate(latest = release)
                    }
                }
            }
        } catch (e: CheckError) {
            CheckOutcome.Failed(e.message ?: "查询更新失败")
        } catch (e: Exception) {
            CheckOutcome.Failed(human(e))
        }
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