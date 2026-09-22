package it.uhde.periph.chips.rtc;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DS3231Test {

    @Test
    void minimalDatetimeRoundTrip() throws Exception {
        MockConnection connection = new MockConnection();
        // 2026-09-22 (Tuesday=2), 14:30:05, OSF set, EN32kHz set.
        connection.setRegister(0x00,
                0x05, 0x30, 0x14, 0x02, 0x22, 0x09, 0x26);
        connection.setRegister(0x0F, 0x88);
        DS3231Minimal rtc = new DS3231Minimal(connection);

        DS3231Minimal.DateTime dt = rtc.getDatetime();
        assertEquals(2026, dt.year());
        assertEquals(9, dt.month());
        assertEquals(22, dt.day());
        assertEquals(2, dt.weekday());
        assertEquals(14, dt.hour());
        assertEquals(30, dt.minute());
        assertEquals(5, dt.second());

        rtc.setDatetime(2027, 1, 1, 5, 0, 0, 0);
        // writes() order: burst datetime write, status readReg (writeRead), status write.
        byte[] lastWrite = connection.writes().get(connection.writes().size() - 3);
        assertEquals(0x00, lastWrite[0]);
        assertEquals(0x00, lastWrite[1]); // seconds BCD
        assertEquals(0x00, lastWrite[3]); // hour, bit6=0 (24h)
        assertEquals(5, lastWrite[4]);    // weekday
        assertEquals(0x27, lastWrite[7]); // year 27 BCD

        // OSF cleared, EN32kHz preserved.
        assertEquals(0x08, connection.registers().get(0x0F));
    }

    @Test
    void readTemperaturePositiveAndNegative() throws Exception {
        MockConnection connection = new MockConnection();
        connection.setRegister(0x11, 0x19, 0x40); // +25.25 C
        DS3231Minimal rtc = new DS3231Minimal(connection);
        assertEquals(25.25, rtc.readTemperature(), 1e-9);

        MockConnection connection2 = new MockConnection();
        connection2.setRegister(0x11, 0xFF, 0x80); // -0.5 C
        DS3231Minimal rtc2 = new DS3231Minimal(connection2);
        assertEquals(-0.5, rtc2.readTemperature(), 1e-9);
    }

    @Test
    void alarm1MatchModeRoundTrip() throws Exception {
        MockConnection connection = new MockConnection();
        DS3231Full rtc = new DS3231Full(connection);

        rtc.setAlarm1(30, 15, 9, 0, false, DS3231Full.ALARM1_MATCH_HOURS_MINUTES_SECONDS);
        DS3231Full.Alarm1 alarm = rtc.getAlarm1();
        assertEquals(30, alarm.second());
        assertEquals(15, alarm.minute());
        assertEquals(9, alarm.hour());
        assertEquals(DS3231Full.ALARM1_MATCH_HOURS_MINUTES_SECONDS, alarm.matchMode());
    }

    @Test
    void alarm2DayOfWeekMatchMode() throws Exception {
        MockConnection connection = new MockConnection();
        DS3231Full rtc = new DS3231Full(connection);

        rtc.setAlarm2(0, 8, 3, true, DS3231Full.ALARM2_MATCH_DAY_HOURS_MINUTES);
        DS3231Full.Alarm2 alarm = rtc.getAlarm2();
        assertEquals(0, alarm.minute());
        assertEquals(8, alarm.hour());
        assertEquals(3, alarm.dayOrDate());
        assertTrue(alarm.isDayOfWeek());
        assertEquals(DS3231Full.ALARM2_MATCH_DAY_HOURS_MINUTES, alarm.matchMode());
    }

    @Test
    void interruptEnableSetsIntcnAndFlagBit() throws Exception {
        MockConnection connection = new MockConnection();
        DS3231Full rtc = new DS3231Full(connection);

        rtc.enableInterrupt(DS3231Full.SOURCE_ALARM1);
        int control = connection.registers().get(0x0E);
        assertEquals(DS3231Minimal.CONTROL_INTCN, control & DS3231Minimal.CONTROL_INTCN);
        assertEquals(DS3231Minimal.CONTROL_A1IE, control & DS3231Minimal.CONTROL_A1IE);

        rtc.disableInterrupt(DS3231Full.SOURCE_ALARM1);
        control = connection.registers().get(0x0E);
        assertEquals(0, control & DS3231Minimal.CONTROL_A1IE);
    }

    @Test
    void pollInterruptClearsFlagsOnly() throws Exception {
        MockConnection connection = new MockConnection();
        connection.setRegister(0x0F, 0x8B); // OSF=1, EN32kHz=1, A2F=1, A1F=1
        DS3231Full rtc = new DS3231Full(connection);

        int status = rtc.pollInterrupt();
        assertEquals(DS3231Full.SOURCE_ALARM1 | DS3231Full.SOURCE_ALARM2, status);
        int afterClear = connection.registers().get(0x0F);
        assertEquals(0, afterClear & 0x03); // A1F/A2F cleared
        assertEquals(0x88, afterClear);     // OSF/EN32kHz preserved
    }

    @Test
    void squareWaveAndInterruptShareIntcn() throws Exception {
        MockConnection connection = new MockConnection();
        DS3231Full rtc = new DS3231Full(connection);

        rtc.enableSquareWave(DS3231Full.SQUARE_WAVE_1024_HZ, true);
        int control = connection.registers().get(0x0E);
        assertFalse((control & DS3231Minimal.CONTROL_INTCN) != 0);

        rtc.disableSquareWave();
        control = connection.registers().get(0x0E);
        assertTrue((control & DS3231Minimal.CONTROL_INTCN) != 0);
    }

    @Test
    void agingOffsetSignedRoundTrip() throws Exception {
        MockConnection connection = new MockConnection();
        DS3231Full rtc = new DS3231Full(connection);

        rtc.setAgingOffset(-10);
        assertEquals(-10, rtc.getAgingOffset());
        rtc.setAgingOffset(100);
        assertEquals(100, rtc.getAgingOffset());
    }
}
