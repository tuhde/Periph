package it.uhde.periph.chips.rtc;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PCF8523Test {

    private static int lastWrite(MockConnection c, int reg) {
        int value = -1;
        for (byte[] w : c.writes()) {
            if (w.length == 2 && (w[0] & 0xFF) == reg) value = w[1] & 0xFF;
        }
        return value;
    }

    private static int reg(MockConnection c, int reg) {
        Integer v = c.registers().get(reg);
        return v == null ? 0 : v;
    }

    @Test
    void constructorEnablesBatteryStandardMode() throws Exception {
        MockConnection connection = new MockConnection();
        connection.setRegister(0x02, 0xE0);
        new PCF8523Minimal(connection);
        assertEquals(0x00, lastWrite(connection, 0x02));
    }

    @Test
    void datetimeDecodeAndStopSequence() throws Exception {
        MockConnection connection = new MockConnection();
        // 2026-09-23 Wednesday(3) 14:30:45, OS flag set; CONTROL_1 in 12-hour mode.
        connection.setRegister(0x03, 0xC5, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26);
        connection.setRegister(0x00, 0x08);
        PCF8523Minimal rtc = new PCF8523Minimal(connection);

        assertEquals(new PCF8523Minimal.DateTime(2026, 9, 23, 3, 14, 30, 45), rtc.getDatetime());

        int before = connection.writes().size();
        rtc.setDatetime(2026, 9, 23, 3, 14, 30, 45);
        List<byte[]> w = connection.writes().subList(before, connection.writes().size());
        assertArrayEquals(new byte[]{0x00, 0x20}, w.get(1));
        assertArrayEquals(new byte[]{0x03, 0x45, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26}, w.get(2));
        assertArrayEquals(new byte[]{0x00, 0x00}, w.get(3));
    }

    @Test
    void alarmActiveLowEnables() throws Exception {
        MockConnection connection = new MockConnection();
        PCF8523Full rtc = new PCF8523Full(connection);
        rtc.setAlarm(new PCF8523Full.Alarm(45, null, null, 6));
        assertEquals(0x45, reg(connection, 0x0A));
        assertEquals(0x80, reg(connection, 0x0B));
        assertEquals(0x80, reg(connection, 0x0C));
        assertEquals(0x06, reg(connection, 0x0D));
        PCF8523Full.Alarm alarm = rtc.getAlarm();
        assertEquals(45, alarm.minute());
        assertNull(alarm.hour());
        assertNull(alarm.day());
        assertEquals(6, alarm.weekday());
    }

    @Test
    void offsetTwosComplement() throws Exception {
        MockConnection connection = new MockConnection();
        PCF8523Full rtc = new PCF8523Full(connection);
        rtc.setOffset(-64, PCF8523Full.OffsetMode.EVERY_MINUTE);
        assertEquals(0xC0, reg(connection, 0x0E));
        assertEquals(new PCF8523Full.Offset(-64, PCF8523Full.OffsetMode.EVERY_MINUTE), rtc.getOffset());
        rtc.setOffset(63);
        assertEquals(new PCF8523Full.Offset(63, PCF8523Full.OffsetMode.EVERY_TWO_HOURS), rtc.getOffset());
    }

    @Test
    void timersAndClockOutput() throws Exception {
        MockConnection connection = new MockConnection();
        connection.setRegister(0x0F, 0x38);
        PCF8523Full rtc = new PCF8523Full(connection);
        rtc.configureTimerA(PCF8523Full.TimerAMode.WATCHDOG, 5, PCF8523Full.SourceClock.HZ_1, true);
        assertEquals(0x02, reg(connection, 0x10));
        assertEquals(5, reg(connection, 0x11));
        assertEquals(0xBC, reg(connection, 0x0F));
        rtc.enableInterrupt(PCF8523Full.SOURCE_TIMER_A);
        assertEquals(0x04, lastWrite(connection, 0x01) & 0x07);
        rtc.disableTimerA();
        assertEquals(0, reg(connection, 0x0F) & 0x06);

        connection.setRegister(0x0F, 0x38);
        rtc.configureTimerB(30, PCF8523Full.SourceClock.HZ_1_60, 130.0, false);
        assertEquals(0x43, reg(connection, 0x12));
        assertEquals(0x39, reg(connection, 0x0F));

        connection.setRegister(0x0F, 0x00);
        rtc.setClockOutput(1);
        assertEquals(0x30, reg(connection, 0x0F));
        rtc.disableClockOutput();
        assertEquals(0x38, reg(connection, 0x0F));
    }

    @Test
    void batteryBackupDirectWithoutDetection() throws Exception {
        MockConnection connection = new MockConnection();
        PCF8523Full rtc = new PCF8523Full(connection);
        rtc.configureBatteryBackup(PCF8523Full.BatteryMode.DIRECT, false);
        assertEquals(0xA0, reg(connection, 0x02) & 0xE0);
    }

    @Test
    void pollInterruptClearsOnlySetFlags() throws Exception {
        MockConnection connection = new MockConnection();
        PCF8523Full rtc = new PCF8523Full(connection);
        connection.setRegister(0x01, 0xAB);
        connection.setRegister(0x02, 0x0E);
        int status = rtc.pollInterrupt();
        assertEquals(PCF8523Full.SOURCE_TIMER_A | PCF8523Full.SOURCE_TIMER_B | PCF8523Full.SOURCE_ALARM
                | PCF8523Full.SOURCE_BATTERY_SWITCH | PCF8523Full.SOURCE_BATTERY_LOW, status);
        assertEquals(0x53, lastWrite(connection, 0x01));
        assertEquals(0x02, lastWrite(connection, 0x02));
    }

    @Test
    void enableInterruptAndSoftwareReset() throws Exception {
        MockConnection connection = new MockConnection();
        PCF8523Full rtc = new PCF8523Full(connection);
        rtc.enableInterrupt(PCF8523Full.SOURCE_SECOND | PCF8523Full.SOURCE_ALARM | PCF8523Full.SOURCE_BATTERY_LOW);
        assertEquals(0x06, lastWrite(connection, 0x00));
        assertEquals(0x01, lastWrite(connection, 0x02) & 0x03);
        rtc.disableInterrupt(PCF8523Full.SOURCE_ALARM);
        assertEquals(0x04, lastWrite(connection, 0x00));
        rtc.softwareReset();
        assertEquals(0x58, lastWrite(connection, 0x00));
    }
}
