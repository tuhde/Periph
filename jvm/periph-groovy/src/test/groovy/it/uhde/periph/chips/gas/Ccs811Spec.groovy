package it.uhde.periph.chips.gas

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.function.IntConsumer

class Ccs811Spec extends Specification {

    /** ALG_RESULT_DATA: eCO2 500 ppm, TVOC 15 ppb, STATUS 0x98, ERROR_ID 0, RAW 0x8C88. */
    static final List<Integer> RESULT = [0x01, 0xF4, 0x00, 0x0F, 0x98, 0x00, 0x8C, 0x88]

    /** Each register is its own mailbox; STATUS tracks FW_MODE and DATA_READY. */
    static class Mock extends MockConnection {
        Map<Integer, List<Integer>> mail = [
            (0x20): [0x81], (0x21): [0x12], (0x23): [0x10, 0x00], (0x24): [0x11, 0x02],
            (0x02): RESULT, (0x03): [0x8C, 0x88], (0x06): [0x06, 0x40, 0x03, 0x20],
            (0x11): [0x84, 0x7B], (0xE0): [0x00],
        ]
        boolean appValid = true, fwMode = false, dataReady = true, readyAfterStart = true
        InputPin pin

        @Override
        void write(byte[] data) throws IOException {
            super.write(data)
            int reg = data[0] & 0xFF
            if (reg == 0xF4 && data.length == 1) {
                if (readyAfterStart) fwMode = true
            } else if (reg == 0xFF) {
                fwMode = false
            } else if (data.length >= 2) {
                mail[reg] = (1..<data.length).collect { int i -> data[i] & 0xFF }
            }
        }

        @Override
        byte[] writeRead(byte[] data, int n) throws IOException {
            super.writeRead(data, n)
            int reg = data[0] & 0xFF
            byte[] out = new byte[n]
            if (reg == 0x00) {
                out[0] = (byte) ((fwMode ? 0x80 : 0) | (appValid ? 0x10 : 0) | (dataReady ? 0x08 : 0))
                return out
            }
            List<Integer> v = mail.get(reg, [])
            for (int i = 0; i < n && i < v.size(); i++) out[i] = (byte) v[i]
            if (reg == 0x02) dataReady = false
            out
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

    List<Integer> last(int reg) {
        def w = c.writes().findAll { byte[] f -> f.length >= 2 && (f[0] & 0xFF) == reg }
        w ? (1..<w[-1].length).collect { int i -> w[-1][i] & 0xFF } : null
    }

    boolean wrote(Mock m, int from, List<Integer> frame) {
        m.writes().drop(from).any { byte[] f -> f.collect { it & 0xFF } == frame }
    }

    Ccs811Full make() {
        c = new Mock()
        def s = new Ccs811Full(c)
        assert c.writes().isEmpty()
        s.init()
        s
    }

    def "init starts the application and read returns eCO2 and TVOC"() {
        given:
        def s = make()

        expect:
        wrote(c, 0, [0xF4])
        last(0x01) == [0x10]
        s.read() == new Ccs811Minimal.Measurement(500, 15)
        !c.dataReady
    }

    def "single-value reads, data ready and raw data"() {
        given:
        def s = make()

        when:
        c.dataReady = true
        def eco2 = s.readEco2()
        c.dataReady = true
        def tvoc = s.readTvoc()
        def raw = s.readRaw()

        then:
        eco2 == 500
        tvoc == 15
        !s.dataReady()
        raw[0] == 35
        Math.abs(raw[1] - 136 * 1.65d / 1023) < 1e-12
    }

    def "MEAS_MODE keeps drive mode and interrupt bits: #action"() {
        given:
        def s = make()

        when:
        steps.each { it.call(s) }

        then:
        last(0x01) == [expected]

        where:
        action                       | steps                                                                                                                     | expected
        'enable data ready'          | [{ Ccs811Full d -> d.enableInterrupt(Ccs811Full.SOURCE_DATA_READY) }]                                                     | 0x18
        'mode 2 keeps interrupt'     | [{ Ccs811Full d -> d.enableInterrupt(Ccs811Full.SOURCE_DATA_READY) }, { Ccs811Full d -> d.setMode(2) }]                    | 0x28
        'enable threshold'           | [{ Ccs811Full d -> d.setMode(2) }, { Ccs811Full d -> d.enableInterrupt(Ccs811Full.SOURCE_THRESHOLD) }]                      | 0x2C
        'disable threshold'          | [{ Ccs811Full d -> d.enableInterrupt(Ccs811Full.SOURCE_THRESHOLD) }, { Ccs811Full d -> d.disableInterrupt(Ccs811Full.SOURCE_THRESHOLD) }] | 0x10
        'disable data ready only'    | [{ Ccs811Full d -> d.enableInterrupt(Ccs811Full.SOURCE_THRESHOLD) }, { Ccs811Full d -> d.disableInterrupt(Ccs811Full.SOURCE_DATA_READY) }] | 0x14
        'mode 4'                     | [{ Ccs811Full d -> d.setMode(4) }]                                                                                        | 0x40
        'idle'                       | [{ Ccs811Full d -> d.setMode(0) }]                                                                                        | 0x00
    }

    def "set_mode rejects mode #mode"() {
        given:
        def s = make()

        when:
        s.setMode(mode)

        then:
        thrown(IllegalArgumentException)

        where:
        mode << [-1, 5]
    }

    def "environment, thresholds and baseline"() {
        given:
        def s = make()

        when:
        s.setEnvironment(48.5d, 22.5d)
        then:
        last(0x05) == [0x61, 0x00, 0x5F, 0x00]

        when:
        s.setThresholds(1000, 2000, 50)
        then:
        last(0x10) == [0x03, 0xE8, 0x07, 0xD0, 0x32]

        when:
        s.setThresholds()
        then:
        last(0x10) == [0x05, 0xDC, 0x09, 0xC4, 0x32]

        when:
        def before = s.getBaseline()
        s.setBaseline(0x1234)
        then:
        before == 0x847B
        last(0x11) == [0x12, 0x34]
        s.getBaseline() == 0x1234
    }

    def "invalid environment #h %RH / #t C is rejected"() {
        given:
        def s = make()

        when:
        s.setEnvironment(h, t)

        then:
        thrown(IllegalArgumentException)

        where:
        h      | t
        -1.0d  | 25.0d
        101.0d | 25.0d
        50.0d  | -26.0d
        50.0d  | 103.0d
    }

    def "NTC, status, error and versions"() {
        given:
        def s = make()

        expect:
        s.readNtc() == [1600, 800] as int[]
        Math.abs(s.ntcResistance(100000d) - 50000d) < 1e-9
        s.hardwareVersion() == [0x81, 0x12] as int[]
        s.firmwareVersion().boot() == new Ccs811Full.Version(1, 0, 0)
        s.firmwareVersion().app() == new Ccs811Full.Version(1, 1, 2)

        when:
        c.dataReady = false
        c.mail[0xE0] = [0x10]
        then:
        s.status() == 0x90
        s.error() == 0x10

        when:
        c.mail[0x06] = [0x00, 0x00, 0x03, 0x20]
        s.ntcResistance(100000d)
        then:
        thrown(IOException)
    }

    def "interrupt dispatch on the falling edge"() {
        given:
        def s = make()
        def pin = new FakePin()
        List<Integer> seen = []

        expect:
        s.pollInterrupt() == 0x98
        c.dataReady

        when:
        s.onInterrupt({ int st -> seen << st } as IntConsumer, pin)
        pin.handler.onEdge()
        then:
        pin.trigger == EdgeTrigger.FALLING
        seen == [0x98]

        when:
        c.dataReady = false
        pin.handler.onEdge()
        then:
        seen == [0x98]

        when:
        s.offInterrupt()
        then:
        pin.handler == null
    }

    def "the ERROR flag raises with ERROR_ID"() {
        given:
        def s = make()
        c.mail[0x02] = [0x01, 0xF4, 0x00, 0x0F, 0x99, 0x08]
        c.mail[0xE0] = [0x08]

        when:
        s.read()

        then:
        def e = thrown(IOException)
        e.message.contains('0x08')
    }

    def "soft reset restarts the application in mode 1"() {
        given:
        def s = make()
        int n = c.writes().size()

        when:
        s.setMode(3)
        s.softReset()

        then:
        wrote(c, n, [0xFF, 0x11, 0xE5, 0x72, 0x8A])
        wrote(c, n, [0xF4])
        last(0x01) == [0x10]

        when:
        c.dataReady = true
        then:
        s.read().eco2Ppm() == 500
    }

    def "minimal driver skips APP_START in application mode"() {
        given:
        def m = new Mock()
        m.fwMode = true
        def mn = new Ccs811Minimal(m)

        when:
        mn.init()

        then:
        !wrote(m, 0, [0xF4])
        mn.read() == new Ccs811Minimal.Measurement(500, 15)
    }

    def "init fails: #reason"() {
        given:
        def m = new Mock()
        setup.call(m)

        when:
        new Ccs811Minimal(m).init()

        then:
        thrown(IOException)

        where:
        reason          | setup
        'wrong HW_ID'   | { Mock x -> x.mail[0x20] = [0x55] }
        'no app'        | { Mock x -> x.appValid = false }
        'stuck in boot' | { Mock x -> x.readyAfterStart = false }
    }
}
