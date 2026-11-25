package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.function.IntConsumer

class Mpl3115a2Spec extends Specification {

    /** RST (0x04) and OST (0x02) are self-clearing in CTRL_REG1 on the real chip. */
    static class Mock extends MockConnection {
        @Override
        void write(byte[] data) throws IOException {
            super.write(data)
            if (data.length == 2 && (data[0] & 0xFF) == 0x26) setRegister(0x26, data[1] & ~0x06 & 0xFF)
        }
    }

    Mock c
    Mpl3115a2Full s

    def setup() {
        c = new Mock()
        c.setRegister(0x0C, 0xC4)
        c.setRegister(0x06, 0x0E)
        c.setRegister(0x01, 0x62, 0xF3, 0x80, 0x19, 0x80)   // 101326.0 Pa, 25.5 °C
        s = new Mpl3115a2Full(c)
    }

    int last(int reg, int idx) {
        int v = -1
        for (byte[] w : c.writes()) if (w.length > idx && (w[0] & 0xFF) == reg) v = w[idx] & 0xFF
        return v
    }

    def "constructor does no bus access and init configures the chip"() {
        expect:
        c.writes().isEmpty()

        when:
        s.init()

        then:
        last(0x13, 1) == 0x07
        last(0x26, 1) == 0x38
    }

    def "init rejects a wrong chip id"() {
        given:
        c.setRegister(0x0C, 0x00)

        when:
        s.init()

        then:
        thrown(IOException)
    }

    def "read converts pressure and temperature"() {
        given:
        s.init()

        when:
        double[] m = s.read()

        then:
        Math.abs(m[0] - 101326.0d) < 1e-6d
        Math.abs(m[1] - 25.5d) < 1e-6d
        last(0x26, 1) == 0x3A

        when:
        c.setRegister(0x04, 0xE7, 0x00)

        then:
        Math.abs(s.readTemperature() + 25.0d) < 1e-6d
    }

    def "altitude switches mode and read switches back"() {
        given:
        s.init()
        c.setRegister(0x01, 0x00, 0x7B, 0x30)

        expect:
        Math.abs(s.readAltitude() - 123.1875d) < 1e-6d
        last(0x26, 1) == 0xBA

        when:
        c.setRegister(0x01, 0xFF, 0x9C, 0x00)

        then:
        Math.abs(s.readAltitude() + 100.0d) < 1e-6d

        when:
        c.setRegister(0x01, 0x62, 0xF3, 0x80)

        then:
        Math.abs(s.read()[0] - 101326.0d) < 1e-6d
    }

    def "oversample"() {
        given:
        s.init()

        when:
        s.setOversample(16)

        then:
        s.getOversample() == 16
        last(0x26, 1) == 0x20

        when:
        s.setOversample(3)

        then:
        thrown(IllegalArgumentException)
    }

    def "configuration writes"() {
        given:
        s.init()

        when:
        s.setSeaLevelPressure(101326.0d)
        s.setOffsets(-8.0d, 1.0d, -3)
        s.setTarget(100000.0d, 200.0d, -5, 3)

        then:
        last(0x14, 1) == 0xC5
        last(0x14, 2) == 0xE7
        last(0x2B, 1) == 0xFE
        last(0x2C, 1) == 16
        last(0x2D, 1) == 0xFD
        last(0x16, 1) == 0xC3
        last(0x16, 2) == 0x50
        last(0x18, 1) == 0xFB
        last(0x19, 2) == 0x64
        last(0x1B, 1) == 3

        when:
        s.setOffsets(1000.0d, 0.0d, 0)

        then:
        thrown(IllegalArgumentException)
    }

    def "continuous acquisition and delta"() {
        given:
        s.init()

        when:
        s.startContinuous(2)

        then:
        (last(0x27, 1) & 0x0F) == 2
        last(0x26, 1) == 0x39
        s.dataReady()

        when:
        double[] v = s.readContinuous()
        s.stopContinuous()
        c.setRegister(0x07, 0x00, 0x00, 0x40, 0x00, 0x10)
        double[] d = s.readDelta()

        then:
        Math.abs(v[0] - 101326.0d) < 1e-6d
        Math.abs(v[1] - 25.5d) < 1e-6d
        last(0x26, 1) == 0x38
        Math.abs(d[0] - 1.0d) < 1e-6d
        Math.abs(d[1] - 0.0625d) < 1e-6d

        when:
        s.startContinuous(16)

        then:
        thrown(IllegalArgumentException)
    }

    def "min max and FIFO"() {
        given:
        s.init()
        c.setRegister(0x1C, 0x62, 0xF3, 0x80, 0x19, 0x80)

        expect:
        Math.abs(s.readMinMax()[0] - 101326.0d) < 1e-6d

        when:
        s.clearMinMax()
        s.fifoSetup(1, 16)

        then:
        last(0x1C, 3) == 0
        last(0x0F, 1) == 0x50

        when:
        s.fifoSetup(2, 0)

        then:
        thrown(IllegalArgumentException)

        when:
        c.setRegister(0x0D, 0xC2)
        c.setRegister(0x0E, 0x62, 0xF3, 0x80, 0x19, 0x80)
        List<double[]> samples = s.fifoRead()

        then:
        s.fifoStatus() == [1, 1, 2] as int[]
        samples.size() == 2
        Math.abs(samples[0][0] - 101326.0d) < 1e-6d
    }

    def "interrupts"() {
        given:
        s.init()

        when:
        s.setInterruptPins(true, true, false, false)
        s.enableInterrupt(Mpl3115a2Full.SOURCE_TTH, 1)

        then:
        last(0x28, 1) == 0x30
        (last(0x29, 1) & 0x04) != 0
        (last(0x2A, 1) & 0x04) != 0

        when:
        s.enableInterrupt(Mpl3115a2Full.SOURCE_DRDY, 2)

        then:
        (last(0x2A, 1) & 0x80) == 0

        when:
        s.disableInterrupt(Mpl3115a2Full.SOURCE_TTH)
        c.setRegister(0x12, 0x84)

        then:
        (last(0x29, 1) & 0x04) == 0
        s.pollInterrupt() == 0x84

        when:
        s.enableInterrupt(1, 3)

        then:
        thrown(IllegalArgumentException)

        when:
        c.setRegister(0x2A, 0x04)
        int[] got = [-1] as int[]
        s.onInterrupt({ int status -> got[0] = status } as IntConsumer, 1)
        Thread.sleep(300)
        s.offInterrupt()

        then:
        got[0] == 0x04
    }

    def "identity and reset"() {
        given:
        s.init()
        c.setRegister(0x11, 0x01)

        expect:
        s.whoAmI() == 0xC4
        s.isActive()

        when:
        s.reset()

        then:
        last(0x26, 1) == 0x38
    }
}
