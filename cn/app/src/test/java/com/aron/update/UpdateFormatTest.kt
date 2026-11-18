package com.aron.chatbot.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 更新界面上给人看的字节数 / 进度文案。 */
class UpdateFormatTest {

    @Test
    fun zeroOrNegativeMeansUnknown() {
        assertEquals("未知大小", UpdateFormat.bytes(0L))
        assertEquals("未知大小", UpdateFormat.bytes(-1L))
    }

    @Test
    fun smallSizesStayInBytes() {
        assertEquals("512 B", UpdateFormat.bytes(512L))
        assertEquals("1023 B", UpdateFormat.bytes(1023L))
    }

    @Test
    fun kilobytesAndMegabytes() {
        assertEquals("1 KB", UpdateFormat.bytes(1024L))
        assertEquals("2 KB", UpdateFormat.bytes(2048L))
        assertEquals("1.0 MB", UpdateFormat.bytes(1048576L))
        assertEquals("12.0 MB", UpdateFormat.bytes(12582912L))
    }

    @Test
    fun progressShowsPercentWhenTotalKnown() {
        val s = UpdateFormat.progress(524288L, 1048576L)
        assertTrue(s, s.contains("50%"))
        // 524288 字节还没到 1 MB，显示成 512 KB 而不是 0.5 MB
        assertTrue(s, s.contains("512 KB"))
        assertTrue(s, s.contains("1.0 MB"))
    }

    @Test
    fun progressFallsBackToBytesWhenTotalUnknown() {
        val s = UpdateFormat.progress(1048576L, 0L)
        assertTrue(s, s.contains("1.0 MB"))
        assertTrue(s, !s.contains("%"))
    }

    @Test
    fun progressNeverExceedsOneHundredPercent() {
        val s = UpdateFormat.progress(2097152L, 1048576L)
        assertTrue(s, s.contains("100%"))
        assertTrue(s, !s.contains("200%"))
    }
}