package it.uhde.periph.chips.rfid;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mfrc522Test {

    // SPI addressing: write = (reg<<1)&0x7E, read = write|0x80.
    private static int waddr(int reg) { return (reg << 1) & 0x7E; }
    private static int raddr(int reg) { return waddr(reg) | 0x80; }

    private static List<byte[]> writesAt(MockConnection connection, int addr) {
        List<byte[]> result = new ArrayList<>();
        for (byte[] w : connection.writes()) {
            if (w.length == 2 && (w[0] & 0xFF) == addr) result.add(w);
        }
        return result;
    }

    private static byte lastByte(List<byte[]> writes) {
        return writes.get(writes.size() - 1)[1];
    }

    /**
     * MockConnection plus a FIFO queue: MFRC522 reads FIFO_LEVEL then FIFO_DATA
     * one byte at a time via writeRead(), which the base register-map mock can't
     * model on its own (a plain register always returns the same fixed byte, but
     * FIFO_LEVEL/FIFO_DATA must reflect "how many bytes are left in this
     * response" across several transceive rounds in one call, e.g. readUid()'s
     * REQA -> anticollision -> select -> halt sequence).
     *
     * queueFifo(chunk) queues one whole response as a unit. FIFO_LEVEL reads
     * report the current front chunk's remaining length; FIFO_DATA reads pop one
     * byte from it, and the chunk is dropped once drained so the next queued
     * response becomes visible to the next FIFO_LEVEL read.
     */
    private static class FifoAwareMockConnection extends MockConnection {
        private final Deque<Deque<Integer>> fifoChunks = new ArrayDeque<>();

        void queueFifo(int... chunk) {
            Deque<Integer> q = new ArrayDeque<>();
            for (int b : chunk) q.addLast(b);
            fifoChunks.addLast(q);
        }

        @Override
        public byte[] writeRead(byte[] data, int n) throws IOException {
            int addr = data[0] & 0xFF;
            if (addr == raddr(Mfrc522Minimal.REG_FIFO_LEVEL) && n == 1) {
                writes().add(data.clone());
                int level = fifoChunks.isEmpty() ? 0 : fifoChunks.peekFirst().size();
                return new byte[] { (byte) level };
            }
            if (addr == raddr(Mfrc522Minimal.REG_FIFO_DATA) && n == 1) {
                writes().add(data.clone());
                if (fifoChunks.isEmpty()) return new byte[] { 0 };
                Deque<Integer> front = fifoChunks.peekFirst();
                int b = front.pollFirst();
                if (front.isEmpty()) fifoChunks.pollFirst();
                return new byte[] { (byte) b };
            }
            return super.writeRead(data, n);
        }
    }

    @Test
    void initSequence() throws Exception {
        FifoAwareMockConnection connection = new FifoAwareMockConnection();
        new Mfrc522Full(connection);

        assertEquals((byte) 0x0F, writesAt(connection, waddr(Mfrc522Minimal.REG_COMMAND)).get(0)[1]);
        assertEquals((byte) 0x80, lastByte(writesAt(connection, waddr(Mfrc522Minimal.REG_T_MODE))));
        assertEquals((byte) 0xA9, lastByte(writesAt(connection, waddr(Mfrc522Minimal.REG_T_PRESCALER))));
        assertEquals((byte) 0x03, lastByte(writesAt(connection, waddr(Mfrc522Minimal.REG_T_RELOAD_H))));
        assertEquals((byte) 0xE8, lastByte(writesAt(connection, waddr(Mfrc522Minimal.REG_T_RELOAD_L))));
        assertEquals((byte) 0x40, lastByte(writesAt(connection, waddr(Mfrc522Minimal.REG_TX_ASK))));
        assertEquals((byte) 0x3D, lastByte(writesAt(connection, waddr(Mfrc522Minimal.REG_MODE))));
        assertEquals(0x03, connection.registers().getOrDefault(waddr(Mfrc522Minimal.REG_TX_CONTROL), 0) & 0x03);
    }

    @Test
    void isCardPresentTrue() throws Exception {
        FifoAwareMockConnection connection = new FifoAwareMockConnection();
        Mfrc522Full sensor = new Mfrc522Full(connection);
        connection.setRegister(raddr(Mfrc522Minimal.REG_COM_IRQ), 0x30);
        connection.setRegister(raddr(Mfrc522Minimal.REG_ERROR), 0x00);
        connection.queueFifo(0x04, 0x00);

        assertTrue(sensor.isCardPresent());
    }

    @Test
    void isCardPresentFalse() throws Exception {
        FifoAwareMockConnection connection = new FifoAwareMockConnection();
        Mfrc522Full sensor = new Mfrc522Full(connection);
        connection.setRegister(raddr(Mfrc522Minimal.REG_COM_IRQ), 0x01); // TimerIRq only -> no card

        assertFalse(sensor.isCardPresent());
    }

    @Test
    void readUidHappyPath() throws Exception {
        FifoAwareMockConnection connection = new FifoAwareMockConnection();
        Mfrc522Full sensor = new Mfrc522Full(connection);
        int[] uidBytes = { 0x12, 0x34, 0x56, 0x78 };
        int bcc = 0;
        for (int b : uidBytes) bcc ^= b;

        connection.setRegister(raddr(Mfrc522Minimal.REG_COM_IRQ), 0x30);
        connection.setRegister(raddr(Mfrc522Minimal.REG_ERROR), 0x00);
        connection.queueFifo(0x04, 0x00); // REQA response (isCardPresent(), called first by readUid())
        connection.queueFifo(uidBytes[0], uidBytes[1], uidBytes[2], uidBytes[3], bcc); // anticollision CL1
        connection.queueFifo(0x00); // select CL1 SAK: completion bit clear, single-size UID
        // HLTA (halt) result is ignored by the driver - no response bytes needed

        byte[] uid = sensor.readUid();
        byte[] expected = new byte[4];
        for (int i = 0; i < 4; i++) expected[i] = (byte) uidBytes[i];
        assertArrayEquals(expected, uid);
    }

    @Test
    void readUidNoCard() throws Exception {
        FifoAwareMockConnection connection = new FifoAwareMockConnection();
        Mfrc522Full sensor = new Mfrc522Full(connection);
        connection.setRegister(raddr(Mfrc522Minimal.REG_COM_IRQ), 0x01); // TimerIRq only -> no card

        assertNull(sensor.readUid());
    }

    @Test
    void antennaControl() throws Exception {
        FifoAwareMockConnection connection = new FifoAwareMockConnection();
        Mfrc522Full sensor = new Mfrc522Full(connection);

        sensor.antennaOff();
        assertEquals(0, connection.registers().get(waddr(Mfrc522Minimal.REG_TX_CONTROL)) & 0x03);
        sensor.antennaOn();
        assertEquals(0x03, connection.registers().get(waddr(Mfrc522Minimal.REG_TX_CONTROL)) & 0x03);

        sensor.setAntennaGain(38);
        assertEquals(0x50, connection.registers().get(waddr(Mfrc522Minimal.REG_RF_CFG)) & 0x70);

        connection.setRegister(raddr(Mfrc522Minimal.REG_RF_CFG), 0x60);
        assertEquals(43, sensor.antennaGain());
    }

    @Test
    void setAntennaGainInvalidThrows() throws Exception {
        FifoAwareMockConnection connection = new FifoAwareMockConnection();
        Mfrc522Full sensor = new Mfrc522Full(connection);

        assertThrows(IllegalArgumentException.class, () -> sensor.setAntennaGain(99));
    }

    @Test
    void version() throws Exception {
        FifoAwareMockConnection connection = new FifoAwareMockConnection();
        Mfrc522Full sensor = new Mfrc522Full(connection);
        connection.setRegister(raddr(Mfrc522Minimal.REG_VERSION), 0x92); // chipType=9, version=2

        assertArrayEquals(new int[] { 9, 2 }, sensor.version());
    }

    @Test
    void selfTestPass() throws Exception {
        FifoAwareMockConnection connection = new FifoAwareMockConnection();
        Mfrc522Full sensor = new Mfrc522Full(connection);
        connection.setRegister(raddr(Mfrc522Minimal.REG_VERSION), 0x91); // version=1 -> v1.0 reference table
        int[] refV10 = {
            0x00, 0x87, 0x98, 0x0F, 0x49, 0xFF, 0x07, 0x19,
            0xBF, 0x22, 0x30, 0x49, 0x59, 0x63, 0xAD, 0xCA,
            0x7F, 0xE3, 0x4E, 0x03, 0x5C, 0x4E, 0x49, 0x50,
            0x47, 0x9A, 0x37, 0x61, 0xE7, 0xE2, 0xC6, 0x2E,
            0x75, 0x5A, 0xED, 0x04, 0x3D, 0x02, 0x4B, 0x78,
            0x32, 0xFF, 0x58, 0x3B, 0x7C, 0xE9, 0x00, 0x94,
            0xB4, 0x4A, 0x59, 0x5B, 0xFD, 0xC9, 0x29, 0xDF,
            0x35, 0x96, 0x98, 0x9E, 0x4F, 0x30, 0x32, 0x8D,
        };
        connection.queueFifo(refV10);

        assertTrue(sensor.selfTest());
    }

    @Test
    void authenticateAndStopCrypto() throws Exception {
        FifoAwareMockConnection connection = new FifoAwareMockConnection();
        Mfrc522Full sensor = new Mfrc522Full(connection);
        int[] uidBytes = { 0x12, 0x34, 0x56, 0x78 };
        byte[] uid = new byte[4];
        for (int i = 0; i < 4; i++) uid[i] = (byte) uidBytes[i];
        connection.setRegister(raddr(Mfrc522Minimal.REG_STATUS_2), 0x08); // MFCrypto1On set immediately

        assertTrue(sensor.authenticate(4, Mfrc522Full.KEY_A, new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF }, uid));

        sensor.stopCrypto();
        assertEquals(0, connection.registers().get(waddr(Mfrc522Minimal.REG_STATUS_2)) & 0x08);
    }

    @Test
    void authenticateBadKeyLength() throws Exception {
        FifoAwareMockConnection connection = new FifoAwareMockConnection();
        Mfrc522Full sensor = new Mfrc522Full(connection);
        byte[] uid = { 0x12, 0x34, 0x56, 0x78 };

        assertFalse(sensor.authenticate(4, Mfrc522Full.KEY_A, new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF }, uid));
    }

    @Test
    void readBlock() throws Exception {
        FifoAwareMockConnection connection = new FifoAwareMockConnection();
        Mfrc522Full sensor = new Mfrc522Full(connection);
        byte[] blockData = new byte[16];
        for (int i = 0; i < 16; i++) blockData[i] = (byte) i;
        // calcCrc() polls DIV_IRQ; make it show CRCIRq set immediately, and preload
        // CRC_RESULT_H/L with a fixed placeholder - the driver just forwards
        // whatever the chip returns as the trailing 2 command bytes.
        connection.setRegister(raddr(Mfrc522Minimal.REG_DIV_IRQ), 0x04);
        connection.setRegister(raddr(0x21), 0xAB); // CRC_RESULT_H
        connection.setRegister(raddr(0x22), 0xCD); // CRC_RESULT_L
        connection.setRegister(raddr(Mfrc522Minimal.REG_COM_IRQ), 0x30);
        connection.setRegister(raddr(Mfrc522Minimal.REG_ERROR), 0x00);
        connection.queueFifo(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);

        assertArrayEquals(blockData, sensor.readBlock(4));
    }

    @Test
    void writeBlock() throws Exception {
        FifoAwareMockConnection connection = new FifoAwareMockConnection();
        Mfrc522Full sensor = new Mfrc522Full(connection);
        byte[] blockData = new byte[16];
        for (int i = 0; i < 16; i++) blockData[i] = (byte) i;
        connection.setRegister(raddr(Mfrc522Minimal.REG_DIV_IRQ), 0x04);
        connection.setRegister(raddr(0x21), 0xAB);
        connection.setRegister(raddr(0x22), 0xCD);
        connection.setRegister(raddr(Mfrc522Minimal.REG_COM_IRQ), 0x30);
        connection.setRegister(raddr(Mfrc522Minimal.REG_ERROR), 0x00);
        connection.queueFifo(0x0A); // phase 1 ACK (0x0A in low nibble)
        connection.queueFifo(0x0A); // phase 2 ACK

        assertTrue(sensor.writeBlock(4, blockData));
    }
}
