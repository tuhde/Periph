package it.uhde.periph.chips.light

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicInteger

class Si1132Spec extends Specification {

    /** Models the Si1132 sequencer: RESPONSE counter, parameter RAM, forced measurement. */
    static class ChipMock extends MockConnection {
        Map<Integer, Integer> params = [:]
        List<Integer> cmds = []
        int vis = 1234
        int ir = 567
        int aux = 250
        boolean stuck = false
        int cmdError = -1
        InputPin pin = null

        ChipMock(int seqId = 0x08, int partId = 0x32) {
            registers().put(0x00, partId)
            registers().put(0x02, seqId)
        }

        int r(int a) { return registers().getOrDefault(a, 0) }

        void count() { registers().put(0x20, (r(0x20) + 1) & 0x0F) }

        @Override
        void write(byte[] data) throws IOException {
            super.write(data)
            if (data.length != 2) return
            int reg = data[0] & 0xFF
            int val = data[1] & 0xFF
            if (reg == 0x21) {
                registers().put(0x21, r(0x21) & ~val & 0xFF)
            } else if (reg == 0x18) {
                cmds.add(val)
                command(val)
            }
        }

        void command(int cmd) {
            if (cmdError >= 0 && cmd != 0x00) { registers().put(0x20, cmdError); return }
            if ((cmd & 0xE0) == 0xA0) {
                params.put(cmd & 0x1F, r(0x17))
                registers().put(0x2E, params.get(cmd & 0x1F))
                count()
            } else if ((cmd & 0xE0) == 0x80) {
                registers().put(0x2E, params.getOrDefault(cmd & 0x1F, 0))
                count()
            } else if (cmd == 0x00) {
                registers().put(0x20, 0)
            } else if (cmd == 0x06) {
                if (!stuck) measure()
                count()
            } else if (cmd == 0x12) {
                for (int i = 0; i < 12; i++) registers().put(0x22 + i, 0x10 + i)
                count()
            } else if (cmd != 0x01) {
                count()
            }
        }

        void measure() {
            int a = aux
            if (params.getOrDefault(0x01, 0) == 0x40) a = params.getOrDefault(0x0F, 0) == 0x65 ? 0x4000 : 0x2000
            setRegister(0x22, vis & 0xFF, vis >> 8, ir & 0xFF, ir >> 8)
            setRegister(0x2C, a & 0xFF, a >> 8)
            registers().put(0x21, r(0x21) | 0x01)
        }

        @Override
        InputPin intPin() { return pin }
    }

    static class FakePin implements InputPin {
        EdgeHandler handler
        EdgeTrigger trigger
        @Override void onEdge(EdgeHandler h, EdgeTrigger t) { handler = h; trigger = t }
        @Override void offEdge(EdgeHandler h) { if (handler == h) handler = null }
        @Override boolean isHigh() { return false }
        @Override void close() {}
    }

    ChipMock mock

    def setup() {
        mock = new ChipMock()
    }

    private boolean wrote(int... bytes) {
        return mock.writes().any { byte[] w ->
            w.length == bytes.length && (0..<w.length).every { (w[it] & 0xFF) == bytes[it] }
        }
    }

    def "constructor configures the chip"() {
        when:
        new Si1132Minimal(mock)
        then:
        wrote(0x07, 0x17)
        wrote(0x13, 0x7B, 0x6B, 0x01, 0x00)
        mock.params[0x01] == 0xB0
        mock.r(0x04) == 0x01
    }

    def "wrong part id is rejected"() {
        when:
        new Si1132Minimal(new ChipMock(0x08, 0x45))
        then:
        thrown(IOException)
    }

    def "read returns the UV index and counts"() {
        when:
        double[] m = new Si1132Minimal(mock).read()
        then:
        Math.abs(m[0] - 2.5d) < 1e-6
        m[1] == 1234.0d
        m[2] == 567.0d
        mock.cmds.contains(0x06)
        mock.r(0x21) == 0
    }

    def "read times out when no measurement completes"() {
        given:
        mock.stuck = true
        def s = new Si1132Minimal(mock)
        when:
        s.read()
        then:
        thrown(IOException)
    }

    def "a command error sends NOP and throws"() {
        given:
        def f = new Si1132Full(mock)
        mock.cmdError = 0x80
        when:
        f.setGain(1, 1)
        then:
        thrown(IOException)
        mock.cmds.last() == 0x00
    }

    def "single channels and auxiliary channels"() {
        given:
        def f = new Si1132Full(mock)
        expect:
        Math.abs(f.readUvIndex() - 2.5d) < 1e-6
        f.readVisible() == 1234
        f.readInfrared() == 567
        f.readRaw() == [1234, 567, 250] as int[]
        f.readTemperatureRaw() == 0x4000
        mock.params[0x0F] == 0x65
        mock.params[0x01] == 0xB0
        f.readVddRaw() == 0x2000
        mock.params[0x0F] == 0x75
    }

    def "configuration methods write the parameter RAM"() {
        given:
        def f = new Si1132Full(mock)
        when:
        f.setChannels(true, false, true, false)
        then:
        mock.params[0x01] == 0xA0
        when:
        f.setChannels(false, true, false, true)
        then:
        mock.params[0x01] == 0x50
        when:
        f.setChannels(false, false, false, false)
        then:
        thrown(IllegalArgumentException)
        when:
        f.setChannels(true, true, true, true)
        then:
        thrown(IllegalArgumentException)
        when:
        f.setGain(3, 5)
        then:
        mock.params[0x11] == 3
        mock.params[0x10] == 0x40
        mock.params[0x1E] == 5
        mock.params[0x1D] == 0x20
        when:
        f.setGain(8, 0)
        then:
        thrown(IllegalArgumentException)
        when:
        f.setHighRange(true, false)
        f.setAlignment(true, true)
        f.setUvCoefficients([1, 2, 3, 4] as int[])
        then:
        mock.params[0x12] == 0x20
        mock.params[0x1F] == 0x00
        mock.params[0x06] == 0x30
        wrote(0x13, 1, 2, 3, 4)
    }

    def "calibration is read after GET_CAL"() {
        when:
        int[] cal = new Si1132Full(mock).readCalibration()
        then:
        cal.length == 12
        cal[0] == 0x10
        cal[11] == 0x1B
    }

    def "autonomous mode programs MEAS_RATE"() {
        given:
        def f = new Si1132Full(mock)
        when:
        f.startAutonomous(0.5d)
        then:
        mock.r(0x08) == 0x80
        mock.r(0x09) == 0x3E
        mock.cmds.last() == 0x0E
        when:
        f.startAutonomous(0.0d)
        then:
        mock.cmds.last() == 0x0A
        when:
        f.startAutonomous(3.0d)
        then:
        thrown(IllegalArgumentException)
    }

    def "SEQ_ID 1 erratum swaps the MEAS_RATE bytes"() {
        given:
        def m = new ChipMock(0x01, 0x32)
        when:
        new Si1132Full(m).startAutonomous(0.5d)
        then:
        m.r(0x0A) == 0x80
        m.r(0x08) == 0x3E
    }

    def "status, response, address and reset"() {
        given:
        def f = new Si1132Full(mock)
        when:
        f.pause()
        then:
        mock.cmds.last() == 0x0A
        when:
        f.force()
        then:
        mock.cmds.last() == 0x06
        f.measurementReady()
        when:
        mock.registers().put(0x21, 0)
        then:
        !f.measurementReady()
        when:
        mock.registers().put(0x20, 0x05)
        then:
        f.readResponse() == [5, 0] as int[]
        when:
        mock.registers().put(0x20, 0x8C)
        then:
        f.readResponse() == [0, 0x8C] as int[]
        when:
        f.setI2cAddress(0x61)
        then:
        mock.params[0x00] == 0x61
        mock.cmds.last() == 0x02
        when:
        f.reset()
        then:
        mock.cmds.contains(0x01)
        wrote(0x07, 0x17)
    }

    def "interrupt registers and polling"() {
        given:
        def f = new Si1132Full(mock)
        when:
        f.enableInterrupt()
        then:
        mock.r(0x03) == 0x01
        mock.r(0x04) == 0x01
        when:
        f.disableInterrupt()
        mock.registers().put(0x21, 0x01)
        then:
        mock.r(0x03) == 0x00
        f.pollInterrupt() == 0x01
        mock.r(0x21) == 0
        f.pollInterrupt() == 0
    }

    def "interrupt via pin delivers the status"() {
        given:
        def pin = new FakePin()
        mock.pin = pin
        def f = new Si1132Full(mock)
        def got = new AtomicInteger(-1)
        when:
        f.onInterrupt({ int s -> got.set(s) } as java.util.function.IntConsumer)
        then:
        mock.r(0x03) == 0x01
        pin.trigger == EdgeTrigger.FALLING
        when:
        mock.registers().put(0x21, 0x01)
        pin.handler.onEdge()
        then:
        got.get() == 0x01
        when:
        f.offInterrupt()
        then:
        pin.handler == null
        mock.r(0x03) == 0x00
    }

    def "interrupt polling fallback delivers the status"() {
        given:
        def f = new Si1132Full(mock)
        def got = new AtomicInteger(-1)
        when:
        f.onInterrupt({ int s -> got.set(s) } as java.util.function.IntConsumer)
        mock.registers().put(0x21, 0x01)
        long end = System.currentTimeMillis() + 1000
        while (got.get() < 0 && System.currentTimeMillis() < end) Thread.sleep(5)
        f.offInterrupt()
        then:
        got.get() == 0x01
    }
}
