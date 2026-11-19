package it.uhde.periph.chips.color

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class As7262Spec extends Specification {

    /** AS7262 simulator: STATUS/WRITE/READ hardware registers over virtual registers. */
    static class Sim extends MockConnection {
        int[] v = new int[0x2C]
        int pending = -1
        int out = -1
        boolean stall = false
        List<int[]> vwrites = []

        Sim() {
            v[0] = 0x40; v[1] = 0x3E; v[2] = 0x41; v[3] = 0x21; v[4] = 0x08; v[5] = 0xFF; v[6] = 25
        }

        @Override
        byte[] read(int reg, int length) {
            if (reg == 0x00) return [(byte) (stall ? 0x02 : (out >= 0 ? 0x01 : 0x00))] as byte[]
            if (reg == 0x02) {
                byte b = (byte) (out >= 0 ? out : 0)
                out = -1
                return [b] as byte[]
            }
            return new byte[length]
        }

        @Override
        void write(int reg, byte[] data) {
            int b = data[0] & 0xFF
            if (pending >= 0) {
                int r = pending
                pending = -1
                vwrites.add([r, b] as int[])
                if (r == 4) {
                    if ((b & 0x80) != 0) { v[4] = 0x08; v[5] = 0xFF; v[7] = 0; return }
                    v[4] = b | (v[4] & 0x02)
                    if (((b >> 2) & 3) == 3) v[4] |= 0x02
                } else {
                    v[r] = b
                }
            } else if ((b & 0x80) != 0) {
                pending = b & 0x7F
            } else {
                out = v[b]
                if (b >= 0x08) v[4] &= ~0x02
            }
        }

        boolean wrote(int r, int value) {
            return vwrites.any { it[0] == r && it[1] == value }
        }

        void loadCal(float... vals) {
            for (int i = 0; i < vals.length; i++) {
                byte[] b = ByteBuffer.allocate(4).putFloat(vals[i]).array()
                for (int k = 0; k < 4; k++) v[0x14 + 4 * i + k] = b[k] & 0xFF
            }
        }
    }

    Sim c
    As7262Full s

    def setup() {
        c = new Sim()
        s = new As7262Full(c)
    }

    def "constructor does no bus access"() {
        expect:
        c.vwrites.isEmpty()
    }

    def "init writes the defaults"() {
        when:
        s.init()

        then:
        c.v[5] == 0x3B
        ((c.v[4] >> 4) & 3) == 2
        c.v[7] == 0
        c.wrote(4, 0x80)
    }

    def "read returns the calibrated channels"() {
        given:
        s.init()
        c.loadCal(1.0f, 2.5f, 3.0f, 4.0f, 5.0f, 6.5f)

        when:
        As7262Minimal.Channels ch = s.read()

        then:
        Math.abs(ch.violet - 1.0f) < 1e-4
        Math.abs(ch.blue - 2.5f) < 1e-4
        Math.abs(ch.red - 6.5f) < 1e-4
        c.wrote(4, 0x2C)
        s.temperature() == 25.0d
    }

    def "raw counts and single channels"() {
        given:
        s.init()
        for (int i = 0; i < 12; i++) c.v[0x08 + i] = (i % 2 == 0) ? 0x01 : 0x02
        c.loadCal(0f, 0f, 0f, 0f, 0f, 7.25f)

        expect:
        s.readRaw().violet == 0x0102
        s.readChannelRaw(5) == 0x0102
        Math.abs(s.readChannel(5) - 7.25f) < 1e-4

        when:
        s.readChannel(6)

        then:
        thrown(IllegalArgumentException)
    }

    def "gain integration time and mode"() {
        given:
        s.init()

        when:
        s.setGain(64d)

        then:
        s.getGain() == 64d

        when:
        s.setGain(3.7d)

        then:
        s.getGain() == 3.7d

        when:
        s.setGain(1d)

        then:
        s.getGain() == 1d

        when:
        s.setGain(5d)

        then:
        thrown(IllegalArgumentException)

        when:
        s.setIntegrationTime(50d)

        then:
        c.v[5] == 18
        Math.abs(s.getIntegrationTime() - 50.4d) < 1e-6

        when:
        s.setIntegrationTime(800d)

        then:
        thrown(IllegalArgumentException)

        when:
        s.setMode(2)

        then:
        s.getMode() == 2

        when:
        s.setContinuous(3)

        then:
        thrown(IllegalArgumentException)
    }

    def "interrupt enable bit"() {
        given:
        s.init()

        when:
        s.setInterrupt(true)

        then:
        (c.v[4] & 0x40) != 0

        when:
        s.setInterrupt(false)

        then:
        (c.v[4] & 0x40) == 0
    }

    def "leds keep each other"() {
        given:
        s.init()

        when:
        s.setLed(true, 25d)

        then:
        c.v[7] == 0x18

        when:
        s.setIndicatorLed(true, 4d)

        then:
        c.v[7] == (0x18 | 0x05)

        when:
        s.setLed(false, 100d)

        then:
        c.v[7] == (0x30 | 0x05)

        when:
        s.setLed(true, 200d)

        then:
        thrown(IllegalArgumentException)
    }

    def "versions and virtual access"() {
        given:
        s.init()

        expect:
        s.hardwareVersion() == [0x40, 0x3E] as int[]
        s.deviceType() == 0x40
        s.firmwareVersion() == [2, ((0x21 & 0x0F) << 2) | 1, 1] as int[]

        when:
        s.writeVirtual(0x05, 0x10)

        then:
        s.readVirtual(0x05) == 0x10

        when:
        s.softReset()

        then:
        c.v[5] == 0xFF
    }

    def "measure data ready and poll"() {
        given:
        s.init()

        when:
        s.measure()

        then:
        s.dataReady()
        s.pollInterrupt() == 1
    }

    def "interrupt polling fallback delivers"() {
        given:
        s.init()
        s.setIntegrationTime(10d)
        s.measure()
        AtomicInteger got = new AtomicInteger()

        when:
        s.onInterrupt({ int st -> got.set(st) } as java.util.function.IntConsumer, null)
        long end = System.currentTimeMillis() + 2000
        while (got.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(10)
        s.offInterrupt()

        then:
        got.get() == 1
        (c.v[4] & 0x40) == 0
    }

    def "handshake times out"() {
        given:
        s.init()
        c.stall = true

        when:
        s.readVirtual(0)

        then:
        thrown(IOException)
    }
}
