package it.uhde.periph.chips.led

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class APA102Test {

    private fun lastWrite(conn: MockConnection): ByteArray? = conn.writes().lastOrNull()

    @Test
    fun fillBgrOrderAndOff() {
        val conn = MockConnection()
        val d = APA102Minimal(conn, 2)
        d.fill(10, 20, 30)
        // Expected: start(4x0x00) + pixels[2*4] + end(4x0xFF)
        // Pixel: [0xFF, B, G, R] = [0xFF, 30, 20, 10]
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0xFF.toByte(), 30, 20, 10, 0xFF.toByte(), 30, 20, 10, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            lastWrite(conn)
        )

        d.off()
        // off: fill(0,0,0) -> pixels [0xFF, 0, 0, 0] x2
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0xFF.toByte(), 0, 0, 0, 0xFF.toByte(), 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            lastWrite(conn)
        )
    }

    @Test
    fun setPixelIndexClamp() {
        val conn = MockConnection()
        val d = APA102Full(conn, 3)
        d.setPixel(-1, 1, 2, 3, 31)
        d.setPixel(1, 4, 5, 6, 31)
        d.setPixel(100, 7, 8, 9, 31)
        d.show()
        // Index -1 clamps to 0, so it writes pixel 0: [0xFF, 3, 2, 1].
        // Pixel 1: [0xFF, 6, 5, 4]. Index 100 clamps to n-1=2: [0xFF, 9, 8, 7].
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0xFF.toByte(), 3, 2, 1, 0xFF.toByte(), 6, 5, 4, 0xFF.toByte(), 9, 8, 7, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            lastWrite(conn)
        )
    }

    @Test
    fun setPixelAndRotateZeroLengthDoesNotThrow() {
        val conn = MockConnection()
        val d = APA102Full(conn, 0)
        assertDoesNotThrow { d.setPixel(0, 1, 2, 3, 31) }
        assertDoesNotThrow { d.rotate(1) }
        assertDoesNotThrow { d.show() }
    }

    @Test
    fun brightnessScaling() {
        val conn = MockConnection()
        val d = APA102Full(conn, 1)
        d.setPixel(0, 200, 100, 50, 31)
        d.brightness = 128
        assertEquals(128, d.brightness)
        d.show()
        // Hardware brightness byte (0xFF) unchanged, RGB scaled.
        // Full frame: start(4x0x00) + pixel[4] + end(4x0xFF).
        val want = byteArrayOf(
            0, 0, 0, 0,
            0xFF.toByte(),
            (50 * 128 / 255).toByte(),
            (100 * 128 / 255).toByte(),
            (200 * 128 / 255).toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        )
        assertArrayEquals(want, lastWrite(conn))
    }

    @Test
    fun rotateShiftsLeftByWholePixels() {
        val conn = MockConnection()
        val d = APA102Full(conn, 4)
        d.setPixel(0, 1, 0, 0, 31)
        d.setPixel(1, 2, 0, 0, 31)
        d.setPixel(2, 3, 0, 0, 31)
        d.setPixel(3, 4, 0, 0, 31)
        d.rotate(1)
        d.show()
        // After rotate(1): pixel 0 gets old 1 (R=2), pixel 3 gets old 0 (R=1)
        assertArrayEquals(
            byteArrayOf(
                0, 0, 0, 0,
                0xFF.toByte(), 0, 0, 2,
                0xFF.toByte(), 0, 0, 3,
                0xFF.toByte(), 0, 0, 4,
                0xFF.toByte(), 0, 0, 1,
                0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
            ),
            lastWrite(conn)
        )
    }

    @Test
    fun fillHsvRed() {
        val conn = MockConnection()
        val d = APA102Full(conn, 1)
        d.fillHsv(0.0, 1.0, 1.0)
        // Red: R=255, G=0, B=0 -> wire: [0xFF, B=0, G=0, R=255]
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0xFF.toByte(), 0, 0, 255.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            lastWrite(conn)
        )
    }

    @Test
    fun setPixelHardwareBrightness() {
        val conn = MockConnection()
        val d = APA102Full(conn, 1)
        d.setPixel(0, 10, 20, 30, 16) // brightness=16
        d.show()
        // Expected: [0xE0|16=0xF0, B=30, G=20, R=10]
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0xF0.toByte(), 30, 20, 10, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            lastWrite(conn)
        )
    }

    @Test
    fun setPixelsWithHardwareBrightness() {
        val conn = MockConnection()
        val d = APA102Full(conn, 4)
        d.setPixels(listOf(
            intArrayOf(10, 20, 30, 31),
            intArrayOf(40, 50, 60, 16),
            intArrayOf(70, 80, 90, 8),
            intArrayOf(100, 110, 120, 4)
        ))
        d.show()
        // pixel 0: [0xFF, 30, 20, 10]
        // pixel 1: [0xF0, 60, 50, 40]
        // pixel 2: [0xE8, 90, 80, 70]
        // pixel 3: [0xE4, 120, 110, 100]
        assertArrayEquals(
            byteArrayOf(
                0, 0, 0, 0,
                0xFF.toByte(), 30, 20, 10,
                0xF0.toByte(), 60, 50, 40,
                0xE8.toByte(), 90, 80, 70,
                0xE4.toByte(), 120.toByte(), 110.toByte(), 100.toByte(),
                0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
            ),
            lastWrite(conn)
        )
    }
}