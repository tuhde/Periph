package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.OutputPin
import spock.lang.Specification

class Adxl362Spec extends Specification {

    static final int CMD_WRITE_REG = 0x0A
    static final int CMD_READ_REG = 0x0B

    // In-memory fake Connection for ADXL362 unit tests. ADXL362's SPI
    // framing is opcode+address(+data) -- WRITE 0x0A addr data, READ 0x0B
    // addr -- unlike MockConnection's single-byte-is-the-address convention,
    // so the real target register is the SECOND byte of a write/writeRead.
    static class Adxl362MockConnection implements Connection {
        Map<Integer, Integer> registers = [:]
        List<byte[]> writes = []

        void setRegister(int reg, int... values) {
            values.eachWithIndex { v, i -> registers[reg + i] = v & 0xFF }
        }

        void enable() {}
        void disable() {}
        boolean isEnabled() { true }
        InputPin intPin() { null }
        OutputPin enPin() { null }

        void write(byte[] data) {
            writes << data.clone()
            if (data.length >= 3 && (data[0] & 0xFF) == CMD_WRITE_REG) {
                int reg = data[1] & 0xFF
                for (int i = 2; i < data.length; i++) registers[reg + i - 2] = data[i] & 0xFF
            }
        }

        byte[] read(int n) { new byte[n] }

        byte[] writeRead(byte[] data, int n) {
            writes << data.clone()
            int reg
            if (data.length == 2 && (data[0] & 0xFF) == CMD_READ_REG) {
                reg = data[1] & 0xFF
            } else if (data.length > 0) {
                reg = data[0] & 0xFF
            } else {
                return new byte[n]
            }
            byte[] out = new byte[n]
            for (int i = 0; i < n; i++) out[i] = (byte) (registers.get(reg + i, 0))
            return out
        }

        void close() {}
    }

    static Adxl362MockConnection newConnection() {
        def c = new Adxl362MockConnection()
        c.setRegister(0x00, 0xAD, 0x1D, 0xF2, 0x01) // DEVID_AD, DEVID_MST, PARTID, REVID
        return c
    }

    static boolean lastWriteEquals(Adxl362MockConnection conn, int... bytes) {
        byte[] want = new byte[bytes.length]
        bytes.eachWithIndex { b, i -> want[i] = (byte) b }
        return conn.writes[-1] == want
    }

    static boolean writeAtEquals(Adxl362MockConnection conn, int fromEnd, int... bytes) {
        byte[] want = new byte[bytes.length]
        bytes.eachWithIndex { b, i -> want[i] = (byte) b }
        return conn.writes[conn.writes.size() - fromEnd] == want
    }

    def "construction and read"() {
        given:
        def conn = newConnection()

        when:
        def chip = new Adxl362Minimal(conn)

        then:
        writeAtEquals(conn, 2, 0x0A, 0x2C, 0x13)
        lastWriteEquals(conn, 0x0A, 0x2D, 0x02)

        when:
        conn.setRegister(0x0E, 0x64, 0x00, 0xCE, 0x0F, 0xD0, 0x07) // x=100,y=-50,z=2000 raw
        def xyz = chip.read()

        then:
        Math.abs(xyz[0] - 0.1f) < 1e-6f
        Math.abs(xyz[1] - (-0.05f)) < 1e-6f
        Math.abs(xyz[2] - 2.0f) < 1e-6f
    }

    def "device id and soft reset"() {
        given:
        def conn = newConnection()
        def full = new Adxl362Full(conn)

        when:
        conn.setRegister(0x00, 0xAD, 0x1D, 0xF2, 0x07)

        then:
        full.deviceId() == [0xAD, 0x1D, 0xF2, 0x07] as int[]

        when:
        full.softReset()

        then:
        lastWriteEquals(conn, 0x0A, 0x1F, 0x52)
    }

    def "set range and set odr"() {
        given:
        def conn = newConnection()
        def full = new Adxl362Full(conn)

        when:
        conn.setRegister(0x2C, 0x13)
        full.setRange(4)

        then:
        lastWriteEquals(conn, 0x0A, 0x2C, 0x53)

        when:
        conn.setRegister(0x2C, 0x53)
        full.setRange(8)

        then:
        lastWriteEquals(conn, 0x0A, 0x2C, 0x93)

        when:
        conn.setRegister(0x2C, 0x93)
        full.setOdr(60.0f) // nearest of 50/100 -> 50 Hz (code 0x02)

        then:
        lastWriteEquals(conn, 0x0A, 0x2C, 0x92)
    }

    def "read8bit temperature status"() {
        given:
        def conn = newConnection()
        def full = new Adxl362Full(conn)
        conn.setRegister(0x2C, 0x92)
        full.setRange(8)

        when:
        conn.setRegister(0x08, 100, 206, 50) // x=100, y=-50 (0xCE), z=50
        def r8 = full.read8bit()
        float sens8 = 0.004255f * 16.0f

        then:
        Math.abs(r8[0] - 100 * sens8) < 1e-3f
        Math.abs(r8[1] - (-50) * sens8) < 1e-3f
        Math.abs(r8[2] - 50 * sens8) < 1e-3f

        when:
        conn.setRegister(0x14, 0xAB, 0x01) // raw 427 = 30 C

        then:
        Math.abs(full.temperature() - 30.0f) < 0.01f

        when:
        conn.setRegister(0x0B, 0x41) // AWAKE + DATA_READY

        then:
        full.status() == 0x41
        full.awake()
        full.dataReady()
    }

    def "fifo configure and read"() {
        given:
        def conn = newConnection()
        def full = new Adxl362Full(conn)

        when:
        conn.setRegister(0x0C, 0xFF, 0x01) // 0x1FF = 511

        then:
        full.fifoEntries() == 0x1FF

        when:
        full.configureFifo(Adxl362Full.FIFO_STREAM, true, 300)

        then:
        writeAtEquals(conn, 2, 0x0A, 0x28, 0x0E)
        lastWriteEquals(conn, 0x0A, 0x29, 0x2C)

        when:
        conn.setRegister(0x0C, 2, 0) // 2 entries
        conn.setRegister(0x0D, 100, 0, 171, 193)
        def entries = full.readFifo()

        then:
        entries.length == 2
        entries[0][0] as int == Adxl362Full.AXIS_X
        Math.abs(entries[0][1] - 100 * 0.001f) < 1e-6f
        entries[1][0] as int == Adxl362Full.AXIS_TEMP
        Math.abs(entries[1][1] - 30.0f) < 0.01f
    }

    def "activity threshold 11-bit regression"() {
        // Regression: THRESH_ACT_H/THRESH_INACT_H are documented as bits
        // [10:8] (3 bits, 11-bit total threshold) -- must not clamp to
        // 10-bit (0x3FF) or mask the H byte with 0x03.
        given:
        def conn = newConnection()
        def full = new Adxl362Full(conn)
        conn.setRegister(0x2C, 0x92)
        full.setRange(2)

        when:
        conn.setRegister(0x27, 0x00)
        full.setActivityThreshold(1.5f, true)

        then: "raw = round(1.5 / 0.001) = 1500 = 0x5DC -> L=0xDC, H bits[10:8]=0x05"
        writeAtEquals(conn, 4, 0x0A, 0x20, 0xDC)
        writeAtEquals(conn, 3, 0x0A, 0x21, 0x05)
        lastWriteEquals(conn, 0x0A, 0x27, 0x02)
    }

    def "link loop interrupt and self test"() {
        given:
        def conn = newConnection()
        def full = new Adxl362Full(conn)

        when:
        conn.setRegister(0x27, 0x05)
        full.setLinkLoopMode(Adxl362Full.LINKLOOP_LOOP)

        then:
        lastWriteEquals(conn, 0x0A, 0x27, 0x35)

        when:
        conn.setRegister(0x2A, 0x00)
        full.setInterrupt(1, Adxl362Full.SOURCE_AWAKE, true)

        then:
        lastWriteEquals(conn, 0x0A, 0x2A, 0x40)

        when:
        conn.setRegister(0x2E, 0x00)
        full.selfTest(true)

        then:
        lastWriteEquals(conn, 0x0A, 0x2E, 0x01)
    }
}
