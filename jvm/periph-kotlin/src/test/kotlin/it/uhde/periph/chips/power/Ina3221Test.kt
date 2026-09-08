package it.uhde.periph.chips.power

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Ina3221Test {

    @Test
    fun fullApi() {
        val connection = MockConnection()

        // Construction (default rShunt=0.1 for all 3 channels) writes nothing.
        val sensor = Ina3221Full(connection)
        assertTrue(connection.writes().isEmpty(), "init should write nothing")

        // --- Channel 1 ---
        // Bus1 raw=10000 (0x2710) -> (10000>>3)*8e-3 = 10.0 V
        connection.setRegister(0x02, 0x27, 0x10)
        assertEquals(10.0, sensor.voltage(1))

        // Shunt1 raw signed = -400 (0xFE70) -> (-400>>3)*40e-6 = -0.002 V
        connection.setRegister(0x01, 0xFE, 0x70)
        val sv1 = sensor.shuntVoltage(1)
        assertEquals((0xFE70.toShort().toInt() shr 3) * 40e-6, sv1, 1e-9)
        assertEquals(sv1 / 0.1, sensor.current(1), 1e-9)

        // power(1): Shunt1 (0x01) and Bus1 (0x02) are adjacent registers, and
        // the mock's byte-slot model can't hold two independent 16-bit
        // values across adjacent addresses at once - so the Shunt1 low byte
        // and Bus1 high byte are chosen equal (0x10) to survive either
        // write order. Shunt1=0xFF10, Bus1=0x1000 (4096) -> 4.096 V.
        connection.setRegister(0x01, 0xFF, 0x10)
        connection.setRegister(0x02, 0x10, 0x00)
        val expectedShunt1 = (0xFF10.toShort().toInt() shr 3) * 40e-6
        assertEquals(4.096 * (expectedShunt1 / 0.1), sensor.power(1), 1e-9)

        // --- Channel 2 ---
        // Bus2 raw=4096 (0x1000) -> (4096>>3)*8e-3 = 4.096 V
        connection.setRegister(0x04, 0x10, 0x00)
        assertEquals(4.096, sensor.voltage(2))

        // Shunt2 raw=800 (0x0320) -> (800>>3)*40e-6
        connection.setRegister(0x03, 0x03, 0x20)
        val sv2 = sensor.shuntVoltage(2)
        assertEquals((800 shr 3) * 40e-6, sv2, 1e-9)
        assertEquals(sv2 / 0.1, sensor.current(2), 1e-9)

        // Invalid channel throws.
        assertThrows(IllegalArgumentException::class.java) { sensor.voltage(4) }

        // configure(3, 2, 1, 5) preserves channel-enable bits (0x7000).
        connection.setRegister(0x00, 0x71, 0x27)
        sensor.configure(3, 2, 1, 5)
        val configWrite1 = connection.writes().last { it.size == 3 && (it[0].toInt() and 0xFF) == 0x00 }
        assertEquals(0x76.toByte(), configWrite1[1])
        assertEquals(0x8D.toByte(), configWrite1[2])

        // enableChannel(2, true): CH2en is bit 13.
        connection.setRegister(0x00, 0x01, 0x27)
        sensor.enableChannel(2, true)
        val configWrite2 = connection.writes().last { it.size == 3 && (it[0].toInt() and 0xFF) == 0x00 }
        assertEquals(0x21.toByte(), configWrite2[1])
        assertEquals(0x27.toByte(), configWrite2[2])

        // channelEnabled(1): CH1en is bit 14.
        connection.setRegister(0x00, 0x41, 0x27)
        assertTrue(sensor.channelEnabled(1))

        // conversionReady(): CVRF is bit 0.
        connection.setRegister(0x0F, 0x00, 0x01)
        assertTrue(sensor.conversionReady())

        // setCriticalAlert(2, 0.048): raw = (round(1200) << 3) & 0xFFF8 = 0x2580.
        sensor.setCriticalAlert(2, 0.048)
        val critWrite = connection.writes().last { it.size == 3 && (it[0].toInt() and 0xFF) == 0x09 }
        assertEquals(0x25.toByte(), critWrite[1])
        assertEquals(0x80.toByte(), critWrite[2])

        // setWarningAlert(1, 0.024): raw = (round(600) << 3) & 0xFFF8 = 0x12C0.
        sensor.setWarningAlert(1, 0.024)
        val warnWrite = connection.writes().last { it.size == 3 && (it[0].toInt() and 0xFF) == 0x08 }
        assertEquals(0x12.toByte(), warnWrite[1])
        assertEquals(0xC0.toByte(), warnWrite[2])

        // alertFlags(): raw Mask/Enable register.
        connection.setRegister(0x0F, 0x02, 0x41)
        assertEquals(0x0241, sensor.alertFlags())

        // setSummationChannels([1], 0.1) with a stale SCC3 bit (0x1000)
        // already set: channel 1 must map to bit 14 (SCC1).
        connection.setRegister(0x0F, 0x10, 0x00)
        sensor.setSummationChannels(intArrayOf(1), 0.1)
        val summationMaskWrite = connection.writes().last { it.size == 3 && (it[0].toInt() and 0xFF) == 0x0F }
        assertEquals(0x40.toByte(), summationMaskWrite[1])
        assertEquals(0x00.toByte(), summationMaskWrite[2])
        val summationLimitWrite = connection.writes().last { it.size == 3 && (it[0].toInt() and 0xFF) == 0x0E }
        assertEquals(0x13.toByte(), summationLimitWrite[1])
        assertEquals(0x88.toByte(), summationLimitWrite[2])

        // summationValue(): raw=0x2328 (9000) -> (9000>>1)*40e-6 = 0.18 V.
        connection.setRegister(0x0D, 0x23, 0x28)
        assertEquals(0.18, sensor.summationValue(), 1e-9)

        // setPowerValidLimits(8.112, 4.096)
        sensor.setPowerValidLimits(8.112, 4.096)
        val pvUpperWrite = connection.writes().last { it.size == 3 && (it[0].toInt() and 0xFF) == 0x10 }
        assertEquals(0x1F.toByte(), pvUpperWrite[1])
        assertEquals(0xB0.toByte(), pvUpperWrite[2])
        val pvLowerWrite = connection.writes().last { it.size == 3 && (it[0].toInt() and 0xFF) == 0x11 }
        assertEquals(0x10.toByte(), pvLowerWrite[1])
        assertEquals(0x00.toByte(), pvLowerWrite[2])

        // powerValid(): PVF is bit 2.
        connection.setRegister(0x0F, 0x00, 0x04)
        assertTrue(sensor.powerValid())

        // shutdown(): reads CONFIG, saves MODE bits, writes CONFIG & 0xFFF8.
        connection.setRegister(0x00, 0x71, 0x27)
        sensor.shutdown()
        val shutdownWrite = connection.writes().last()
        assertEquals(0x71.toByte(), shutdownWrite[1])
        assertEquals(0x20.toByte(), shutdownWrite[2])

        // wake(): reads CONFIG, restores saved MODE bits (7, from shutdown()).
        connection.setRegister(0x00, 0x71, 0x20)
        sensor.wake()
        val wakeWrite = connection.writes().last()
        assertEquals(0x71.toByte(), wakeWrite[1])
        assertEquals(0x27.toByte(), wakeWrite[2])

        // reset(): writes CONFIG=0x8000 (RST), then restores hardware
        // defaults (0x7127) with the saved MODE (7, from shutdown()).
        sensor.reset()
        val writes = connection.writes()
        val resetWrite1 = writes[writes.size - 2]
        val resetWrite2 = writes[writes.size - 1]
        assertEquals(0x80.toByte(), resetWrite1[1])
        assertEquals(0x00.toByte(), resetWrite1[2])
        assertEquals(0x71.toByte(), resetWrite2[1])
        assertEquals(0x27.toByte(), resetWrite2[2])

        // manufacturerId() / dieId()
        connection.setRegister(0xFE, 0x54, 0x49)
        assertEquals(0x5449, sensor.manufacturerId())
        connection.setRegister(0xFF, 0x32, 0x20)
        assertEquals(0x3220, sensor.dieId())
    }
}
