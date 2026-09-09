package it.uhde.periph.chips.rfid

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Mfrc522Spec extends Specification {

    // SPI addressing: write = (reg<<1)&0x7E, read = write|0x80.
    private static int waddr(int reg) { (reg << 1) & 0x7E }
    private static int raddr(int reg) { waddr(reg) | 0x80 }

    private static List<byte[]> writesAt(MockConnection connection, int addr) {
        connection.writes().findAll { it.length == 2 && (it[0] & 0xFF) == addr }
    }

    private static byte lastByte(List<byte[]> writes) {
        writes[writes.size() - 1][1]
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
        private final List<List<Integer>> fifoChunks = []

        void queueFifo(int... chunk) {
            fifoChunks << (chunk as List<Integer>)
        }

        @Override
        byte[] writeRead(byte[] data, int n) {
            int addr = data[0] & 0xFF
            if (addr == raddr(Mfrc522Minimal.REG_FIFO_LEVEL) && n == 1) {
                writes() << data.clone()
                int level = fifoChunks.isEmpty() ? 0 : fifoChunks[0].size()
                return [(byte) level] as byte[]
            }
            if (addr == raddr(Mfrc522Minimal.REG_FIFO_DATA) && n == 1) {
                writes() << data.clone()
                if (fifoChunks.isEmpty()) return [(byte) 0] as byte[]
                def front = fifoChunks[0]
                int b = front.remove(0)
                if (front.isEmpty()) fifoChunks.remove(0)
                return [(byte) b] as byte[]
            }
            return super.writeRead(data, n)
        }
    }

    def "init sequence"() {
        given:
        def connection = new FifoAwareMockConnection()

        when:
        new Mfrc522Full(connection)

        then:
        writesAt(connection, waddr(Mfrc522Minimal.REG_COMMAND))[0][1] == (byte) 0x0F
        lastByte(writesAt(connection, waddr(Mfrc522Minimal.REG_T_MODE))) == (byte) 0x80
        lastByte(writesAt(connection, waddr(Mfrc522Minimal.REG_T_PRESCALER))) == (byte) 0xA9
        lastByte(writesAt(connection, waddr(Mfrc522Minimal.REG_T_RELOAD_H))) == (byte) 0x03
        lastByte(writesAt(connection, waddr(Mfrc522Minimal.REG_T_RELOAD_L))) == (byte) 0xE8
        lastByte(writesAt(connection, waddr(Mfrc522Minimal.REG_TX_ASK))) == (byte) 0x40
        lastByte(writesAt(connection, waddr(Mfrc522Minimal.REG_MODE))) == (byte) 0x3D
        (connection.registers().getOrDefault(waddr(Mfrc522Minimal.REG_TX_CONTROL), 0) & 0x03) == 0x03
    }

    def "is card present true"() {
        given:
        def connection = new FifoAwareMockConnection()
        def sensor = new Mfrc522Full(connection)
        connection.setRegister(raddr(Mfrc522Minimal.REG_COM_IRQ), 0x30)
        connection.setRegister(raddr(Mfrc522Minimal.REG_ERROR), 0x00)
        connection.queueFifo(0x04, 0x00)

        expect:
        sensor.isCardPresent()
    }

    def "is card present false"() {
        given:
        def connection = new FifoAwareMockConnection()
        def sensor = new Mfrc522Full(connection)
        connection.setRegister(raddr(Mfrc522Minimal.REG_COM_IRQ), 0x01) // TimerIRq only -> no card

        expect:
        !sensor.isCardPresent()
    }

    def "read uid happy path"() {
        given:
        def connection = new FifoAwareMockConnection()
        def sensor = new Mfrc522Full(connection)
        int[] uidBytes = [0x12, 0x34, 0x56, 0x78]
        int bcc = 0
        uidBytes.each { bcc ^= it }

        connection.setRegister(raddr(Mfrc522Minimal.REG_COM_IRQ), 0x30)
        connection.setRegister(raddr(Mfrc522Minimal.REG_ERROR), 0x00)
        connection.queueFifo(0x04, 0x00) // REQA response (isCardPresent(), called first by readUid())
        connection.queueFifo(uidBytes[0], uidBytes[1], uidBytes[2], uidBytes[3], bcc) // anticollision CL1
        connection.queueFifo(0x00) // select CL1 SAK: completion bit clear, single-size UID
        // HLTA (halt) result is ignored by the driver - no response bytes needed

        when:
        byte[] uid = sensor.readUid()

        then:
        uid == (uidBytes.collect { (byte) it } as byte[])
    }

    def "read uid no card"() {
        given:
        def connection = new FifoAwareMockConnection()
        def sensor = new Mfrc522Full(connection)
        connection.setRegister(raddr(Mfrc522Minimal.REG_COM_IRQ), 0x01) // TimerIRq only -> no card

        expect:
        sensor.readUid() == null
    }

    def "antenna control"() {
        given:
        def connection = new FifoAwareMockConnection()
        def sensor = new Mfrc522Full(connection)

        when:
        sensor.antennaOff()

        then:
        (connection.registers().get(waddr(Mfrc522Minimal.REG_TX_CONTROL)) & 0x03) == 0

        when:
        sensor.antennaOn()

        then:
        (connection.registers().get(waddr(Mfrc522Minimal.REG_TX_CONTROL)) & 0x03) == 0x03

        when:
        sensor.setAntennaGain(38)

        then:
        (connection.registers().get(waddr(Mfrc522Minimal.REG_RF_CFG)) & 0x70) == 0x50

        when:
        connection.setRegister(raddr(Mfrc522Minimal.REG_RF_CFG), 0x60)

        then:
        sensor.antennaGain() == 43
    }

    def "set antenna gain invalid throws"() {
        given:
        def connection = new FifoAwareMockConnection()
        def sensor = new Mfrc522Full(connection)

        when:
        sensor.setAntennaGain(99)

        then:
        thrown(IllegalArgumentException)
    }

    def "version"() {
        given:
        def connection = new FifoAwareMockConnection()
        def sensor = new Mfrc522Full(connection)
        connection.setRegister(raddr(Mfrc522Minimal.REG_VERSION), 0x92) // chipType=9, version=2

        expect:
        sensor.version() == [9, 2] as int[]
    }

    def "self test pass"() {
        given:
        def connection = new FifoAwareMockConnection()
        def sensor = new Mfrc522Full(connection)
        connection.setRegister(raddr(Mfrc522Minimal.REG_VERSION), 0x91) // version=1 -> v1.0 reference table
        int[] refV10 = [
            0x00, 0x87, 0x98, 0x0F, 0x49, 0xFF, 0x07, 0x19,
            0xBF, 0x22, 0x30, 0x49, 0x59, 0x63, 0xAD, 0xCA,
            0x7F, 0xE3, 0x4E, 0x03, 0x5C, 0x4E, 0x49, 0x50,
            0x47, 0x9A, 0x37, 0x61, 0xE7, 0xE2, 0xC6, 0x2E,
            0x75, 0x5A, 0xED, 0x04, 0x3D, 0x02, 0x4B, 0x78,
            0x32, 0xFF, 0x58, 0x3B, 0x7C, 0xE9, 0x00, 0x94,
            0xB4, 0x4A, 0x59, 0x5B, 0xFD, 0xC9, 0x29, 0xDF,
            0x35, 0x96, 0x98, 0x9E, 0x4F, 0x30, 0x32, 0x8D,
        ]
        connection.queueFifo(*refV10)

        expect:
        sensor.selfTest()
    }

    def "authenticate and stop crypto"() {
        given:
        def connection = new FifoAwareMockConnection()
        def sensor = new Mfrc522Full(connection)
        byte[] uid = [0x12, 0x34, 0x56, 0x78] as byte[]
        connection.setRegister(raddr(Mfrc522Minimal.REG_STATUS_2), 0x08) // MFCrypto1On set immediately

        expect:
        sensor.authenticate(4, Mfrc522Full.KEY_A, [0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF] as byte[], uid)

        when:
        sensor.stopCrypto()

        then:
        (connection.registers().get(waddr(Mfrc522Minimal.REG_STATUS_2)) & 0x08) == 0
    }

    def "authenticate bad key length"() {
        given:
        def connection = new FifoAwareMockConnection()
        def sensor = new Mfrc522Full(connection)
        byte[] uid = [0x12, 0x34, 0x56, 0x78] as byte[]

        expect:
        !sensor.authenticate(4, Mfrc522Full.KEY_A, [0xFF, 0xFF, 0xFF, 0xFF, 0xFF] as byte[], uid)
    }

    def "read block"() {
        given:
        def connection = new FifoAwareMockConnection()
        def sensor = new Mfrc522Full(connection)
        byte[] blockData = (0..15).collect { (byte) it } as byte[]
        // calcCrc() polls DIV_IRQ; make it show CRCIRq set immediately, and preload
        // CRC_RESULT_H/L with a fixed placeholder - the driver just forwards
        // whatever the chip returns as the trailing 2 command bytes.
        connection.setRegister(raddr(Mfrc522Minimal.REG_DIV_IRQ), 0x04)
        connection.setRegister(raddr(0x21), 0xAB) // CRC_RESULT_H
        connection.setRegister(raddr(0x22), 0xCD) // CRC_RESULT_L
        connection.setRegister(raddr(Mfrc522Minimal.REG_COM_IRQ), 0x30)
        connection.setRegister(raddr(Mfrc522Minimal.REG_ERROR), 0x00)
        connection.queueFifo(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)

        expect:
        sensor.readBlock(4) == blockData
    }

    def "write block"() {
        given:
        def connection = new FifoAwareMockConnection()
        def sensor = new Mfrc522Full(connection)
        byte[] blockData = (0..15).collect { (byte) it } as byte[]
        connection.setRegister(raddr(Mfrc522Minimal.REG_DIV_IRQ), 0x04)
        connection.setRegister(raddr(0x21), 0xAB)
        connection.setRegister(raddr(0x22), 0xCD)
        connection.setRegister(raddr(Mfrc522Minimal.REG_COM_IRQ), 0x30)
        connection.setRegister(raddr(Mfrc522Minimal.REG_ERROR), 0x00)
        connection.queueFifo(0x0A) // phase 1 ACK (0x0A in low nibble)
        connection.queueFifo(0x0A) // phase 2 ACK

        expect:
        sensor.writeBlock(4, blockData)
    }
}
