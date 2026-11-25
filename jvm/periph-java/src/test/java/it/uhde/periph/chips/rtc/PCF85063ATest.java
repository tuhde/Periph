package it.uhde.periph.chips.rtc;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PCF85063ATest {

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
    void constructorForces24HourRunning() throws Exception {
        MockConnection connection = new MockConnection();
        connection.setRegister(0x00, 0x37); // STOP, SR, CIE, 12_24, CAP_SEL
        new PCF85063AMinimal(connection);
        // STOP and 12_24 cleared, SR masked, CIE and CAP_SEL preserved.
        assertEquals(0x05, lastWrite(connection, 0x00));
        for (byte[] w : connection.writes()) assertTrue(w.length <= 2, "constructor must not write a time register");
    }

    @Test
    void datetimeDecodeAndStopSequence() throws Exception {
        MockConnection connection = new MockConnection();
        // 2026-09-23 Wednesday(3) 14:30:45, OS flag set.
        connection.setRegister(0x04, 0xC5, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26);
        PCF85063AMinimal rtc = new PCF85063AMinimal(connection);
        connection.setRegister(0x00, 0x02); // 12-hour mode set

        assertEquals(new PCF85063AMinimal.DateTime(2026, 9, 23, 3, 14, 30, 45), rtc.getDatetime());

        int before = connection.writes().size();
        rtc.setDatetime(2026, 9, 23, 3, 14, 30, 45);
        List<byte[]> w = connection.writes().subList(before, connection.writes().size());
        assertArrayEquals(new byte[]{0x00, 0x20}, w.get(1));
        assertArrayEquals(new byte[]{0x04, 0x45, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26}, w.get(2));
        assertArrayEquals(new byte[]{0x00, 0x00}, w.get(3));

        int n = connection.writes().size();
        assertThrows(IllegalArgumentException.class, () -> rtc.setDatetime(2100, 1, 1, 5, 0, 0, 0));
        assertEquals(n, connection.writes().size());
    }

    @Test
    void alarmActiveLowEnablesAndClearsAf() throws Exception {
        MockConnection connection = new MockConnection();
        PCF85063AFull rtc = new PCF85063AFull(connection);
        connection.setRegister(0x01, 0xC8); // AIE, AF, TF
        rtc.setAlarm(new PCF85063AFull.Alarm(5, 45, null, null, 6));
        assertEquals(0x05, reg(connection, 0x0B));
        assertEquals(0x45, reg(connection, 0x0C));
        assertEquals(0x80, reg(connection, 0x0D));
        assertEquals(0x80, reg(connection, 0x0E));
        assertEquals(0x06, reg(connection, 0x0F));
        PCF85063AFull.Alarm alarm = rtc.getAlarm();
        assertEquals(5, alarm.second());
        assertEquals(45, alarm.minute());
        assertNull(alarm.hour());
        assertNull(alarm.day());
        assertEquals(6, alarm.weekday());
        int c2 = lastWrite(connection, 0x01);
        assertEquals(0x08, c2 & 0x48);
        assertEquals(0x80, c2 & 0x80);
    }

    @Test
    void offsetTwosComplement() throws Exception {
        MockConnection connection = new MockConnection();
        PCF85063AFull rtc = new PCF85063AFull(connection);
        rtc.setOffset(-64, PCF85063AFull.OffsetMode.COURSE);
        assertEquals(0xC0, reg(connection, 0x02));
        assertEquals(new PCF85063AFull.Offset(-64, PCF85063AFull.OffsetMode.COURSE), rtc.getOffset());
        rtc.setOffset(63);
        assertEquals(new PCF85063AFull.Offset(63, PCF85063AFull.OffsetMode.NORMAL), rtc.getOffset());
    }

    @Test
    void timerConfigurationAndDisable() throws Exception {
        MockConnection connection = new MockConnection();
        connection.setRegister(0x11, 0x18);
        PCF85063AFull rtc = new PCF85063AFull(connection);
        rtc.configureTimer(30, PCF85063AFull.SourceClock.HZ_1, true);
        assertEquals(30, reg(connection, 0x10));
        assertEquals(0x15, reg(connection, 0x11));
        rtc.enableInterrupt(PCF85063AFull.SOURCE_TIMER);
        assertEquals(0x17, reg(connection, 0x11));
        rtc.disableTimer();
        assertEquals(0x19, reg(connection, 0x11));
        connection.setRegister(0x10, 17);
        assertEquals(17, rtc.readTimer());
    }

    @Test
    void clockOutputAndMinuteInterrupt() throws Exception {
        MockConnection connection = new MockConnection();
        connection.setRegister(0x01, 0x48);
        PCF85063AFull rtc = new PCF85063AFull(connection);
        rtc.setClockOutput(1);
        assertEquals(0x4E, reg(connection, 0x01));
        rtc.disableClockOutput();
        assertEquals(0x07, reg(connection, 0x01) & 0x07);
        rtc.setClockOutput(2048);
        assertEquals(0x04, reg(connection, 0x01) & 0x07);
        rtc.setMinuteInterrupt(PCF85063AFull.MinuteInterrupt.MINUTE);
        assertEquals(0x20, reg(connection, 0x01) & 0x30);
        rtc.setMinuteInterrupt(PCF85063AFull.MinuteInterrupt.HALF_MINUTE);
        assertEquals(0x10, reg(connection, 0x01) & 0x30);
        rtc.setMinuteInterrupt(PCF85063AFull.MinuteInterrupt.OFF);
        assertEquals(0x00, reg(connection, 0x01) & 0x30);
    }

    @Test
    void oscillatorLoadStopStartAndRam() throws Exception {
        MockConnection connection = new MockConnection();
        PCF85063AFull rtc = new PCF85063AFull(connection);
        rtc.setOscillatorLoad(12.5);
        assertEquals(0x01, reg(connection, 0x00) & 0x01);
        rtc.setOscillatorLoad(7);
        assertEquals(0x00, reg(connection, 0x00) & 0x01);
        assertThrows(IllegalArgumentException.class, () -> rtc.setOscillatorLoad(9));
        rtc.stopClock();
        assertEquals(0x20, reg(connection, 0x00) & 0x20);
        rtc.startClock();
        assertEquals(0x00, reg(connection, 0x00) & 0x20);
        connection.setRegister(0x04, 0xD9);
        assertTrue(rtc.oscillatorStopped());
        rtc.clearOscillatorStop();
        assertEquals(0x59, reg(connection, 0x04));
        assertFalse(rtc.oscillatorStopped());
        rtc.writeRam(0xA5);
        assertEquals(0xA5, rtc.readRam());
    }

    @Test
    void pollInterruptMapsAndClearsOnlySetFlags() throws Exception {
        MockConnection connection = new MockConnection();
        PCF85063AFull rtc = new PCF85063AFull(connection);
        // AF set with AIE|MI enabled and COF=5.
        connection.setRegister(0x01, 0xE5);
        assertEquals(PCF85063AFull.SOURCE_ALARM, rtc.pollInterrupt());
        int c2 = lastWrite(connection, 0x01);
        assertEquals(0x00, c2 & 0x40);
        assertEquals(0x08, c2 & 0x08);
        assertEquals(0xA5, c2 & 0xB7);
        connection.setRegister(0x01, 0x08);
        assertEquals(PCF85063AFull.SOURCE_TIMER, rtc.pollInterrupt());
        connection.setRegister(0x01, 0x00);
        assertEquals(0, rtc.pollInterrupt());
    }

    @Test
    void enableAndDisableInterruptsAndSoftwareReset() throws Exception {
        MockConnection connection = new MockConnection();
        PCF85063AFull rtc = new PCF85063AFull(connection);
        rtc.enableInterrupt(PCF85063AFull.SOURCE_ALARM | PCF85063AFull.SOURCE_MINUTE
                | PCF85063AFull.SOURCE_HALF_MINUTE | PCF85063AFull.SOURCE_CORRECTION);
        assertEquals(0xB0, reg(connection, 0x01) & 0xB0);
        assertEquals(0x04, reg(connection, 0x00) & 0x04);
        rtc.disableInterrupt(PCF85063AFull.SOURCE_ALARM | PCF85063AFull.SOURCE_CORRECTION);
        assertEquals(0x30, reg(connection, 0x01) & 0xB0);
        assertEquals(0x00, reg(connection, 0x00) & 0x04);
        rtc.softwareReset();
        assertEquals(0x58, lastWrite(connection, 0x00));
    }
}
