package it.uhde.periph.chips.light

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.function.IntConsumer

class Si1133Spec extends Specification {

    /** Simulates the command engine: RESPONSE0 counter, parameter table, FORCE packing HOSTOUT. */
    static class Mock extends MockConnection {
        int partId = 0x33
        int[] params = new int[64]
        int counter = 0x0F, error = 0, resp1 = 0, irqEnable = 0, irqStatus = 0
        int[] hostout = new int[26]
        int[] samples = [0x012345, -5, 0xABCD, 0, 0, 0] as int[]
        List<Integer> commands = []
        boolean ignoreCommands = false
        InputPin pin

        int resp0() { error != 0 ? (0x30 | (error & 0x0F)) : (0x20 | counter) }

        void command(int cmd, int hostin) {
            commands << cmd
            if (ignoreCommands) return
            if (cmd == 0x00) { counter = 0; error = 0; return }
            if (cmd == 0x01) {
                Arrays.fill(params, 0)
                counter = 0x0F; error = 0; irqEnable = 0
                return
            }
            if ((cmd & 0xC0) == 0x80) {
                int p = cmd & 0x3F
                if (p > 0x2B) { error = 0x11; return }
                params[p] = hostin
                resp1 = hostin
            } else if ((cmd & 0xC0) == 0x40) {
                resp1 = params[cmd & 0x3F]
            } else if (cmd == 0x11) {
                hostout = new int[26]
                int pos = 0
                for (int ch = 0; ch < 6; ch++) {
                    if ((params[1] & (1 << ch)) == 0) continue
                    int v = samples[ch]
                    if ((params[0x04 + 4 * ch] & 0x40) != 0) {
                        hostout[pos++] = (v >> 16) & 0xFF
                        hostout[pos++] = (v >> 8) & 0xFF
                        hostout[pos++] = v & 0xFF
                    } else {
                        hostout[pos++] = (v >> 8) & 0xFF
                        hostout[pos++] = v & 0xFF
                    }
                }
                irqStatus |= params[1] & irqEnable
            } else if (cmd != 0x12 && cmd != 0x13) {
                error = 0x10
                return
            }
            counter = (counter + 1) & 0x0F
        }

        @Override
        void write(byte[] data) throws IOException {
            super.write(data)
            int reg = data[0] & 0xFF
            if (reg == 0x0A && data.length >= 3) command(data[2] & 0xFF, data[1] & 0xFF)
            else if (reg == 0x0B && data.length >= 2) command(data[1] & 0xFF, 0)
            else if (reg == 0x0F && data.length >= 2) irqEnable = data[1] & 0xFF
        }

        @Override
        byte[] writeRead(byte[] data, int n) throws IOException {
            super.writeRead(data, n)
            int reg = data[0] & 0xFF
            List<Integer> block
            if (reg == 0x00) block = [partId, 0x03, 0x10]
            else if (reg == 0x10) block = [resp1]
            else if (reg == 0x11) block = [resp0()]
            else if (reg == 0x12) { block = [irqStatus]; irqStatus = 0 }
            else if (reg >= 0x13) block = (hostout as List<Integer>).subList(reg - 0x13, 26)
            else block = []
            byte[] out = new byte[n]
            for (int i = 0; i < n && i < block.size(); i++) out[i] = (byte) block[i]
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

    boolean wrote(List<Integer> frame) {
        c.writes().any { byte[] f -> f.collect { it & 0xFF } == frame }
    }

    List<Integer> params(int from, int to) {
        (from..to).collect { int i -> c.params[i] }
    }

    Si1133Full make() {
        c = new Mock()
        def s = new Si1133Full(c)
        assert c.writes().isEmpty()
        s.init()
        s
    }

    def "init resets and configures the UV and white channels, read decodes them"() {
        given:
        def s = make()

        expect:
        c.commands[0] == 0x01
        params(0x02, 0x04) == [0x18, 0x09, 0x40]
        params(0x06, 0x08) == [0x0B, 0x07, 0x40]
        c.params[0x01] == 0x03
        c.irqEnable == 0x3F
        wrote([0x0A, 0x03, 0x81])
        s.read() == new Si1133Minimal.Reading(0x012345, -5)
        c.commands[-1] == 0x11
    }

    def "init rejects a wrong PART_ID"() {
        given:
        c = new Mock()
        c.partId = 0x32

        when:
        new Si1133Minimal(c).init()

        then:
        thrown(IOException)
    }

    def "channel configuration, multi-channel reads and scratch reads"() {
        given:
        def s = make()

        when:
        s.configureChannel(2, Si1133Minimal.MUX_LARGE_IR, 3, 2, 1, true, false, 1)
        s.enableChannels(0x07)

        then:
        params(0x0A, 0x0C) == [0x22, 0xA3, 0x08]
        c.params[0x01] == 0x07
        s.readChannels() as List == [0x012345, -5, 0xABCD]
        s.readChannels(false) as List == [0x012345, -5, 0xABCD]
        s.read() == new Si1133Minimal.Reading(0x012345, -5)

        when:
        c.samples[5] = 1234
        def uv = s.readUv()

        then:
        uv == 1234
        params(0x16, 0x17) == [0x18, 0x09]
        c.params[0x01] == 0x07
        s.readWhite() == 1234
        c.params[0x16] == 0x0B
        s.readIr(Si1133Minimal.MUX_MEDIUM_IR) == 1234
        c.params[0x16] == 0x01
        s.readIr() == 1234
        c.params[0x16] == 0x00

        when:
        s.force()

        then:
        c.commands[-1] == 0x11
    }

    def "argument checks: #label"() {
        given:
        def s = make()

        when:
        call.call(s)

        then:
        thrown(IllegalArgumentException)

        where:
        label              | call
        'channel 6'        | { Si1133Full d -> d.configureChannel(6, 0, 0) }
        'hw gain 12'       | { Si1133Full d -> d.configureChannel(0, 0x18, 12) }
        'mask 0x40'        | { Si1133Full d -> d.enableChannels(0x40) }
        'ir mux 0x0B'      | { Si1133Full d -> d.readIr(0x0B) }
        'rate 0'           | { Si1133Full d -> d.startAutonomous(0.0d, 1, 1, 1) }
        'burst 128'        | { Si1133Full d -> d.setBurst(128) }
        'threshold 3'      | { Si1133Full d -> d.setThreshold(3, 0) }
        'address 0x80'     | { Si1133Full d -> d.setI2cAddress(0x80) }
    }

    def "autonomous mode, burst and thresholds"() {
        given:
        def s = make()

        when:
        s.startAutonomous(1.0d, 2, 3, 4)

        then:
        ((c.params[0x1A] << 8) | c.params[0x1B]) == 1250
        params(0x1C, 0x1E) == [2, 3, 4]
        c.params[0x05] == 0x40
        c.params[0x09] == 0x40
        c.commands[-1] == 0x13

        when:
        s.setChannelCounter(2, 3)
        s.pause()

        then:
        c.params[0x0D] == 0xC0
        c.commands[-1] == 0x12

        when:
        s.setBurst(4)

        then:
        c.params[0x2B] == 0x84

        when:
        s.setBurst(0)
        s.setThreshold(1, 0x1234)
        s.setChannelThreshold(0, 2)

        then:
        c.params[0x2B] == 0
        params(0x27, 0x28) == [0x12, 0x34]
        c.params[0x04] == 0x42
    }

    def "parameters, response, status, identity, address and reset"() {
        given:
        def s = make()

        when:
        s.writeParameter(0x1C, 0x55)

        then:
        c.params[0x1C] == 0x55
        s.readParameter(0x1C) == 0x55
        c.commands[-1] == 0x5C
        s.readResponse() == new Si1133Full.Response(0x20, false, c.counter)

        when:
        s.writeParameter(0x30, 1)

        then:
        thrown(IOException)
        c.commands[-1] == 0x00
        c.error == 0

        when:
        c.irqStatus = 0x05

        then:
        s.dataReady() == 0x05
        s.dataReady() == 0
        s.readId() == new Si1133Full.Id(0x33, 0x03, 0x10)

        when:
        s.setI2cAddress(0x30)
        def addr = c.params[0x00]
        s.reset()

        then:
        addr == 0x30
        c.params[0x01] == 0x03
        c.params[0x02] == 0x18
        s.read() == new Si1133Minimal.Reading(0x012345, -5)
    }

    def "interrupt enable, poll and edge subscription"() {
        given:
        def s = make()
        def pin = new FakePin()
        def seen = []

        when:
        s.disableInterrupt(Si1133Full.SOURCE_CHANNEL1)
        def disabled = c.irqEnable
        s.enableInterrupt(Si1133Full.SOURCE_CHANNEL1)
        c.irqStatus = 0x02

        then:
        disabled == 0x3D
        c.irqEnable == 0x3F
        s.pollInterrupt() == 0x02

        when:
        s.onInterrupt({ int st -> seen << st } as IntConsumer, pin)
        c.irqStatus = 0x01
        pin.handler.onEdge()

        then:
        pin.trigger == EdgeTrigger.FALLING
        seen == [0x01]

        when:
        s.offInterrupt()

        then:
        pin.handler == null
    }

    def "a command without counter change times out"() {
        given:
        c = new Mock()
        def s = new Si1133Minimal(c)
        s.init()
        c.ignoreCommands = true

        when:
        s.read()

        then:
        thrown(IOException)
    }
}
