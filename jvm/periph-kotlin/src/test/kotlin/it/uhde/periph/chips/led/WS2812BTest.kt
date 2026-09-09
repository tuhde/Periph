package it.uhde.periph.chips.led

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WS2812BTest {

    private fun lastWrite(conn: MockConnection): ByteArray? = conn.writes().lastOrNull()

    @Test
    fun fillGrbOrderAndOff() {
        val conn = MockConnection()
        val d = WS2812BMinimal(conn, 2)
        d.fill(10, 20, 30)
        assertArrayEquals(byteArrayOf(20, 10, 30, 20, 10, 30), lastWrite(conn))

        d.off()
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0), lastWrite(conn))
    }

    @Test
    fun setPixelIndexClamp() {
        val conn = MockConnection()
        val d = WS2812BFull(conn, 3)
        d.setPixel(-1, 1, 2, 3)
        d.setPixel(1, 4, 5, 6)
        d.setPixel(100, 7, 8, 9)
        d.show()
        assertArrayEquals(byteArrayOf(2, 1, 3, 5, 4, 6, 8, 7, 9), lastWrite(conn))
    }

    @Test
    fun setPixelAndRotateZeroLengthDoesNotThrow() {
        val conn = MockConnection()
        val d = WS2812BFull(conn, 0)
        assertDoesNotThrow { d.setPixel(0, 1, 2, 3) }
        assertDoesNotThrow { d.rotate(1) }
        assertDoesNotThrow { d.show() }
    }

    @Test
    fun brightnessScaling() {
        val conn = MockConnection()
        val d = WS2812BFull(conn, 1)
        d.setPixel(0, 200, 100, 50)
        d.brightness = 128
        assertEquals(128, d.brightness)
        d.show()
        val want = byteArrayOf(
            (100 * 128 / 255).toByte(),
            (200 * 128 / 255).toByte(),
            (50 * 128 / 255).toByte(),
        )
        assertArrayEquals(want, lastWrite(conn))
    }

    @Test
    fun rotateShiftsLeftByWholePixels() {
        val conn = MockConnection()
        val d = WS2812BFull(conn, 4)
        d.setPixel(0, 1, 0, 0)
        d.setPixel(1, 2, 0, 0)
        d.setPixel(2, 3, 0, 0)
        d.setPixel(3, 4, 0, 0)
        d.rotate(1)
        d.show()
        assertArrayEquals(byteArrayOf(0, 2, 0, 0, 3, 0, 0, 4, 0, 0, 1, 0), lastWrite(conn))
    }

    @Test
    fun fillHsvRed() {
        val conn = MockConnection()
        val d = WS2812BFull(conn, 1)
        d.fillHsv(0.0, 1.0, 1.0)
        assertArrayEquals(byteArrayOf(0, 255.toByte(), 0), lastWrite(conn))
    }
}
