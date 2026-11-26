package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.function.IntConsumer

class Spl06Spec extends Specification {

    // Coefficients c0=204 c1=-261 c00=80469 c10=-54769 c01=-2659 c11=1258 c20=-10529 c21=94 c30=-1350
    static final int[] COEF = [0x0C, 0xCE, 0xFB, 0x13, 0xA5, 0x5F, 0x2A, 0x0F, 0xF5,
                               0x9D, 0x04, 0xEA, 0xD6, 0xDF, 0x00, 0x5E, 0xFA, 0xBA] as int[]
    static final byte[] TRAW = [0x02, 0x5C, 0x28] as byte[]                       // 154664
    static final byte[] PRAW = [(byte) 0xFE, (byte) 0x94, (byte) 0xB7] as byte[]  // -93001
    static final double T_EXP = 25.0054779053d
    static final double P_EXP = 98263.8611522177d

    /** MEAS_CFG keeps its read-only ready flags on write; PRS_B2..0 pops queued FIFO entries. */
    static class Mock extends MockConnection {
        int flags = 0xF0
        Deque<byte[]> fifo
        InputPin pin

        Mock() {
            setRegister(0x0D, 0x10)
            setRegister(0x08, 0xF0)
            setRegister(0x10, COEF)
            setRegister(0x00, 0xFE, 0x94, 0xB7, 0x02, 0x5C, 0x28)
        }

        @Override
        void write(byte[] data) throws IOException {
            super.write(data)
            if (data.length >= 2 && (data[0] & 0xFF) == 0x08) setRegister(0x08, (data[1] & 0x07) | flags)
        }

        @Override
        byte[] writeRead(byte[] data, int n) throws IOException {
            int reg = data[0] & 0xFF
            if (fifo != null && reg == 0x0B) setRegister(0x0B, fifo.isEmpty() ? 0x01 : 0x00)
            if (fifo != null && reg == 0x00 && n == 3 && !fifo.isEmpty()) return fifo.pollFirst().clone()
            return super.writeRead(data, n)
        }

        @Override
        InputPin intPin() { pin }
    }

    static class FakePin implements InputPin {
        EdgeHandler handler
        EdgeTrigger trigger

        void onEdge(EdgeHandler h, EdgeTrigger t) { handler = h; trigger = t }
        void offEdge(EdgeHandler h) { if (h.is(handler)) handler = null }
        boolean isHigh() { true }
        void close() {}
    }

    Mock c
    Spl06Full s

    def setup() {
        c = new Mock()
        s = new Spl06Full(c)
    }

    int last(int reg) {
        int v = -1
        for (byte[] w : c.writes()) if (w.length >= 2 && (w[0] & 0xFF) == reg) v = w[1] & 0xFF
        return v
    }

    long count(int reg) {
        c.writes().count { byte[] w -> w.length >= 2 && (w[0] & 0xFF) == reg }
    }

    def "constructor does no bus access and init decodes coefficients and writes defaults"() {
        expect:
        c.writes().isEmpty()

        when:
        s.init()

        then:
        last(0x07) == 0x80
        last(0x06) == 0x04
        last(0x09) == 0x04
        s.coefficients() == new Spl06Minimal.Coefficients(204, -261, 80469, -54769, -2659, 1258, -10529, 94, -1350)
    }

    def "read measures temperature then pressure"() {
        given:
        s.init()

        when:
        def m = s.read()
        def meas = c.writes().findAll { byte[] w -> w.length >= 2 && w[0] == 0x08 }.collect { byte[] w -> w[1] as int }

        then:
        Math.abs(m.temperatureC() - T_EXP) < 1e-6
        Math.abs(m.pressurePa() - P_EXP) < 1e-6
        meas.takeRight(2) == [0x02, 0x01]
    }

    def "single reads, raw values and altitude"() {
        given:
        s.init()

        expect:
        Math.abs(s.readPressure() - P_EXP) < 1e-6
        Math.abs(s.readTemperature() - T_EXP) < 1e-6
        last(0x08) == 0x02
        s.readRaw() == [-93001, 154664] as int[]
        Math.abs(s.altitude() - 258.0291543011d) < 1e-3
        Math.abs(s.altitude(P_EXP)) < 1e-6
    }

    def "compensate is pure computation"() {
        given:
        s.init()

        when:
        def a = s.compensate(-93001, 154664, 16, 1)
        def b = s.compensate(-93001, 154664, 8, 2)

        then:
        Math.abs(a.pressurePa() - P_EXP) < 1e-6
        Math.abs(a.temperatureC() - T_EXP) < 1e-6
        Math.abs(b.pressurePa() - 80852.2826419888d) < 1e-6
        Math.abs(b.temperatureC() - 76.3351593018d) < 1e-6

        when:
        s.compensate(0, 0, 3, 1)

        then:
        thrown(IllegalArgumentException)
    }

    def "configuration sets registers and shift bits"() {
        given:
        s.init()

        when:
        s.setPressureConfig(128, 8)
        then:
        last(0x06) == 0x37
        (last(0x09) & 0x04) != 0

        when:
        s.setPressureConfig(8, 1)
        then:
        last(0x06) == 0x03
        (last(0x09) & 0x04) == 0

        when:
        s.setTemperatureConfig(32, 4)
        then:
        last(0x07) == 0xA5
        (last(0x09) & 0x08) != 0

        when:
        s.setTemperatureConfig(2, 1)
        then:
        last(0x07) == 0x81
        (last(0x09) & 0x08) == 0
        Math.abs(s.read().pressurePa() - 80852.2826419888d) < 1e-6
    }

    def "invalid configuration is rejected"() {
        given:
        s.init()

        when:
        s.setPressureConfig(osr, rate)

        then:
        thrown(IllegalArgumentException)

        where:
        osr | rate
        3   | 1
        16  | 3
        0   | 1
    }

    def "background mode reads without starting a measurement"() {
        given:
        s.init()

        when:
        s.startContinuous(true, true)
        then:
        last(0x08) == 0x07

        when:
        s.startContinuous(true, false)
        then:
        last(0x08) == 0x05

        when:
        s.startContinuous(false, true)
        then:
        last(0x08) == 0x06

        when:
        s.startContinuous(false, false)
        then:
        thrown(IllegalArgumentException)

        when:
        s.startContinuous()
        long n = count(0x08)
        def m = s.readContinuous()
        def r = s.read()
        then:
        Math.abs(m.pressurePa() - P_EXP) < 1e-6
        Math.abs(r.pressurePa() - P_EXP) < 1e-6
        count(0x08) == n

        when:
        c.setRegister(0x08, 0xD7)
        then:
        s.dataReady() == [true, false] as boolean[]

        when:
        c.setRegister(0x08, 0xF7)
        s.stop()
        then:
        last(0x08) == 0x00
    }

    def "FIFO drains and classifies entries"() {
        given:
        s.init()
        s.enableFifo()
        c.fifo = new ArrayDeque<byte[]>([TRAW, PRAW])

        when:
        def e = s.readFifo()

        then:
        (last(0x09) & 0x02) != 0
        e.size() == 2
        e[0].kind() == Spl06Full.KIND_TEMPERATURE
        Math.abs(e[0].value() - T_EXP) < 1e-6
        e[1].kind() == Spl06Full.KIND_PRESSURE
        Math.abs(e[1].value() - 98264.0472093194d) < 1e-6
        s.readFifo().isEmpty()

        when:
        c.fifo = null
        c.setRegister(0x0B, 0x02)
        def st = s.fifoStatus()
        s.flushFifo()
        s.disableFifo()

        then:
        st == [false, true] as boolean[]
        last(0x0C) == 0x80
        (last(0x09) & 0x02) == 0
    }

    def "interrupt sources map to CFG_REG bits and dispatch on edges"() {
        given:
        s.init()
        def pin = new FakePin()
        def seen = []

        when:
        s.enableInterrupt(Spl06Full.SOURCE_PRESSURE)
        then:
        (last(0x09) & 0x20) != 0

        when:
        s.enableInterrupt(Spl06Full.SOURCE_TEMPERATURE | Spl06Full.SOURCE_FIFO_FULL)
        then:
        (last(0x09) & 0x74) == 0x74

        when:
        s.disableInterrupt(Spl06Full.SOURCE_PRESSURE | Spl06Full.SOURCE_FIFO_FULL)
        c.setRegister(0x0A, 0xFA)
        then:
        (last(0x09) & 0x70) == 0x10
        s.pollInterrupt() == 0x02

        when:
        s.onInterrupt({ int st -> seen << st } as IntConsumer, pin)
        c.setRegister(0x0A, 0x01)
        pin.handler.onEdge()
        c.setRegister(0x0A, 0x00)
        pin.handler.onEdge()
        then:
        pin.trigger == EdgeTrigger.FALLING
        seen == [0x01]

        when:
        s.offInterrupt()
        then:
        pin.handler == null
    }

    def "reset restores the init defaults"() {
        given:
        s.init()
        s.setPressureConfig(64, 2)

        when:
        s.reset()

        then:
        last(0x0C) == 0x09
        last(0x06) == 0x04
        last(0x07) == 0x80
        last(0x09) == 0x04
        Math.abs(s.read().pressurePa() - P_EXP) < 1e-6
    }

    def "minimal driver reads and rejects bad chips"() {
        given:
        def m = new Spl06Minimal(new Mock())
        def bad = new Mock()
        bad.setRegister(0x0D, 0x58)

        when:
        m.init()
        then:
        Math.abs(m.read().pressurePa() - P_EXP) < 1e-6

        when:
        new Spl06Minimal(bad).init()
        then:
        thrown(IOException)
    }
}
