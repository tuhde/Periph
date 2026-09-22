package it.uhde.periph.chips.rtc

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DS3231Test {

    @Test
    fun minimalDatetimeRoundTrip() {
        val conn = MockConnection()
        conn.setRegister(0x00, 0x05, 0x30, 0x14, 0x02, 0x22, 0x09, 0x26)
        conn.setRegister(0x0F, 0x88)
        val rtc = DS3231Minimal(conn)

        val dt = rtc.getDatetime()
        assertEquals(2026, dt.year)
        assertEquals(9, dt.month)
        assertEquals(22, dt.day)
        assertEquals(2, dt.weekday)
        assertEquals(14, dt.hour)
        assertEquals(30, dt.minute)
        assertEquals(5, dt.second)

        rtc.setDatetime(2027, 1, 1, 5, 0, 0, 0)
        assertEquals(0x08, conn.registers()[0x0F]) // OSF cleared, EN32kHz preserved
    }

    @Test
    fun readTemperaturePositiveAndNegative() {
        val conn = MockConnection()
        conn.setRegister(0x11, 0x19, 0x40) // +25.25 C
        val rtc = DS3231Minimal(conn)
        assertEquals(25.25, rtc.readTemperature(), 1e-9)

        val conn2 = MockConnection()
        conn2.setRegister(0x11, 0xFF, 0x80) // -0.5 C
        val rtc2 = DS3231Minimal(conn2)
        assertEquals(-0.5, rtc2.readTemperature(), 1e-9)
    }

    @Test
    fun alarm1MatchModeRoundTrip() {
        val conn = MockConnection()
        val rtc = DS3231Full(conn)
        rtc.setAlarm1(30, 15, 9, 0, false, DS3231Full.ALARM1_MATCH_HOURS_MINUTES_SECONDS)
        val alarm = rtc.getAlarm1()
        assertEquals(30, alarm.second)
        assertEquals(15, alarm.minute)
        assertEquals(9, alarm.hour)
        assertEquals(DS3231Full.ALARM1_MATCH_HOURS_MINUTES_SECONDS, alarm.matchMode)
    }

    @Test
    fun interruptEnableSetsIntcnAndFlagBit() {
        val conn = MockConnection()
        val rtc = DS3231Full(conn)
        rtc.enableInterrupt(DS3231Full.SOURCE_ALARM1)
        val control = conn.registers()[0x0E]!!
        assertEquals(DS3231Minimal.CONTROL_INTCN, control and DS3231Minimal.CONTROL_INTCN)
        assertEquals(DS3231Minimal.CONTROL_A1IE, control and DS3231Minimal.CONTROL_A1IE)
    }

    @Test
    fun pollInterruptClearsFlagsOnly() {
        val conn = MockConnection()
        conn.setRegister(0x0F, 0x8B)
        val rtc = DS3231Full(conn)
        val status = rtc.pollInterrupt()
        assertEquals(DS3231Full.SOURCE_ALARM1 or DS3231Full.SOURCE_ALARM2, status)
        val afterClear = conn.registers()[0x0F]!!
        assertEquals(0, afterClear and 0x03)
        assertEquals(0x88, afterClear)
    }

    @Test
    fun squareWaveAndInterruptShareIntcn() {
        val conn = MockConnection()
        val rtc = DS3231Full(conn)
        rtc.enableSquareWave(DS3231Full.SQUARE_WAVE_1024_HZ, true)
        assertEquals(0, conn.registers()[0x0E]!! and DS3231Minimal.CONTROL_INTCN)
        rtc.disableSquareWave()
        assertTrue((conn.registers()[0x0E]!! and DS3231Minimal.CONTROL_INTCN) != 0)
    }

    @Test
    fun agingOffsetSignedRoundTrip() {
        val conn = MockConnection()
        val rtc = DS3231Full(conn)
        rtc.setAgingOffset(-10)
        assertEquals(-10, rtc.getAgingOffset())
        rtc.setAgingOffset(100)
        assertEquals(100, rtc.getAgingOffset())
    }
}
