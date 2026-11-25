package it.uhde.periph.chips.rtc

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PCF85063ATest {

    private fun MockConnection.lastWrite(reg: Int): Int =
        writes().lastOrNull { it.size == 2 && (it[0].toInt() and 0xFF) == reg }?.let { it[1].toInt() and 0xFF } ?: -1

    private fun MockConnection.reg(reg: Int): Int = registers()[reg] ?: 0

    @Test
    fun constructorForces24HourRunning() {
        val conn = MockConnection()
        conn.setRegister(0x00, 0x37) // STOP, SR, CIE, 12_24, CAP_SEL
        PCF85063AMinimal(conn)
        // STOP and 12_24 cleared, SR masked, CIE and CAP_SEL preserved.
        assertEquals(0x05, conn.lastWrite(0x00))
        assertTrue(conn.writes().all { it.size <= 2 }, "constructor must not write a time register")
    }

    @Test
    fun datetimeDecodeAndStopSequence() {
        val conn = MockConnection()
        conn.setRegister(0x04, 0xC5, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26)
        val rtc = PCF85063AMinimal(conn)
        conn.setRegister(0x00, 0x02)
        assertEquals(PCF85063AMinimal.DateTime(2026, 9, 23, 3, 14, 30, 45), rtc.getDatetime())

        val before = conn.writes().size
        rtc.setDatetime(2026, 9, 23, 3, 14, 30, 45)
        val w = conn.writes().subList(before, conn.writes().size)
        assertArrayEquals(byteArrayOf(0x00, 0x20), w[1])
        assertArrayEquals(byteArrayOf(0x04, 0x45, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26), w[2])
        assertArrayEquals(byteArrayOf(0x00, 0x00), w[3])

        val n = conn.writes().size
        assertThrows(IllegalArgumentException::class.java) { rtc.setDatetime(2100, 1, 1, 5, 0, 0, 0) }
        assertEquals(n, conn.writes().size)
    }

    @Test
    fun alarmActiveLowEnablesAndClearsAf() {
        val conn = MockConnection()
        val rtc = PCF85063AFull(conn)
        conn.setRegister(0x01, 0xC8)
        val alarm = PCF85063AFull.Alarm(second = 5, minute = 45, weekday = 6)
        rtc.setAlarm(alarm)
        assertEquals(listOf(0x05, 0x45, 0x80, 0x80, 0x06), (0x0B..0x0F).map { conn.reg(it) })
        assertEquals(alarm, rtc.getAlarm())
        val c2 = conn.lastWrite(0x01)
        assertEquals(0x08, c2 and 0x48)
        assertEquals(0x80, c2 and 0x80)
    }

    @Test
    fun offsetTwosComplement() {
        val conn = MockConnection()
        val rtc = PCF85063AFull(conn)
        rtc.setOffset(-64, PCF85063AFull.OffsetMode.COURSE)
        assertEquals(0xC0, conn.reg(0x02))
        assertEquals(PCF85063AFull.Offset(-64, PCF85063AFull.OffsetMode.COURSE), rtc.getOffset())
        rtc.setOffset(63)
        assertEquals(PCF85063AFull.Offset(63, PCF85063AFull.OffsetMode.NORMAL), rtc.getOffset())
    }

    @Test
    fun timerConfigurationAndDisable() {
        val conn = MockConnection()
        conn.setRegister(0x11, 0x18)
        val rtc = PCF85063AFull(conn)
        rtc.configureTimer(30, PCF85063AFull.SourceClock.HZ_1, true)
        assertEquals(30, conn.reg(0x10))
        assertEquals(0x15, conn.reg(0x11))
        rtc.enableInterrupt(PCF85063AFull.SOURCE_TIMER)
        assertEquals(0x17, conn.reg(0x11))
        rtc.disableTimer()
        assertEquals(0x19, conn.reg(0x11))
        conn.setRegister(0x10, 17)
        assertEquals(17, rtc.readTimer())
    }

    @Test
    fun clockOutputAndMinuteInterrupt() {
        val conn = MockConnection()
        conn.setRegister(0x01, 0x48)
        val rtc = PCF85063AFull(conn)
        rtc.setClockOutput(1)
        assertEquals(0x4E, conn.reg(0x01))
        rtc.disableClockOutput()
        assertEquals(0x07, conn.reg(0x01) and 0x07)
        rtc.setClockOutput(2048)
        assertEquals(0x04, conn.reg(0x01) and 0x07)
        rtc.setMinuteInterrupt(PCF85063AFull.MinuteInterrupt.MINUTE)
        assertEquals(0x20, conn.reg(0x01) and 0x30)
        rtc.setMinuteInterrupt(PCF85063AFull.MinuteInterrupt.HALF_MINUTE)
        assertEquals(0x10, conn.reg(0x01) and 0x30)
        rtc.setMinuteInterrupt(PCF85063AFull.MinuteInterrupt.OFF)
        assertEquals(0x00, conn.reg(0x01) and 0x30)
    }

    @Test
    fun oscillatorLoadStopStartAndRam() {
        val conn = MockConnection()
        val rtc = PCF85063AFull(conn)
        rtc.setOscillatorLoad(12.5)
        assertEquals(0x01, conn.reg(0x00) and 0x01)
        rtc.setOscillatorLoad(7.0)
        assertEquals(0x00, conn.reg(0x00) and 0x01)
        assertThrows(IllegalArgumentException::class.java) { rtc.setOscillatorLoad(9.0) }
        rtc.stopClock()
        assertEquals(0x20, conn.reg(0x00) and 0x20)
        rtc.startClock()
        assertEquals(0x00, conn.reg(0x00) and 0x20)
        conn.setRegister(0x04, 0xD9)
        assertTrue(rtc.oscillatorStopped())
        rtc.clearOscillatorStop()
        assertEquals(0x59, conn.reg(0x04))
        assertFalse(rtc.oscillatorStopped())
        rtc.writeRam(0xA5)
        assertEquals(0xA5, rtc.readRam())
    }

    @Test
    fun pollInterruptMapsAndClearsOnlySetFlags() {
        val conn = MockConnection()
        val rtc = PCF85063AFull(conn)
        conn.setRegister(0x01, 0xE5)
        assertEquals(PCF85063AFull.SOURCE_ALARM, rtc.pollInterrupt())
        val c2 = conn.lastWrite(0x01)
        assertEquals(0x00, c2 and 0x40)
        assertEquals(0x08, c2 and 0x08)
        assertEquals(0xA5, c2 and 0xB7)
        conn.setRegister(0x01, 0x08)
        assertEquals(PCF85063AFull.SOURCE_TIMER, rtc.pollInterrupt())
        conn.setRegister(0x01, 0x00)
        assertEquals(0, rtc.pollInterrupt())
    }

    @Test
    fun enableAndDisableInterruptsAndSoftwareReset() {
        val conn = MockConnection()
        val rtc = PCF85063AFull(conn)
        rtc.enableInterrupt(PCF85063AFull.SOURCE_ALARM or PCF85063AFull.SOURCE_MINUTE or
            PCF85063AFull.SOURCE_HALF_MINUTE or PCF85063AFull.SOURCE_CORRECTION)
        assertEquals(0xB0, conn.reg(0x01) and 0xB0)
        assertEquals(0x04, conn.reg(0x00) and 0x04)
        rtc.disableInterrupt(PCF85063AFull.SOURCE_ALARM or PCF85063AFull.SOURCE_CORRECTION)
        assertEquals(0x30, conn.reg(0x01) and 0xB0)
        assertEquals(0x00, conn.reg(0x00) and 0x04)
        rtc.softwareReset()
        assertEquals(0x58, conn.lastWrite(0x00))
    }
}
