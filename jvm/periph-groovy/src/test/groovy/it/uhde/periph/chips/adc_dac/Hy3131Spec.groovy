package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.OutputPin
import spock.lang.Specification

class Hy3131Spec extends Specification {

    /** Models the HY3131 framing: (addr<<1)|R/W command, one blank clock on reads, auto-increment. */
    static class Chip implements Connection {
        Map<Integer, Integer> mem = [:]
        List<byte[]> log = []
        int intf = 0x07

        void set(int reg, int... vals) {
            vals.eachWithIndex { int v, int i -> mem[reg + i] = v & 0xFF }
        }

        int get(int reg) { mem.containsKey(reg) ? mem[reg] : 0 }

        void write(byte[] data) {
            log << (data.clone() as byte[])
            int addr = (data[0] & 0xFF) >> 1
            for (int i = 1; i < data.length; i++) mem[addr + i - 1] = data[i] & 0xFF
        }

        byte[] read(int n) { new byte[n] }

        byte[] writeRead(byte[] data, int n) {
            int addr = (data[0] & 0xFF) >> 1
            int[] vals = new int[n]
            for (int i = 0; i < n; i++) {
                int a = addr + i
                if (a == 0x1E) { vals[i] = intf; intf = 0 } else { vals[i] = get(a) }
            }
            byte[] out = new byte[n]
            for (int i = 0; i < n; i++) {
                out[i] = (byte) (i == 0 ? vals[0] >> 1 : ((vals[i - 1] & 1) << 7) | (vals[i] >> 1))
            }
            out
        }

        void enable() {}
        void disable() {}
        boolean isEnabled() { true }
        InputPin intPin() { null }
        OutputPin enPin() { null }
        void close() {}
    }

    def "init applies the Minimal defaults"() {
        given:
        def c = new Chip()
        new Hy3131Minimal(c, 1.2f)

        expect:
        c.log[0] == [(0x37 << 1) as byte, 0x60 as byte] as byte[]
        c.get(0x33) == 0x28
        c.get(0x31) == 0xC0
        c.get(0x30) == 0x00
        c.get(0x24) == 0xA3
        c.get(0x28) == 0x11
        c.get(0x22) == 0x07
        c.get(0x23) == 0x80
    }

    def "register framing shifts reads by one bit and auto-increments writes"() {
        given:
        def c = new Chip()
        def adc = new Hy3131Minimal(c, 1.2f)
        c.set(0x70, 0xA5, 0x5A)

        expect:
        adc.readRegister(0x70, 2) == [0xA5 as byte, 0x5A as byte] as byte[]

        when:
        adc.writeRegister(0x60, [1, 2, 3] as byte[])

        then:
        c.get(0x60) == 1
        c.get(0x61) == 2
        c.get(0x62) == 3
    }

    def "readRaw and readVoltage"() {
        given:
        def c = new Chip()
        def adc = new Hy3131Minimal(c, 1.2f)
        c.set(0x00, 0, 0, 0x40)
        c.intf = 0x04

        expect:
        adc.readRaw() == 0x400000

        when:
        c.intf = 0x04

        then:
        Math.abs(adc.readVoltage() - (0.5d * 1.2d / 0.9d)) < 1e-5

        when:
        c.set(0x00, 0, 0, 0x80)
        c.intf = 0x04

        then:
        adc.readRaw() == -8388608

        when:
        c.intf = 0x04

        then:
        adc.dataReady()

        when:
        c.set(0x00, 1, 0, 0)

        then:
        adc.readRaw() == 1
    }

    def "full feature set"() {
        given:
        def c = new Chip()
        def f = new Hy3131Full(c, 1.2f)

        when:
        f.configureAd1(4, 2, 3, 1, 1, 3)
        c.set(0x00, 0, 0, 0x40)
        c.intf = 0x04

        then:
        c.get(0x22) == ((1 << 5) | (2 << 3) | 4)
        c.get(0x23) == 0x93
        ((c.get(0x25) >> 4) & 3) == 3
        Math.abs(f.readVoltage() - ((0.5d * 0.3333333333d - 0.25d) * 1.2d / 3.6d)) < 1e-5

        when:
        f.setAd1Input(0x8, 0x5, 1)
        f.setAd1Reference(2, 6)

        then:
        c.get(0x24) == 0x85
        ((c.get(0x33) >> 2) & 3) == 1
        c.get(0x28) == 0x26

        when:
        f.enableAd2(3, 2, 0, false, false, 1, true)
        c.set(0x03, 0xFF, 0xFF, 0xFF)
        c.intf = 0x02

        then:
        c.get(0x26) == 0x83
        (c.get(0x34) & 0x84) == 0x84
        ((c.get(0x25) >> 6) & 3) == 2
        f.readAd2Raw() == -1

        when:
        f.enableLpf(3)
        f.enableRms()
        c.set(0x09, 0x00, 0x90, 0x01, 0x00, 0x00)
        c.intf = 0x10

        then:
        c.get(0x29) == (0xC0 | (3 << 3))
        Math.abs(f.readRms() - 320.0d) < 1e-3

        when:
        f.enablePeakHold(2)
        c.set(0x0E, 0x00, 0x00, 0xFE, 0x00, 0x00, 0x01)

        then:
        (c.get(0x29) & 0x07) == 0x06
        f.readPeak() == [-131072, 65536] as int[]

        when:
        f.startCounter(0x1234)
        c.set(0x14, 0x01)
        c.intf = 0x01

        then:
        c.get(0x1C) == 0x34
        c.get(0x1D) == 0x12
        (c.get(0x20) & 0x0A) == 0x0A
        f.readCounter()[3] == 1L

        when:
        c.intf = 0x14

        then:
        f.readFlags() == 0x14

        when:
        f.enableInterrupts(0xFF)

        then:
        c.get(0x1F) == 0x1F
    }
}
