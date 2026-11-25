package it.uhde.periph.chips.motor;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DRV2605Test {

    /** Mock whose DEV_RESET and GO bits self-clear like the real chip. */
    static class ChipMock extends MockConnection {
        ChipMock() { setRegister(0x00, 0x60); }

        @Override
        public void write(byte[] data) throws IOException {
            super.write(data);
            if (data.length >= 2 && data[0] == 0x01) setRegister(0x01, data[1] & 0x7F);
            if (data.length >= 2 && data[0] == 0x0C) setRegister(0x0C, 0);
        }
    }

    private static byte[] lastWrite(MockConnection c, int reg) {
        byte[] out = null;
        for (byte[] w : c.writes()) {
            if (w.length >= 2 && (w[0] & 0xFF) == reg) out = Arrays.copyOfRange(w, 1, w.length);
        }
        return out;
    }

    private static int lastByte(MockConnection c, int reg) {
        byte[] v = lastWrite(c, reg);
        return v == null ? -1 : v[0] & 0xFF;
    }

    private static boolean wrote(MockConnection c, int reg, int value) {
        for (byte[] w : c.writes()) {
            if (w.length == 2 && (w[0] & 0xFF) == reg && (w[1] & 0xFF) == value) return true;
        }
        return false;
    }

    @Test
    void initSequenceErmAndLra() throws Exception {
        ChipMock erm = new ChipMock();
        new DRV2605Minimal(erm);
        assertArrayEquals(new byte[]{0x01, (byte) 0x80}, erm.writes().get(0));
        assertTrue(wrote(erm, 0x01, 0x00));
        assertEquals(0x36, lastByte(erm, 0x1A));
        assertEquals(0x01, lastByte(erm, 0x03));
        ChipMock lra = new ChipMock();
        new DRV2605Minimal(lra, true);
        assertEquals(0xB6, lastByte(lra, 0x1A));
        assertEquals(0x06, lastByte(lra, 0x03));
    }

    @Test
    void initRejectsWrongDeviceId() {
        ChipMock c = new ChipMock();
        c.setRegister(0x00, 0xE0);
        assertThrows(IllegalStateException.class, () -> new DRV2605Minimal(c));
    }

    @Test
    void minimalPlayback() throws Exception {
        ChipMock c = new ChipMock();
        DRV2605Minimal chip = new DRV2605Minimal(c);
        chip.playEffect(47);
        assertArrayEquals(new byte[]{47, 0}, lastWrite(c, 0x04));
        assertEquals(1, lastByte(c, 0x0C));
        assertThrows(IllegalArgumentException.class, () -> chip.playEffect(0));
        assertThrows(IllegalArgumentException.class, () -> chip.playEffect(124));
        chip.stop();
        assertEquals(0, lastByte(c, 0x0C));
        c.setRegister(0x0C, 1);
        assertTrue(chip.isPlaying());
        c.setRegister(0x0C, 0);
        assertFalse(chip.isPlaying());
    }

    @Test
    void sequenceEncodingAndWaitDone() throws Exception {
        ChipMock c = new ChipMock();
        DRV2605Full chip = new DRV2605Full(c);
        chip.playSequence(DRV2605Full.SequenceStep.effect(1), DRV2605Full.SequenceStep.delay(100),
                DRV2605Full.SequenceStep.effect(10));
        assertArrayEquals(new byte[]{1, (byte) 0x8A, 10, 0}, lastWrite(c, 0x04));
        DRV2605Full.SequenceStep[] eight = new DRV2605Full.SequenceStep[8];
        for (int i = 0; i < 8; i++) eight[i] = DRV2605Full.SequenceStep.effect(i + 1);
        chip.playSequence(eight);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, lastWrite(c, 0x04));
        assertThrows(IllegalArgumentException.class,
                () -> chip.playSequence(new DRV2605Full.SequenceStep[9]));
        assertThrows(IllegalArgumentException.class,
                () -> chip.playSequence(DRV2605Full.SequenceStep.delay(2000)));
        c.setRegister(0x0C, 0);
        assertTrue(chip.waitDone(50));
        c.setRegister(0x0C, 1);
        assertFalse(chip.waitDone(20));
    }

    @Test
    void modeStandbyLibraryAndHiZ() throws Exception {
        ChipMock c = new ChipMock();
        DRV2605Full chip = new DRV2605Full(c);
        chip.setMode(5);
        assertEquals(0x05, lastByte(c, 0x01));
        chip.standby();
        assertEquals(0x45, lastByte(c, 0x01));
        chip.wake();
        assertEquals(0x05, lastByte(c, 0x01));
        chip.setHiZ(true);
        assertEquals(0x10, lastByte(c, 0x03) & 0x10);
        chip.setLibrary(6);
        assertEquals(0x16, lastByte(c, 0x03));
        assertThrows(IllegalArgumentException.class, () -> chip.setLibrary(7));
        c.setRegister(0x1A, 0x36);
        chip.setActuator(true);
        assertEquals(0xB6, lastByte(c, 0x1A));
    }

    @Test
    void realTimePlayback() throws Exception {
        ChipMock c = new ChipMock();
        DRV2605Full chip = new DRV2605Full(c);
        chip.rtpLevel(0.5);
        assertTrue(wrote(c, 0x01, 0x05));
        assertEquals(64, lastByte(c, 0x02));
        chip.rtpWrite(200);
        assertEquals(200, lastByte(c, 0x02));
        chip.stop();
        assertEquals(0, lastByte(c, 0x02));
        assertEquals(0x00, lastByte(c, 0x01));
        assertThrows(IllegalArgumentException.class, () -> chip.rtpLevel(1.5));
        chip.setRtpFormat(true);
        assertEquals(0x08, lastByte(c, 0x1D) & 0x08);
    }

    @Test
    void loopConstraint() throws Exception {
        ChipMock c = new ChipMock();
        DRV2605Full chip = new DRV2605Full(c);
        c.setRegister(0x1D, 0x20);
        assertThrows(IllegalStateException.class, () -> chip.setBidirectional(false));
        chip.setOpenLoop(false);
        assertEquals(0x00, lastByte(c, 0x1D));
        chip.setBidirectional(false);
        assertEquals(0, lastByte(c, 0x1C) & 0x80);
    }

    @Test
    void voltageConversions() throws Exception {
        ChipMock c = new ChipMock();
        DRV2605Full chip = new DRV2605Full(c);
        c.setRegister(0x1A, 0x36);
        chip.setRatedVoltage(3.0);
        assertEquals(141, lastByte(c, 0x16));
        chip.setRatedVoltageCode(77);
        assertEquals(77, lastByte(c, 0x16));
        c.setRegister(0x1D, 0x20);
        chip.setOverdriveClamp(3.0);
        assertEquals(137, lastByte(c, 0x17));
        c.setRegister(0x1D, 0x00);
        assertThrows(IllegalStateException.class, () -> chip.setOverdriveClamp(3.0));
        c.setRegister(0x1A, 0xB6);
        c.setRegister(0x1C, 0x30);
        chip.setRatedVoltage(2.0, 200.0);
        assertEquals(81, lastByte(c, 0x16));
        assertThrows(IllegalArgumentException.class, () -> chip.setRatedVoltage(2.0));
        chip.setDriveTime(2.5);
        assertEquals(20, lastByte(c, 0x1B) & 0x1F);
        c.setRegister(0x1A, 0x36);
        chip.setDriveTime(5.0);
        assertEquals(20, lastByte(c, 0x1B) & 0x1F);
    }

    @Test
    void feedbackAndOffsets() throws Exception {
        ChipMock c = new ChipMock();
        DRV2605Full chip = new DRV2605Full(c);
        c.setRegister(0x1A, 0x80);
        chip.setFeedback(2, 2, 1);
        assertEquals(0x80 | (2 << 4) | (2 << 2) | 1, lastByte(c, 0x1A));
        chip.setTimeOffsets(10, -5, 0, -640);
        assertArrayEquals(new byte[]{2, (byte) 0xFF, 0, (byte) 0x80}, lastWrite(c, 0x0D));
        assertThrows(IllegalArgumentException.class, () -> chip.setTimeOffsets(0, 0, 0, 700));
    }

    @Test
    void calibrationFlow() throws Exception {
        ChipMock c = new ChipMock();
        DRV2605Full chip = new DRV2605Full(c);
        c.setRegister(0x18, 0x0D, 0x6D);
        c.setRegister(0x1A, 0x36);
        DRV2605Full.Calibration r = chip.calibrate(false, 3.0, 3.0);
        assertTrue(wrote(c, 0x01, 0x07));
        assertEquals(141, lastByte(c, 0x16));
        assertEquals(137, lastByte(c, 0x17));
        assertEquals(0x30, lastByte(c, 0x1E) & 0x30);
        assertTrue(wrote(c, 0x0C, 0x01));
        assertEquals(new DRV2605Full.Calibration(true, 0x0D, 0x6D, 2), r);
        assertEquals(0x00, lastByte(c, 0x01));
        c.setRegister(0x00, 0x68);
        assertFalse(chip.calibrate(false, 3.0, 3.0).ok());
        c.setRegister(0x00, 0x60);
        assertThrows(IllegalArgumentException.class, () -> chip.calibrate(true, 2.0, 2.5));
        chip.calibrate(true, 2.0, 2.5, 200.0);
        assertEquals(0x80, lastByte(c, 0x1A) & 0x80);
        chip.setCalibration(0x20, 0x80, 3);
        assertArrayEquals(new byte[]{0x20, (byte) 0x80}, lastWrite(c, 0x18));
        assertEquals(new DRV2605Full.Calibration(true, 0x20, 0x80, 3), chip.getCalibration());
    }

    @Test
    void diagnose() throws Exception {
        ChipMock c = new ChipMock();
        DRV2605Full chip = new DRV2605Full(c);
        assertTrue(chip.diagnose());
        assertTrue(wrote(c, 0x01, 0x06));
        c.setRegister(0x00, 0x68);
        assertFalse(chip.diagnose());
    }

    @Test
    void audioToVibe() throws Exception {
        ChipMock c = new ChipMock();
        DRV2605Full chip = new DRV2605Full(c);
        chip.setAudioToVibe(true, 30, 150, 0.0, 1.8, 0.0, 1.0);
        assertArrayEquals(new byte[]{(2 << 2) | 2, 0, (byte) 255, 0, (byte) 255}, lastWrite(c, 0x11));
        assertEquals(0x04, lastByte(c, 0x01));
        assertEquals(0x20, lastByte(c, 0x1B) & 0x20);
        assertThrows(IllegalArgumentException.class, () -> chip.setAudioToVibe(true, 20, 300, 0.1, 1.8, 0.1, 1.0));
    }

    @Test
    void statusAndReadouts() throws Exception {
        ChipMock c = new ChipMock();
        DRV2605Full chip = new DRV2605Full(c);
        c.setRegister(0x00, 0x60 | 0x08 | 0x01);
        assertEquals(new DRV2605Full.Status(true, false, false, true), chip.readStatus());
        assertEquals(3, chip.readDeviceId());
        c.setRegister(0x21, 255);
        assertEquals(5.6, chip.readSupplyVoltage(), 1e-9);
        c.setRegister(0x22, 0);
        assertTrue(chip.readLraFrequency().isEmpty());
        c.setRegister(0x22, 52);
        assertEquals(195.3, chip.readLraFrequency().getAsDouble(), 0.2);
    }

    @Test
    void otpGuards() throws Exception {
        ChipMock c = new ChipMock();
        DRV2605Full chip = new DRV2605Full(c);
        int n = c.writes().size();
        assertThrows(IllegalArgumentException.class, () -> chip.programOtp(false));
        assertEquals(n, c.writes().size());
        c.setRegister(0x1E, 0x04);
        assertTrue(chip.otpProgrammed());
        assertThrows(IllegalStateException.class, () -> chip.programOtp(true));
    }
}
