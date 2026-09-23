package it.uhde.periph.chips.rtc

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PCF8523Test {

    private fun MockConnection.lastWrite(reg: Int): Int =
        writes().lastOrNull { it.size == 2 && (it[0].toInt() and 0xFF) == reg }?.let { it[1].toInt() and 0xFF } ?: -1

    private fun MockConnection.reg(reg: Int): Int = registers()[reg] ?: 0

    @Test
    fun constructorEnablesBatteryStandardMode() {
        val conn = MockConnection()
        conn.setRegister(0x02, 0xE0)
        PCF8523Minimal(conn)
        assertEquals(0x00, conn.lastWrite(0x02))
    }

    @Test
    fun datetimeDecodeAndStopSequence() {
        val conn = MockConnection()
        conn.setRegister(0x03, 0xC5, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26)
        conn.setRegister(0x00, 0x08)
        val rtc = PCF8523Minimal(conn)
        assertEquals(PCF8523Minimal.DateTime(2026, 9, 23, 3, 14, 30, 45), rtc.getDatetime())

        val before = conn.writes().size
        rtc.setDatetime(2026, 9, 23, 3, 14, 30, 45)
        val w = conn.writes().subList(before, conn.writes().size)
        assertArrayEquals(byteArrayOf(0x00, 0x20), w[1])
        assertArrayEquals(byteArrayOf(0x03, 0x45, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26), w[2])
        assertArrayEquals(byteArrayOf(0x00, 0x00), w[3])
    }

    @Test
    fun alarmActiveLowEnables() {
        val conn = MockConnection()
        val rtc = PCF8523Full(conn)
        val alarm = PCF8523Full.Alarm(minute = 45, weekday = 6)
        rtc.setAlarm(alarm)
        assertEquals(listOf(0x45, 0x80, 0x80, 0x06), (0x0A..0x0D).map { conn.reg(it) })
        assertEquals(alarm, rtc.getAlarm())
    }

    @Test
    fun offsetTwosComplement() {
        val conn = MockConnection()
        val rtc = PCF8523Full(conn)
        rtc.setOffset(-64, PCF8523Full.OffsetMode.EVERY_MINUTE)
        assertEquals(0xC0, conn.reg(0x0E))
        assertEquals(PCF8523Full.Offset(-64, PCF8523Full.OffsetMode.EVERY_MINUTE), rtc.getOffset())
        rtc.setOffset(63)
        assertEquals(PCF8523Full.Offset(63, PCF8523Full.OffsetMode.EVERY_TWO_HOURS), rtc.getOffset())
    }

    @Test
    fun timersAndClockOutput() {
        val conn = MockConnection()
        conn.setRegister(0x0F, 0x38)
        val rtc = PCF8523Full(conn)
        rtc.configureTimerA(PCF8523Full.TimerAMode.WATCHDOG, 5, PCF8523Full.SourceClock.HZ_1, pulsed = true)
        assertEquals(0x02, conn.reg(0x10))
        assertEquals(5, conn.reg(0x11))
        assertEquals(0xBC, conn.reg(0x0F))
        rtc.enableInterrupt(PCF8523Full.SOURCE_TIMER_A)
        assertEquals(0x04, conn.lastWrite(0x01) and 0x07)
        rtc.disableTimerA()
        assertEquals(0, conn.reg(0x0F) and 0x06)

        conn.setRegister(0x0F, 0x38)
        rtc.configureTimerB(30, PCF8523Full.SourceClock.HZ_1_60, 130.0)
        assertEquals(0x43, conn.reg(0x12))
        assertEquals(0x39, conn.reg(0x0F))

        conn.setRegister(0x0F, 0x00)
        rtc.setClockOutput(1)
        assertEquals(0x30, conn.reg(0x0F))
        rtc.disableClockOutput()
        assertEquals(0x38, conn.reg(0x0F))
    }

    @Test
    fun batteryBackupDirectWithoutDetection() {
        val conn = MockConnection()
        val rtc = PCF8523Full(conn)
        rtc.configureBatteryBackup(PCF8523Full.BatteryMode.DIRECT, lowDetection = false)
        assertEquals(0xA0, conn.reg(0x02) and 0xE0)
    }

    @Test
    fun pollInterruptClearsOnlySetFlags() {
        val conn = MockConnection()
        val rtc = PCF8523Full(conn)
        conn.setRegister(0x01, 0xAB)
        conn.setRegister(0x02, 0x0E)
        val status = rtc.pollInterrupt()
        assertEquals(
            PCF8523Full.SOURCE_TIMER_A or PCF8523Full.SOURCE_TIMER_B or PCF8523Full.SOURCE_ALARM or
                PCF8523Full.SOURCE_BATTERY_SWITCH or PCF8523Full.SOURCE_BATTERY_LOW,
            status,
        )
        assertEquals(0x53, conn.lastWrite(0x01))
        assertEquals(0x02, conn.lastWrite(0x02))
    }

    @Test
    fun enableInterruptAndSoftwareReset() {
        val conn = MockConnection()
        val rtc = PCF8523Full(conn)
        rtc.enableInterrupt(PCF8523Full.SOURCE_SECOND or PCF8523Full.SOURCE_ALARM or PCF8523Full.SOURCE_BATTERY_LOW)
        assertEquals(0x06, conn.lastWrite(0x00))
        assertEquals(0x01, conn.lastWrite(0x02) and 0x03)
        rtc.disableInterrupt(PCF8523Full.SOURCE_ALARM)
        assertEquals(0x04, conn.lastWrite(0x00))
        rtc.softwareReset()
        assertEquals(0x58, conn.lastWrite(0x00))
    }
}
