package it.uhde.periph.chips.light

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class TSL2561Spec extends Specification {

    static final double[][] T = [[0.50d, 0.0304d, 0.062d], [0.61d, 0.0224d, 0.031d], [0.80d, 0.0128d, 0.0153d], [1.30d, 0.00146d, 0.00112d]] as double[][]
    static final double[][] CS = [[0.52d, 0.0315d, 0.0593d], [0.65d, 0.0229d, 0.0291d], [0.80d, 0.0157d, 0.0180d], [1.30d, 0.00338d, 0.00260d]] as double[][]

    def setupSpec() {
        TSL2561Minimal.waitPercent = 0   // skip the integration waits
    }

    static double ref(double ch0, double ch1, double gain, double scale, double[][] k) {
        double c0 = ch0 * (16d / gain) / scale
        double c1 = ch1 * (16d / gain) / scale
        double r = c1 / c0
        if (r <= k[0][0]) return k[0][1] * c0 - k[0][2] * c0 * Math.pow(r, 1.4d)
        for (int i = 1; i < 4; i++) if (r <= k[i][0]) return k[i][1] * c0 - k[i][2] * c1
        return 0d
    }

    static boolean near(double a, double b) { Math.abs(a - b) <= 1e-9 * Math.max(1d, Math.abs(b)) }

    static void counts(MockConnection c, int ch0, int ch1) {
        c.setRegister(0x9C, ch0 & 0xFF, ch0 >> 8, ch1 & 0xFF, ch1 >> 8)
    }

    static MockConnection mock() {
        def c = new MockConnection()
        c.setRegister(0x8A, 0x15)
        return c
    }

    static int reg(MockConnection c, int r) { c.registers().getOrDefault(r, 0) }

    static boolean wrote(MockConnection c, List<Integer> bytes) {
        c.writes().any { byte[] w -> w.collect { it & 0xFF } == bytes }
    }

    /** Counts follow the TIMING register: the light level is fixed, counts scale with gain and time. */
    static class RangeMock extends MockConnection {
        final double base

        RangeMock(double base) { this.base = base }

        @Override
        byte[] writeRead(byte[] data, int n) throws IOException {
            if ((data[0] & 0xFF) == 0x9C) {
                int t = registers().getOrDefault(0x81, 0x02)
                double gain = (t & 0x10) != 0 ? 16d : 1d
                double scale = [0.034d, 0.252d, 1.0d][t & 3]
                double limit = [5047d, 37177d, 65535d][t & 3]
                counts(this, (int) Math.min(limit, Math.floor(base * gain * scale)), (int) Math.min(limit, Math.floor(base * 0.2d * gain * scale)))
            }
            return super.writeRead(data, n)
        }
    }

    static class FakePin implements InputPin {
        EdgeHandler handler
        void onEdge(EdgeHandler h, EdgeTrigger t) { handler = h }
        void offEdge(EdgeHandler h) { if (handler.is(h)) handler = null }
        boolean isHigh() { false }
        void close() {}
    }

    def "init powers up, reads CONTROL back and selects 1x / 402 ms"() {
        given:
        def c = mock()

        when:
        new TSL2561Full(c)
        def data = c.writes().findAll { it.length > 1 }.collect { byte[] w -> w.collect { it & 0xFF } }

        then:
        data == [[0x80, 0x03], [0x81, 0x02]]
        wrote(c, [0x80])
    }

    def "init rejects a CONTROL that does not read back as powered"() {
        given:
        def dead = new MockConnection() {
            @Override
            void write(byte[] data) throws IOException {
                super.write(data)
                setRegister(0x80, 0x00)
            }
        }

        when:
        new TSL2561Minimal(dead)

        then:
        thrown(IOException)
    }

    def "lux formula covers every segment and both coefficient sets"() {
        given:
        def c = mock()
        def chip = new TSL2561Full(c)

        when:
        counts(c, 1000, 200)
        double lx = chip.readLux()
        then:
        near(lx, ref(1000, 200, 1, 1, T))
        Math.abs(lx - 382.2d) < 0.5d

        when:
        def got = [550, 700, 1000].collect { int c1 -> counts(c, 1000, c1); chip.readLux() }
        then:
        [550, 700, 1000].withIndex().every { int c1, int i -> near(got[i], ref(1000, c1, 1, 1, T)) }

        when:
        counts(c, 1000, 1400)
        double above = chip.readLux()
        counts(c, 0, 0)
        double dark = chip.readLux()
        then:
        above == 0d
        dark == 0d
        near(TSL2561Minimal.calculateLux(500, 100, 16, 101, "T"), ref(500, 100, 16, 0.252d, T))
        near(TSL2561Minimal.calculateLux(500, 300, 1, 14, "FN"), ref(500, 300, 1, 0.034d, T))
        [100, 600, 700, 1000].every { int c1 -> near(TSL2561Minimal.calculateLux(1000, c1, 1, 402, "CS"), ref(1000, c1, 1, 1, CS)) }
    }

    def "raw block read and saturation"() {
        given:
        def c = mock()
        def chip = new TSL2561Full(c)

        when:
        counts(c, 0x1234, 0x0567)
        int[] raw = chip.readRaw()
        then:
        raw == [0x1234, 0x0567] as int[]
        (c.writes().last()[0] & 0xFF) == 0x9C

        when:
        def r = chip.readLuxRaw()
        then:
        r.ch0 == 0x1234
        near(r.lux, ref(0x1234, 0x0567, 1, 1, T))

        when:
        counts(c, 65535, 100)
        chip.readLux()
        then:
        def e = thrown(TSL2561Minimal.SaturatedException)
        e.ch0 == 65535

        when:
        chip.setIntegrationTime(101)
        counts(c, 37177, 10)
        chip.readLux()
        then:
        thrown(TSL2561Minimal.SaturatedException)

        when:
        counts(c, 37176, 10)
        then:
        chip.readLux() > 0
    }

    def "gain, integration time, package and manual timing"() {
        given:
        def c = mock()
        def chip = new TSL2561Full(c)

        when:
        chip.setGain(16)
        then:
        reg(c, 0x81) == 0x12
        chip.getGain() == 16

        when:
        chip.setIntegrationTime(14)
        then:
        reg(c, 0x81) == 0x10
        chip.getIntegrationTime() == 14

        when:
        chip.setGain(1)
        chip.setIntegrationTime(101)
        then:
        reg(c, 0x81) == 0x01

        when:
        chip.setGain(4)
        then:
        thrown(IllegalArgumentException)

        when:
        chip.setIntegrationTime(50)
        then:
        thrown(IllegalArgumentException)

        when:
        chip.setPackage("X")
        then:
        thrown(IllegalArgumentException)

        when:
        chip.setIntegrationTime(402)
        counts(c, 1000, 100)
        chip.setPackage("CS")
        then:
        near(chip.readLux(), ref(1000, 100, 1, 1, CS))

        when:
        chip.setGain(16)
        chip.startManual()
        then:
        reg(c, 0x81) == 0x1B

        when:
        chip.stopManual()
        then:
        reg(c, 0x81) == 0x13
        chip.getIntegrationTime() == 0
    }

    def "auto range picks #gain x / #integ ms for base #base"() {
        given:
        def chip = new TSL2561Full(new RangeMock(base))

        when:
        double lx = chip.autoRange()

        then:
        lx > 0
        chip.getGain() == gain
        chip.getIntegrationTime() == integ

        where:
        base    | gain | integ
        20d     | 16   | 402
        148000d | 1    | 14
        5000d   | 1    | 402
    }

    def "power, thresholds and interrupt"() {
        given:
        def c = mock()
        def chip = new TSL2561Full(c)

        expect:
        chip.isPowered()

        when:
        chip.powerDown()
        then:
        !chip.isPowered()

        when:
        chip.powerUp()
        chip.setThresholds(0x0102, 0x0A0B)
        then:
        reg(c, 0x80) == 0x03
        wrote(c, [0xA2, 0x02, 0x01])
        wrote(c, [0xA4, 0x0B, 0x0A])
        chip.getThresholds() == [0x0102, 0x0A0B] as int[]

        when:
        chip.setThresholds(-1, 70000)
        then:
        thrown(IllegalArgumentException)

        when:
        chip.configureInterrupt(1, 2)
        then:
        reg(c, 0x86) == 0x12
        chip.readId() >> 4 == 1

        when:
        chip.configureInterrupt(3, 0)
        then:
        thrown(IllegalArgumentException)

        when:
        chip.configureInterrupt(1, 16)
        then:
        thrown(IllegalArgumentException)

        when:
        chip.setThresholds(100, 1000)
        def polls = [500, 100, 1001].collect { int ch0 -> counts(c, ch0, 0); chip.pollInterrupt() }
        then:
        polls == [false, true, true]

        when:
        chip.clearInterrupt()
        then:
        c.writes().last().collect { it & 0xFF } == [0xC0]

        when:
        def pin = new FakePin()
        def seen = []
        chip.onInterrupt({ int v -> seen << v } as java.util.function.IntConsumer, pin)
        counts(c, 4242, 0)
        pin.handler.onEdge()
        then:
        seen == [4242]
        c.writes().last().collect { it & 0xFF } == [0xC0]

        when:
        chip.offInterrupt()
        then:
        pin.handler == null
    }
}
