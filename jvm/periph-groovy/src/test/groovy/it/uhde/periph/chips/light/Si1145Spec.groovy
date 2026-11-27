package it.uhde.periph.chips.light

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.function.IntConsumer

class Si1145Spec extends Specification {

    /** Simulates the command engine: RESPONSE counter, parameter RAM, forced measurements. */
    static class Sim extends MockConnection {
        int[] regs = new int[0x40]
        int[] params = new int[32]
        int[] ps = [1500, 1600, 1700] as int[]
        List<Integer> commands = []
        boolean ignore = false
        int failParam = -1
        InputPin pin

        Sim(int part, int seq) {
            regs[0] = part
            regs[2] = seq
            params[0x00] = 0x60
            params[0x02] = 0x21
            params[0x0C] = 0x04
            params[0x0F] = 0x65
        }

        int last() { commands[-1] }

        void put16(int r, int v) { regs[r] = v & 0xFF; regs[r + 1] = (v >> 8) & 0xFF }

        void measure(boolean p, boolean a) {
            int ch = params[1]
            int st = 0
            if (a && (ch & 0xF0) != 0) {
                if ((ch & 0x10) != 0) put16(0x22, 260)
                if ((ch & 0x20) != 0) put16(0x24, 300)
                if ((ch & 0x80) != 0) put16(0x2C, 345)
                else if ((ch & 0x40) != 0) put16(0x2C, params[0x0F] == 0x65 ? 0x1111 : 0x2222)
                st |= 0x01
            }
            if (p) {
                for (int i = 0; i < 3; i++) {
                    if ((ch & (1 << i)) != 0) { put16(0x26 + 2 * i, ps[i]); st |= 0x04 << i }
                }
            }
            regs[0x21] |= st & regs[0x04]
        }

        void command(int cmd) {
            commands << cmd
            if (ignore) return
            int resp = regs[0x20]
            if (cmd == 0x00) { regs[0x20] = 0; return }
            if (cmd == 0x01) { regs[0x20] = 0; regs[0x07] = 0; return }
            if ((resp & 0x80) != 0) return
            if ((cmd & 0xE0) == 0xA0) {
                int off = cmd & 0x1F
                if (off == failParam) { regs[0x20] = 0x80; return }
                params[off] = regs[0x17]
                regs[0x2E] = regs[0x17]
            } else if ((cmd & 0xE0) == 0x80) {
                regs[0x2E] = params[cmd & 0x1F]
            } else if (cmd >= 0x05 && cmd <= 0x07) {
                measure(cmd != 0x06, cmd != 0x05)
            } else if (cmd == 0x12) {
                for (int i = 0; i < 12; i++) regs[0x22 + i] = 0x10 + i
            } else if (!([0x02, 0x09, 0x0A, 0x0B, 0x0D, 0x0E, 0x0F].contains(cmd))) {
                regs[0x20] = 0x80
                return
            }
            regs[0x20] = (resp + 1) & 0x0F
        }

        @Override
        void write(byte[] data) throws IOException {
            super.write(data)
            int reg = data[0] & 0xFF
            for (int i = 1; i < data.length; i++) {
                int r = reg + i - 1
                int v = data[i] & 0xFF
                if (r == 0x18) command(v)
                else if (r == 0x21) regs[0x21] &= ~v
                else regs[r] = v
            }
        }

        @Override
        byte[] writeRead(byte[] data, int n) throws IOException {
            super.writeRead(data, n)
            int reg = data[0] & 0xFF
            byte[] out = new byte[n]
            for (int i = 0; i < n; i++) out[i] = (byte) regs[(reg + i) & 0x3F]
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

    Sim c

    List<Integer> params(int... idx) { idx.collect { int i -> c.params[i] } }

    List<Integer> regs(int... idx) { idx.collect { int i -> c.regs[i] } }

    Si1145Full make(int part = 0x45, int seq = 0x08) {
        c = new Sim(part, seq)
        def s = new Si1145Full(c)
        assert c.writes().isEmpty()
        s.init()
        s
    }

    def "init unlocks the chip and writes the defaults, read decodes PS + ALS"() {
        given:
        def s = make()

        when:
        def r = s.read()

        then:
        regs(0x07, 0x13, 0x14, 0x15, 0x16, 0x0F, 0x04) == [0x17, 0x7B, 0x6B, 0x01, 0x00, 0x03, 0x05]
        params(0x01, 0x02) == [0xB1, 0x01]
        r.proximity() == 1500
        Math.abs(r.uvIndex() - 3.45d) < 1e-9d
        r.visible() == 260
        r.infrared() == 300
        c.regs[0x21] == 0
    }

    def "init rejects a wrong PART_ID"() {
        when:
        new Si1145Minimal(new Sim(0x32, 0)).init()

        then:
        thrown(IOException)
    }

    def "single reads and the auxiliary channels"() {
        given:
        def s = make()

        expect:
        s.readProximity() == 1500
        c.last() == 0x05
        Math.abs(s.readUvIndex() - 3.45d) < 1e-9d
        c.last() == 0x06
        s.readVisible() == 260
        s.readInfrared() == 300
        s.readRaw() == new Si1145Full.Raw(260, 300, 1500, 0, 0, 345)
        s.readTemperatureRaw() == 0x1111
        c.params[1] == 0xB1
        s.readVddRaw() == 0x2222
        c.params[0x0F] == 0x75
    }

    def "PS2 is rejected on the Si1145"() {
        given:
        def s = make()

        when:
        s.readProximity(2)

        then:
        thrown(IllegalArgumentException)
    }

    def "channel list and its limits"() {
        given:
        def s = make()

        when:
        s.setChannels(false, true, false, true)

        then:
        c.params[1] == 0x11

        when:
        s.readUvIndex()

        then:
        thrown(IllegalArgumentException)

        when:
        s.setChannels(true, false, false, false, false, false, true)

        then:
        thrown(IllegalArgumentException)
    }

    def "LEDs, photodiode, gain, range, alignment, UV coefficients and calibration"() {
        given:
        def s = make()

        when:
        s.setLedCurrent(1, 15)
        s.setPsLed(1, 0)
        s.setPsPhotodiode(1, false)
        s.setGain(3, 2, 1)
        s.setHighRange(true, true, true)
        s.setAlignment(true, false, true, false, false)
        s.setUvCoefficients([1, 2, 3, 4] as int[])
        def cal = s.readCalibration()

        then:
        c.regs[0x0F] == 0x0F
        params(0x02, 0x07) == [0, 0]
        params(0x11, 0x10, 0x1E, 0x1D, 0x0B, 0x0A) == [3, 0x40, 2, 0x50, 1, 0x60]
        params(0x12, 0x1F, 0x0C) == [0x20, 0x20, 0x24]
        params(0x06, 0x05) == [0x10, 0x10]
        regs(0x13, 0x16) == [1, 4]
        cal[0] == 0x10
        cal[11] == 0x1B
    }

    def "LED limits on the Si1145"() {
        given:
        def s = make()

        when:
        s.setLedCurrent(2, 1)

        then:
        thrown(IllegalArgumentException)

        when:
        s.setGain(8, 0, 0)

        then:
        thrown(IllegalArgumentException)
    }

    def "Si1147 drives three LEDs and PS3"() {
        given:
        def s = make(0x47)

        when:
        s.setLedCurrent(2, 5)
        s.setLedCurrent(3, 9)
        s.setPsLed(2, 2)
        s.setPsLed(3, 4)
        s.setChannels(false, false, false, true, true, true, false)

        then:
        regs(0x0F, 0x10) == [0x53, 0x09]
        params(0x02, 0x03) == [0x21, 0x04]
        s.readProximity(3) == 1700
    }

    def "autonomous mode, pause and force"() {
        given:
        def s = make()

        when:
        s.startAutonomous(0.1d)

        then:
        regs(0x08, 0x09) == [0x80, 0x0C]
        c.last() == 0x0F

        when:
        s.startAutonomous(0.01d, false, true)
        then:
        c.last() == 0x0E

        when:
        s.pause()
        then:
        c.last() == 0x0B

        when:
        s.force(true, false)
        then:
        c.last() == 0x05

        when:
        s.startAutonomous(3.0d)
        then:
        thrown(IllegalArgumentException)
    }

    def "SEQ_ID 0x01 swaps the MEAS_RATE bytes"() {
        given:
        def s = make(0x45, 0x01)

        when:
        s.startAutonomous(0.1d)

        then:
        regs(0x0A, 0x08) == [0x80, 0x0C]
    }

    def "a parameter error is cleared with NOP and RESPONSE decodes errors"() {
        given:
        def s = make()
        c.failParam = 0x0B

        when:
        s.setGain(0, 0, 1)

        then:
        thrown(IOException)
        c.last() == 0x00
        c.regs[0x20] == 0

        when:
        c.failParam = -1
        c.regs[0x20] = 0x8C

        then:
        s.readResponse() == new Si1145Full.Response(0, 0x8C)

        when:
        s.setGain(0, 0, 0)

        then:
        (c.regs[0x20] & 0x80) == 0
    }

    def "interrupt sources, polling and the INT callback"() {
        given:
        def s = make()
        def pin = new FakePin()
        c.pin = pin
        List<Integer> got = []

        when:
        s.enableInterrupt(Si1145Minimal.SOURCE_CMD)
        then:
        regs(0x03, 0x04) == [0x01, 0x25]

        when:
        s.disableInterrupt(Si1145Minimal.SOURCE_CMD)
        c.regs[0x21] = 0x05
        then:
        c.regs[0x04] == 0x05
        s.pollInterrupt() == 0x05
        s.pollInterrupt() == 0

        when:
        s.onInterrupt({ int v -> got << v } as IntConsumer)
        c.regs[0x21] = 0x04
        pin.handler.onEdge()
        pin.handler.onEdge()
        then:
        pin.trigger == EdgeTrigger.FALLING
        got == [0x04]

        when:
        s.offInterrupt()
        then:
        pin.handler == null
    }

    def "identity, address and reset"() {
        given:
        def s = make()

        when:
        s.setI2cAddress(0x30)
        c.regs[0x0F] = 0x0F
        s.reset()

        then:
        s.readId() == new Si1145Full.Id(0x45, 0, 0x08)
        c.params[0] == 0x30
        c.commands.contains(0x01)
        regs(0x07, 0x0F) == [0x17, 0x03]
        c.params[1] == 0xB1
    }

    def "a stuck sequencer times out"() {
        given:
        def s = make()
        c.ignore = true

        when:
        s.read()

        then:
        thrown(IOException)
    }
}
