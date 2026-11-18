package com.jev.probe.update

/**
 * 版本号比较。只认「数字 + 点」这种正经版本号（"1.7" / "1.7.1" / "2.0"），
 * 前面的 v / V 和首尾空白先剥掉。任何一段不是纯数字（比如 "1.7.0-dev" 的 "0-dev"）
 * 就判不出来，返回 null —— 这种时候调用方不该自作主张去比，
 * 宁可少弹一次提示，也不要拿不准的版本号瞎打扰用户。
 */
object UpdateVersion {

    /**
     * "v1.7.1" → [1, 7, 1]；"1.7" → [1, 7]；"1.7-dev" / "" / "abc" → null。
     */
    fun parse(raw: String): List<Int>? {
        if (raw.isBlank()) return null
        var v = raw.trim()
        if (v[0] == 'v' || v[0] == 'V') v = v.substring(1)
        if (v.isBlank()) return null
        val segs = v.split(".")
        if (segs.isEmpty()) return null
        val out = ArrayList<Int>(segs.size)
        for (seg in segs) {
            if (seg.isEmpty()) return null
            for (ch in seg) {
                if (ch < '0' || ch > '9') return null
            }
            out.add(seg.toInt())
        }
        return out
    }

    /**
     * latest 是否严格比 current 新。缺的段当 0（"1.7" vs "1.7.1" → 后者新）。
     * 任一边解析不出来 → false，不做提示。
     */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parse(latest) ?: return false
        val b = parse(current) ?: return false
        val n = if (a.size > b.size) a.size else b.size
        for (i in 0 until n) {
            val x = if (i < a.size) a[i] else 0
            val y = if (i < b.size) b[i] else 0
            if (x > y) return true
            if (x < y) return false
        }
        return false
    }
}