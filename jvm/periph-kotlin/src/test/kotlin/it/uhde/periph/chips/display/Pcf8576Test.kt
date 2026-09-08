package it.uhde.periph.chips.display

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Pcf8576Test {

    private fun zeros21(): ByteArray = ByteArray(21)

    private fun lastWrite(connection: MockConnection): ByteArray {
        val writes = connection.writes()
        return writes[writes.size - 1]
    }

    @Test
    fun fullApi() {
        val connection = MockConnection()
        val sensor = Pcf8576Full(connection)

        // init: mode-set (E=1, bias=1/3, mode=1:4 -> 0x40|0x08|0x00|0x00 = 0x48),
        // then load-ptr(0) + 20 zero bytes to blank all RAM.
        assertArrayEquals(byteArrayOf(0x48), connection.writes()[0])
        assertArrayEquals(zeros21(), connection.writes()[1])

        sensor.clear()
        val writes = connection.writes()
        assertArrayEquals(byteArrayOf(0x48), writes[writes.size - 2])
        assertArrayEquals(zeros21(), writes[writes.size - 1])

        sensor.writeRaw(5, byteArrayOf(0xAB.toByte(), 0xCD.toByte()))
        assertArrayEquals(byteArrayOf(0x05, 0xAB.toByte(), 0xCD.toByte()), lastWrite(connection))

        val nBefore = connection.writes().size
        sensor.writeRaw(3, ByteArray(0))
        assertEquals(nBefore, connection.writes().size, "writeRaw with empty data should be a no-op")

        // digit '7' -> 0xE0, at RAM address 3*2=6.
        sensor.setDigit7seg(3, Pcf8576Minimal.SEVEN_SEG[7])
        assertArrayEquals(byteArrayOf(0x06, 0xE0.toByte()), lastWrite(connection))

        sensor.disable()
        assertArrayEquals(byteArrayOf(0x40), lastWrite(connection))
        sensor.enable()
        assertArrayEquals(byteArrayOf(0x48), lastWrite(connection))

        // setMode(): mode-set byte = 0x40 | E(0x08) | bias | mode.
        sensor.setMode(Pcf8576Full.BACKPLANES_1, Pcf8576Full.BIAS_1_2_FULL)
        assertArrayEquals(byteArrayOf(0x4D), lastWrite(connection)) // 0x40|8|4|1

        sensor.setMode(Pcf8576Full.BACKPLANES_2, Pcf8576Full.BIAS_1_3_FULL)
        assertArrayEquals(byteArrayOf(0x4A), lastWrite(connection)) // 0x40|8|0|2

        sensor.setMode(Pcf8576Full.BACKPLANES_3, Pcf8576Full.BIAS_1_3_FULL)
        assertArrayEquals(byteArrayOf(0x4B), lastWrite(connection)) // 0x40|8|0|3

        sensor.setMode(Pcf8576Full.BACKPLANES_4, Pcf8576Full.BIAS_1_3_FULL)
        assertArrayEquals(byteArrayOf(0x48), lastWrite(connection)) // 0x40|8|0|0

        sensor.setBlink(Pcf8576Full.BLINK_1_HZ, alternateBank = false)
        assertArrayEquals(byteArrayOf(0x72), lastWrite(connection)) // 0x70|0|2

        sensor.setBlink(Pcf8576Full.BLINK_2_HZ, alternateBank = true)
        assertArrayEquals(byteArrayOf(0x75), lastWrite(connection)) // 0x70|4|1

        sensor.setBank(1, 0)
        assertArrayEquals(byteArrayOf(0x7A), lastWrite(connection)) // 0x78|(1<<1)|0

        sensor.deviceSelect(5)
        assertArrayEquals(byteArrayOf(0x65), lastWrite(connection)) // 0x60|5
    }

    // Kotlin's `protected` is subclass-only (unlike Java's, which also
    // allows same-package access), so a small test-only subclass is the way
    // to reach the field below.
    private class BackplanesAccess(connection: MockConnection) : Pcf8576Minimal(connection) {
        fun backplanesValue() = backplanes
    }

    @Test
    fun minimalInitMatchesDefaultBackplanes() {
        // Regression test for a bug where Pcf8576Minimal's `backplanes` field
        // defaulted to MODE_1_4 (0x00, a drive-mode bit pattern) instead of
        // the backplane count 4 - harmless only by coincidence, since
        // Pcf8576Full.modeCode()'s default arm also happens to return
        // MODE_1_4.
        val connection = MockConnection()
        val sensor = BackplanesAccess(connection)
        assertEquals(4, sensor.backplanesValue())
    }
}
