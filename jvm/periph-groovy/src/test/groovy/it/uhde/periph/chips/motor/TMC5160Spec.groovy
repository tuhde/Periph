package it.uhde.periph.chips.motor

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.OutputPin
import spock.lang.Specification

class TMC5160Spec extends Specification {

    /** Scripted TMC5160 over SPI: pipelined 40-bit datagrams, instant ramp generator. */
    static class SpiSim implements Connection {
        Map<Integer, Long> regs = [0x04: 0x30000000L]
        List<List<Long>> writes = []
        long pending
        boolean mirrorBad

        long lastWrite(int reg) { writes.findAll { it[0] == (long) reg }.last()[1] }

        private long datagram(byte[] data) {
            byte[] f = Arrays.copyOf(data, 5)
            long reply = pending
            int reg = f[0] & 0x7F
            if ((f[0] & 0x80) != 0) {
                long v = ((f[1] & 0xFFL) << 24) | ((f[2] & 0xFFL) << 16) | ((f[3] & 0xFFL) << 8) | (f[4] & 0xFFL)
                regs[reg] = v
                writes << [(long) reg, v]
                if (reg == 0x2D) {
                    regs[0x21] = v
                    regs[0x35] = 1L << 9
                }
                pending = mirrorBad ? 0L : v
            } else {
                pending = regs.getOrDefault(reg, 0L)
            }
            return reply
        }

        void write(byte[] data) { datagram(data) }
        byte[] read(int n) { new byte[n] }
        byte[] writeRead(byte[] data, int n) {
            long r = datagram(data)
            return [(byte) (r >> 24), (byte) (r >> 16), (byte) (r >> 8), (byte) r] as byte[]
        }
        void enable() {}
        void disable() {}
        boolean isEnabled() { true }
        InputPin intPin() { null }
        OutputPin enPin() { null }
        void close() {}
    }

    /** Scripted TMC5160 over single-wire UART with echo, CRC8 and IFCNT. */
    static class UartSim implements Connection {
        Map<Integer, Long> regs = [0x04: 0x30000000L]
        List<List<Long>> writes = []
        int ifcnt
        byte[] rx = new byte[0]

        void write(byte[] data) {
            rx = concat(rx, data)
            if (data.length == 8) {
                int reg = data[2] & 0x7F
                long v = ((data[3] & 0xFFL) << 24) | ((data[4] & 0xFFL) << 16) | ((data[5] & 0xFFL) << 8) | (data[6] & 0xFFL)
                regs[reg] = v
                writes << [(long) reg, v]
                ifcnt = (ifcnt + 1) & 0xFF
            }
        }
        byte[] read(int n) {
            byte[] out = Arrays.copyOf(rx, n)
            rx = Arrays.copyOfRange(rx, n, rx.length)
            return out
        }
        byte[] writeRead(byte[] req, int n) {
            int reg = req[2] & 0x7F
            long v = reg == 0x02 ? (long) ifcnt : regs.getOrDefault(reg, 0L)
            byte[] r = [5, (byte) 0xFF, (byte) reg, (byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v, 0] as byte[]
            r[7] = (byte) TMC5160Minimal.crc8(r, 7)
            return Arrays.copyOf(concat(req, r), n)
        }
        private static byte[] concat(byte[] a, byte[] b) {
            byte[] o = Arrays.copyOf(a, a.length + b.length)
            System.arraycopy(b, 0, o, a.length, b.length)
            return o
        }
        void enable() {}
        void disable() {}
        boolean isEnabled() { true }
        InputPin intPin() { null }
        OutputPin enPin() { null }
        void close() {}
    }

    def "crc vector"() {
        expect:
        TMC5160Minimal.crc8([5, 0, 0] as byte[], 3) == 0x48
    }

    def "spi init writes defaults"() {
        given:
        def sim = new SpiSim()

        when:
        new TMC5160Minimal(sim, 0.075d, (OutputPin) null)

        then:
        sim.lastWrite(0x6C) == 0x000100C3L
        sim.lastWrite(0x00) == 0x4L
        sim.lastWrite(0x13) == 500L
        sim.lastWrite(0x01) == 7L
        sim.lastWrite(0x27) == 71583L
        sim.lastWrite(0x26) == 1564L
    }

    def "rejects wrong chip and STEP/DIR strap"() {
        when:
        def wrong = new SpiSim()
        wrong.regs[0x04] = 0x11000000L
        new TMC5160Minimal(wrong, 0.075d, (OutputPin) null)

        then:
        thrown(IOException)

        when:
        def sd = new SpiSim()
        sd.regs[0x04] = 0x30000040L
        new TMC5160Minimal(sd, 0.075d, (OutputPin) null)

        then:
        thrown(IOException)
    }

    def "motion and position"() {
        given:
        def sim = new SpiSim()
        def m = new TMC5160Minimal(sim, 0.075d, (OutputPin) null)

        when:
        m.moveTo(400.0d)
        m.waitUntilReached(1.0d)

        then:
        sim.lastWrite(0x2D) == 400L * 256
        m.position() == 400.0d

        when:
        m.run(-200.0d)

        then:
        sim.lastWrite(0x20) == 2L

        when:
        m.stop()

        then:
        sim.lastWrite(0x27) == 0L

        when:
        m.setCurrent(10.0d)

        then:
        thrown(IllegalArgumentException)

        when:
        m.setSpeed(0.0d)

        then:
        thrown(IllegalArgumentException)
    }

    def "full ramp and UART-only methods"() {
        given:
        def sim = new SpiSim()
        def f = new TMC5160Full(sim, 0.075d, (OutputPin) null, (InputPin) null, (InputPin) null)

        when:
        f.configureRamp(0.0d, 800.0d, 400.0d, 400.0d, 200.0d, 400.0d, 800.0d, 1.0d)
        f.setMicrosteps(16)

        then:
        sim.lastWrite(0x24) == 3127L
        ((sim.lastWrite(0x6C) >> 24) & 0xF) == 4L

        when:
        f.readIfcnt()

        then:
        thrown(IllegalStateException)

        when:
        f.setMicrosteps(3)

        then:
        thrown(IllegalArgumentException)

        when:
        sim.mirrorBad = true
        f.writeRegister(0x13, 6, true)

        then:
        thrown(IOException)

        when:
        f.setCoilCurrents(1, 1)

        then:
        thrown(IllegalStateException)
    }

    def "uart init and IFCNT"() {
        given:
        def sim = new UartSim()

        when:
        def f = new TMC5160Full(sim, 0.075d, 0, (OutputPin) null, true, false)
        f.setSendDelay(2)

        then:
        sim.writes.any { it[0] == 0x6CL && it[1] == 0x000100C3L }
        f.readIfcnt() > 0
        sim.regs[0x03] == (2L << 8)

        when:
        new TMC5160Minimal(new UartSim(), 0.075d, 254, (OutputPin) null, true, false)

        then:
        thrown(IllegalArgumentException)
    }
}
