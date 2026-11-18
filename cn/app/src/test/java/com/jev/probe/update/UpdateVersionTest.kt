package com.jev.probe.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本号比较是在线更新的判据：判错一次，要么天天骚扰用户，要么永远提示不到新版。
 * 这里把能想到的脏版本号都钉住。
 */
class UpdateVersionTest {

    @Test
    fun parseAcceptsPlainNumbers() {
        assertEquals(listOf(1, 7), UpdateVersion.parse("1.7"))
        assertEquals(listOf(1, 7, 1), UpdateVersion.parse("1.7.1"))
        assertEquals(listOf(2, 0), UpdateVersion.parse("2.0"))
        assertEquals(listOf(2), UpdateVersion.parse("2"))
    }

    @Test
    fun parseStripsVPrefixAndSpaces() {
        assertEquals(listOf(1, 8), UpdateVersion.parse("v1.8"))
        assertEquals(listOf(1, 8), UpdateVersion.parse("V1.8"))
        assertEquals(listOf(1, 10), UpdateVersion.parse("  1.10  "))
    }

    @Test
    fun parseRejectsSuffixedOrGarbageVersions() {
        assertNull(UpdateVersion.parse("1.7.0-dev"))
        assertNull(UpdateVersion.parse("1.7-beta1"))
        assertNull(UpdateVersion.parse(""))
        assertNull(UpdateVersion.parse("   "))
        assertNull(UpdateVersion.parse("abc"))
        assertNull(UpdateVersion.parse("1..7"))
        assertNull(UpdateVersion.parse("1.7."))
        assertNull(UpdateVersion.parse("v"))
        assertNull(UpdateVersion.parse("0.0.0-dev"))
    }

    @Test
    fun newerComparesSegmentBySegmentNotLexically() {
        assertTrue(UpdateVersion.isNewer("1.8", "1.7"))
        assertFalse(UpdateVersion.isNewer("1.7", "1.8"))
        // 字符串比较会认为 "1.9" > "1.10"，数字比较才是对的
        assertTrue(UpdateVersion.isNewer("1.10", "1.9"))
        assertFalse(UpdateVersion.isNewer("1.9", "1.10"))
    }

    @Test
    fun newerTreatsMissingSegmentsAsZero() {
        assertTrue(UpdateVersion.isNewer("1.7.1", "1.7"))
        assertFalse(UpdateVersion.isNewer("1.7.0", "1.7"))
        assertFalse(UpdateVersion.isNewer("1.7", "1.7.0"))
    }

    @Test
    fun sameVersionIsNotNewer() {
        assertFalse(UpdateVersion.isNewer("1.7", "1.7"))
        assertFalse(UpdateVersion.isNewer("v1.7", "1.7"))
        assertFalse(UpdateVersion.isNewer("1.7.0", "1.7.0"))
    }

    @Test
    fun unparsableSideNeverTriggersAnUpdate() {
        // 开发版 / 奇怪的 tag：拿不准就别提示，交给用户自己看
        assertFalse(UpdateVersion.isNewer("1.8", "1.7-dev"))
        assertFalse(UpdateVersion.isNewer("1.8-dev", "1.7"))
        assertFalse(UpdateVersion.isNewer("", "1.7"))
        assertFalse(UpdateVersion.isNewer("1.8", ""))
    }

    @Test
    fun majorVersionWins() {
        assertTrue(UpdateVersion.isNewer("2", "1.99.99"))
        assertFalse(UpdateVersion.isNewer("1.99.99", "2"))
    }
}