package it.uhde.periph.chips.led

import it.uhde.periph.connection.MockConnection
import it.uhde.periph.connection.MockResetExtenderConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WS2814Test {

    private val resetBytes = 90

    private fun lastExtWrite(conn: MockResetExtenderConnection): ByteArray? = conn.extWrites().lastOrNull()

    @Test
    fun fillRgbwIdentityOrderAndOffRequestExtendedReset() {
        val conn = MockResetExtenderConnection()
        val d = WS2814Minimal(conn, 2)
        d.fill(10, 20, 30, 40)
        assertArrayEquals(byteArrayOf(10, 20, 30, 40, 10, 20, 30, 40), lastExtWrite(conn))
        assertEquals(resetBytes, conn.extResetBytes().last())

        d.off()
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0), lastExtWrite(conn))
    }

    @Test
    fun fillWhiteDefaultsZero() {
        val conn = MockResetExtenderConnection()
        val d = WS2814Minimal(conn, 1)
        d.fill(0, 0, 0, 255)
        assertArrayEquals(byteArrayOf(0, 0, 0, 255.toByte()), lastExtWrite(conn))
    }

    @Test
    fun fallsBackToPlainWriteWithoutResetExtender() {
        val conn = MockConnection()
        val d = WS2814Minimal(conn, 1)
        d.fill(1, 2, 3, 4)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), conn.writes().last())
    }

    @Test
    fun setPixelIndexClamp() {
        val conn = MockResetExtenderConnection()
        val d = WS2814Full(conn, 3)
        d.setPixel(-1, 1, 2, 3, 4)
        d.setPixel(1, 5, 6, 7, 8)
        d.setPixel(100, 9, 10, 11, 12)
        d.show()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), lastExtWrite(conn))
    }

    @Test
    fun setPixelAndRotateZeroLengthDoesNotThrow() {
        val conn = MockResetExtenderConnection()
        val d = WS2814Full(conn, 0)
        assertDoesNotThrow { d.setPixel(0, 1, 2, 3, 4) }
        assertDoesNotThrow { d.rotate(1) }
        assertDoesNotThrow { d.show() }
    }

    @Test
    fun brightnessScaling() {
        val conn = MockResetExtenderConnection()
        val d = WS2814Full(conn, 1)
        d.setPixel(0, 200, 100, 50, 80)
        d.brightness = 128
        assertEquals(128, d.brightness)
        d.show()
        val want = byteArrayOf(
            (200 * 128 / 255).toByte(),
            (100 * 128 / 255).toByte(),
            (50 * 128 / 255).toByte(),
            (80 * 128 / 255).toByte(),
        )
        assertArrayEquals(want, lastExtWrite(conn))
    }

    @Test
    fun rotateShiftsLeftByWholePixels() {
        val conn = MockResetExtenderConnection()
        val d = WS2814Full(conn, 4)
        d.setPixel(0, 1, 0, 0, 0)
        d.setPixel(1, 2, 0, 0, 0)
        d.setPixel(2, 3, 0, 0, 0)
        d.setPixel(3, 4, 0, 0, 0)
        d.rotate(1)
        d.show()
        assertArrayEquals(
            byteArrayOf(2, 0, 0, 0, 3, 0, 0, 0, 4, 0, 0, 0, 1, 0, 0, 0), lastExtWrite(conn))
    }

    @Test
    fun fillHsvRed() {
        val conn = MockResetExtenderConnection()
        val d = WS2814Full(conn, 1)
        d.fillHsv(0.0, 1.0, 1.0)
        assertArrayEquals(byteArrayOf(255.toByte(), 0, 0, 0), lastExtWrite(conn))
    }
}
