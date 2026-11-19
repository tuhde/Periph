package it.uhde.periph.chips.led

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Pca9685Spec extends Specification {

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length]
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i]
        r
    }

    /** Data writes only: the register-pointer writes of reads (one byte) are dropped. */
    private static List<byte[]> data(MockConnection c) {
        List<byte[]> r = new ArrayList<byte[]>()
        for (byte[] w : c.writes()) if (w.length > 1) r.add(w)
        r
    }

    private static byte[] last(MockConnection c) {
        List<byte[]> w = data(c)
        w[w.size() - 1]
    }

    private static void assertArr(byte[] expected, byte[] actual) {
        assert Arrays.equals(expected, actual)
    }

    private static void assertArr(int[] expected, int[] actual) {
        assert Arrays.equals(expected, actual)
    }

    private static void assertEq(Object expected, Object actual) {
        assert expected == actual
    }

    private static void assertEq(double expected, double actual, double delta) {
        assert Math.abs(expected - actual) <= delta
    }

    private static void throwsIae(Closure<?> body) {
        boolean thrown = false
        try {
            body.call()
        } catch (IllegalArgumentException ignored) {
            thrown = true
        }
        assert thrown
    }

    def "full API"() {
        expect:
        run()
    }

    private static boolean run() {
MockConnection c = new MockConnection()
        Pca9685Full d = new Pca9685Full(c)
        List<byte[]> w = data(c)
        assertEq(3, w.size())
        assertArr(b(0x00, 0x30), w[0])
        assertArr(b(0xFE, 5), w[1])
        assertArr(b(0x00, 0x20), w[2])

        c.writes().clear()
        d.setDuty(0, 0.5)
        assertArr(b(0x06, 0, 0, 0x00, 0x08), last(c))
        d.setDuty(15, 0.0)
        assertArr(b(0x42, 0, 0, 0, 0x10), last(c))
        d.setDuty(1, 1.0)
        assertArr(b(0x0A, 0, 0x10, 0, 0), last(c))
        throwsIae { d.setDuty(16, 0.5) }
        throwsIae { d.setDuty(0, 1.1) }
        throwsIae { d.setDuty(-1, 0.5) }

        d.setPwm(2, 409, 1228)
        assertArr(b(0x0E, 0x99, 0x01, 0xCC, 0x04), last(c))
        assertArr([409, 1228] as int[], d.getPwm(2))
        throwsIae { d.setPwm(0, 4096, 1) }
        throwsIae { d.setPwm(0, 5, 5) }

        d.setFullOn(3)
        assertArr(b(0x12, 0, 0x10, 0, 0), last(c))
        d.setFullOff(3)
        assertArr(b(0x12, 0, 0, 0, 0x10), last(c))
        d.setAllDuty(0.25)
        assertArr(b(0xFA, 0, 0, 0x00, 0x04), last(c))
        d.setAllPwm(0, 100)
        assertArr(b(0xFA, 0, 0, 100, 0), last(c))

        c.setRegister(0x00, 0x20)
        c.writes().clear()
        d.setFrequency(50)
        w = data(c)
        assertEq(3, w.size())
        assertArr(b(0x00, 0x30), w[0])
        assertArr(b(0xFE, 121), w[1])
        assertArr(b(0x00, 0x20), w[2])
        assertEq(25e6 / (4096 * 122), d.getFrequency(), 1e-6)
        d.setFrequency(1e6)
        w = data(c)
        assertArr(b(0xFE, 3), w[w.size() - 2])
        d.setFrequency(1)
        w = data(c)
        assertArr(b(0xFE, 255), w[w.size() - 2])
        c.setRegister(0x00, 0xA0)
        c.writes().clear()
        d.setFrequency(200)
        w = data(c)
        assertArr(b(0xFE, 30), w[1])
        assertArr(b(0x00, 0xA0), w[w.size() - 1])
        throwsIae { d.setFrequency(0) }

        c.setRegister(0xFE, 121)
        d.setPulseWidth(15, 0.0015)
        assertArr(b(0x42, 0, 0, 307 & 0xFF, 307 >> 8), last(c))

        c.setRegister(0x00, 0x20)
        d.sleep()
        assertArr(b(0x00, 0x30), last(c))
        c.setRegister(0x00, 0xB0)
        c.writes().clear()
        d.wake()
        w = data(c)
        assertArr(b(0x00, 0x20), w[0])
        assertArr(b(0x00, 0xA0), w[1])
        d.restart()
        assertArr(b(0x00, 0xA0), last(c))

        c.setRegister(0x01, 0x04)
        d.setOutputMode(true, true, Pca9685Full.OFF_HIGHZ)
        assertArr(b(0x01, 0x12), last(c))
        d.setOutputMode(false, false, Pca9685Full.OFF_LOW)
        assertArr(b(0x01, 0x04), last(c))
        throwsIae { d.setOutputMode(false, false, 3) }
        d.setUpdateOnAck(true)
        assertArr(b(0x01, 0x0C), last(c))
        d.setUpdateOnAck(false)
        assertArr(b(0x01, 0x04), last(c))

        c.setRegister(0x00, 0x20)
        c.writes().clear()
        d.useExternalClock()
        w = data(c)
        assertArr(b(0x00, 0x30), w[0])
        assertArr(b(0x00, 0x70), w[1])
        assertArr(b(0x00, 0x60), w[2])

        c.setRegister(0x00, 0x20)
        c.writes().clear()
        d.setAllcall(true)
        w = data(c)
        assertArr(b(0x05, 0xE0), w[0])
        assertArr(b(0x00, 0x21), w[1])
        d.setSubaddress(2, 0x71, true)
        w = data(c)
        assertArr(b(0x03, 0xE2), w[w.size() - 2])
        assertArr(b(0x00, 0x25), w[w.size() - 1])
        throwsIae { d.setSubaddress(4, 0x71, true) }
        throwsIae { d.setAllcall(true, 0x80) }

        c.writes().clear()
        d.reset()
        w = data(c)
        assertArr(b(0x00, 0x10), w[0])
        assertArr(b(0x00, 0x11), w[w.size() - 1])
        assert (w.any { Arrays.equals(it, b(0xFE, 0x1E)) })

        MockConnection g = new MockConnection()
        Pca9685Full.softwareReset(g)
        assertEq(1, g.writes().size())
        assertArr(b(0x06), g.writes()[0])

        c.setRegister(0x00, 0x11, 0x04)
        assertArr([0x11, 0x04] as int[], d.readMode())
        assertEq(16, Pca9685Minimal.CHANNELS)
        true
    }
}
