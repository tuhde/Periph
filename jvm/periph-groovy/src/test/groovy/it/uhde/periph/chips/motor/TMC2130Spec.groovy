package it.uhde.periph.chips.motor

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.OutputPin
import spock.lang.Specification

class TMC2130Spec extends Specification {

    /** Scripted TMC2130 implementing the pipelined 40-bit datagram protocol. */
    static class Sim implements Connection {
        Map<Integer, Long> regs = [0x04: 0x11000040L, 0x01: 1L]
        List<byte[]> frames = []
        List<List<Long>> writes = []
        long pending
        boolean mirrorBroken
        int gstatReads

        long lastWrite(int reg) { writes.findAll { it[0] == (long) reg }.last()[1] }

        private long datagram(byte[] data) {
            byte[] f = Arrays.copyOf(data, 5)
            frames << f
            long reply = pending
            int reg = f[0] & 0x7F
            if ((f[0] & 0x80) != 0) {
                long v = ((f[1] & 0xFFL) << 24) | ((f[2] & 0xFFL) << 16) | ((f[3] & 0xFFL) << 8) | (f[4] & 0xFFL)
                regs[reg] = v
                writes << [(long) reg, v]
                pending = mirrorBroken ? 0L : v
            } else {
                if (reg == 0x01) gstatReads++
                pending = regs.getOrDefault(reg, 0L)
                if (reg == 0x01) regs[0x01] = 0L
            }
            return reply
        }

        void write(byte[] data) { assert data.length == 5; datagram(data) }
        byte[] read(int n) { new byte[n] }
        byte[] writeRead(byte[] data, int n) {
            assert n == 4
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

    static class Pin implements OutputPin {
        List<Boolean> levels = []
        void set(boolean high) { levels << high }
        void close() {}
        int highs() { levels.count { it } as int }
    }

    static class DiagPin implements InputPin {
        boolean isHigh() { true }
        void onEdge(EdgeHandler handler, EdgeTrigger trigger) {}
        void offEdge(EdgeHandler handler) {}
        void close() {}
    }

    def "init writes defaults"() {
        given:
        def sim = new Sim()

        when:
        new TMC2130Minimal(sim, new Pin(), new Pin())

        then:
        sim.frames.any { Arrays.equals(it, [0xEC, 0x14, 0x03, 0x00, 0xC3] as byte[]) }
        sim.lastWrite(0x11) == 10L
        sim.lastWrite(0x00) == 0x4L
        sim.lastWrite(0x13) == 500L
        sim.lastWrite(0x70) == 0x000401C8L
        def ih = sim.lastWrite(0x10)
        ((ih >> 8) & 0x1F) == 15
        (ih & 0x1F) == 5
        ((ih >> 16) & 0xF) == 6
        sim.gstatReads == 1
    }

    def "init failures"() {
        when:
        def a = new Sim()
        a.regs[0x04] = 0L
        new TMC2130Minimal(a, new Pin(), new Pin())

        then:
        thrown(IOException)

        when:
        def b = new Sim()
        b.regs[0x04] = 0x11000000L
        new TMC2130Minimal(b, new Pin(), new Pin())

        then:
        thrown(IOException)

        when:
        def c = new Sim()
        c.mirrorBroken = true
        new TMC2130Minimal(c, new Pin(), new Pin())

        then:
        thrown(IOException)
    }

    def "current motion and faults"() {
        given:
        def sim = new Sim()
        def step = new Pin()
        def dir = new Pin()
        def m = new TMC2130Minimal(sim, step, dir)

        when:
        m.setCurrent(1.0d)

        then:
        ((sim.lastWrite(0x10) >> 8) & 0x1F) == 17
        (sim.lastWrite(0x6C) & (1L << 17)) == 0L

        when:
        m.setCurrent(0.5d)

        then:
        ((sim.lastWrite(0x10) >> 8) & 0x1F) == 15
        (sim.lastWrite(0x6C) & (1L << 17)) != 0L

        when:
        m.setCurrent(1.5d)

        then:
        thrown(IllegalArgumentException)

        when:
        m.move(2, 1e6d)

        then:
        step.highs() == 32
        dir.levels.last()

        when:
        m.move(-1, 1e6d)

        then:
        !dir.levels.last()
        step.highs() == 48

        when:
        m.move(1, 0.0d)

        then:
        thrown(IllegalArgumentException)

        when:
        m.disable()

        then:
        (sim.lastWrite(0x6C) & 0xF) == 0

        when:
        m.enable()
        sim.regs[0x6F] = (1L << 31) | (1L << 26) | (1L << 28) | (1L << 30)

        then:
        (sim.lastWrite(0x6C) & 0xF) == 3
        m.isStandstill()
        m.readFault() == (TMC2130Minimal.FAULT_OVERTEMP_WARNING | TMC2130Minimal.FAULT_SHORT_GND | TMC2130Minimal.FAULT_OPEN_LOAD)
    }

    def "enn pin"() {
        given:
        def en = new Pin()

        when:
        def m = new TMC2130Minimal(new Sim(), new Pin(), new Pin(), 0.11d, en)

        then:
        en.levels == [true]

        when:
        m.enable()

        then:
        !en.levels.last()
    }

    def "full configuration"() {
        given:
        def sim = new Sim()
        def step = new Pin()
        def dir = new Pin()
        def f = new TMC2130Full(sim, step, dir, 0.11d, null, new DiagPin(), null)

        when:
        f.setMicrosteps(256)

        then:
        ((sim.lastWrite(0x6C) >> 24) & 0xF) == 0

        when:
        f.setMicrosteps(1)

        then:
        ((sim.lastWrite(0x6C) >> 24) & 0xF) == 8

        when:
        f.setMicrosteps(3)

        then:
        thrown(IllegalArgumentException)

        when:
        f.setMicrosteps(32)
        f.move(1, 1e6d)
        f.setChopperMode(true)

        then:
        step.highs() == 32
        (sim.lastWrite(0x00) & 0x4) == 0

        when:
        f.setChopperMode(false)
        f.setDirectionInverted(true)
        f.configureDiag(false, false, true, false, true, false, true, false)
        def g = sim.lastWrite(0x00)

        then:
        ((g >> 7) & 1) == 1
        ((g >> 9) & 1) == 1
        ((g >> 12) & 1) == 1
        ((g >> 5) & 1) == 0
        (g & 0x14) == 0x14

        when:
        f.setVsense(false)
        f.setCurrent(0.5d)
        f.setHoldCurrent(0.2d)
        f.setCurrentDelay(2, 100)

        then:
        ((sim.lastWrite(0x6C) >> 17) & 1) == 0
        (sim.lastWrite(0x10) & 0x1F) == 3
        ((sim.lastWrite(0x10) >> 8) & 0x1F) == 8
        ((sim.lastWrite(0x10) >> 16) & 0xF) == 2
        sim.lastWrite(0x11) == 100L

        when:
        f.configureChopper(4, 1, 3, 2)
        def c = sim.lastWrite(0x6C)

        then:
        (c & 0xF) == 4
        ((c >> 15) & 3) == 1
        ((c >> 4) & 7) == 3
        ((c >> 7) & 0xF) == 2
        ((c >> 14) & 1) == 0

        when:
        f.configureConstantOffTime(5, 2, 11, -3, true, true)
        c = sim.lastWrite(0x6C)

        then:
        (c & 0xF) == 5
        ((c >> 14) & 1) == 1
        ((c >> 4) & 7) == 3
        ((c >> 11) & 1) == 1
        ((c >> 7) & 0xF) == 0
        ((c >> 12) & 3) == 3

        when:
        f.configureStealthchop(true, false, 40, 5, 1)
        f.setFreewheel(1)
        def p = sim.lastWrite(0x70)

        then:
        (p & 0xFF) == 40
        ((p >> 8) & 0xFF) == 5
        ((p >> 16) & 3) == 1
        ((p >> 20) & 3) == 1
        ((p >> 18) & 1) == 1

        when:
        f.setStallThreshold(-5)
        f.setStallguardFilter(true)
        f.configureCoolstep(5, 2, 1, 1, 0)
        def cc = sim.lastWrite(0x6D)

        then:
        (cc & 0xF) == 5
        ((cc >> 16) & 0x7F) == (-5 & 0x7F)
        ((cc >> 24) & 1) == 1
        ((cc >> 8) & 0xF) == 2

        when:
        f.setStallThreshold(64)

        then:
        thrown(IllegalArgumentException)

        when:
        f.configureCoolstep(16, 0, 0, 0, 0)

        then:
        thrown(IllegalArgumentException)

        when:
        f.setCoolstepThreshold(500L)
        f.setCoilCurrents(1, 1)

        then:
        sim.lastWrite(0x14) == 500L
        thrown(IllegalStateException)

        when:
        f.setDirectMode(true)
        f.setCoilCurrents(100, -100)
        f.setDcstep(1000L, 200, 3)
        f.writeMicrostepTable([1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L] as long[], 0x12345678L, 0x00F70000L)

        then:
        sim.lastWrite(0x2D) == (100L | ((-100L & 0x1FF) << 16))
        sim.lastWrite(0x33) == 1000L
        sim.lastWrite(0x6E) == (200L | (3L << 16))
        sim.lastWrite(0x60) == 1L
        sim.lastWrite(0x67) == 8L
        sim.lastWrite(0x68) == 0x12345678L

        when:
        f.setInternalRsense(true)
        f.setCurrent(0.5d)

        then:
        thrown(IllegalStateException)

        when:
        f.setInternalRsense(false)
        f.step(3, 0.0d)

        then:
        step.highs() == 35
        dir.levels.last()

        when:
        f.step(-2, 0.0d)

        then:
        !dir.levels.last()
    }

    def "full readbacks"() {
        given:
        def sim = new Sim()
        def f = new TMC2130Full(sim, new Pin(), new Pin(), 0.11d, null, new DiagPin(), null)
        sim.regs[0x6A] = 513L
        sim.regs[0x6B] = (0x1FFL << 16) | 0x100
        sim.regs[0x12] = 1234L
        sim.regs[0x71] = 77L
        sim.regs[0x73] = 0x123456L
        sim.regs[0x6F] = (1L << 31) | (1L << 15) | (17L << 16) | (1L << 25) | (1L << 24) | 300
        sim.regs[0x04] = 0x11000000L | 0x40 | 0x1 | 0x10
        sim.regs[0x01] = 3L

        expect:
        f.microstepPosition() == 513
        f.readCoilCurrents() == new TMC2130Full.CoilCurrents(-1, -256)
        f.readTstep() == 1234L
        f.readPwmScale() == 77
        f.readLostSteps() == 0x23456L
        f.readCurrentScale() == 17
        f.readStallguard() == 300
        f.isStalled()

        when:
        def st = f.readStatus()

        then:
        st.stst && st.fsactive && st.ot && st.stallguard && !st.otpw
        st.csActual == 17
        st.sgResult == 300
        f.readGlobalStatus() == new TMC2130Full.GlobalStatus(true, true, false)
        sim.regs[0x01] == 0L

        when:
        def io = f.readIoin()

        then:
        io.step && io.enn && !io.dir
        io.version == 0x11
        f.readRegister(0x6A) == 513L

        when:
        f.readRegister(0x10)

        then:
        thrown(IllegalArgumentException)

        when:
        f.writeRegister(0x13, 5L, true)

        then:
        sim.lastWrite(0x13) == 5L

        when:
        f.writeRegister(0x72, 1L, false)

        then:
        thrown(IllegalArgumentException)

        expect:
        f.readDiag(0)

        when:
        f.readDiag(1)

        then:
        thrown(IllegalStateException)

        when:
        f.readDiag(2)

        then:
        thrown(IllegalArgumentException)
    }
}
