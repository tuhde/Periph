package it.uhde.periph.chips.led

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Is31fl3731Spec extends Specification {

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length]
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i]
        r
    }

    /** reg followed by n copies of v. */
    private static byte[] rep(int reg, int n, int v) {
        byte[] r = new byte[n + 1]
        Arrays.fill(r, (byte) v)
        r[0] = (byte) reg
        r
    }

    private static byte[] prepend(int reg, byte[] data) {
        byte[] r = new byte[data.length + 1]
        r[0] = (byte) reg
        System.arraycopy(data, 0, r, 1, data.length)
        r
    }

    private static byte[] withAt(byte[] data, int idx, int v) {
        data[idx] = (byte) v
        data
    }

    private static byte[] last(MockConnection c) {
        def w = c.writes()
        w[w.size() - 1]
    }

    private static boolean throwsIae(Closure<?> body) {
        try {
            body.call()
        } catch (IllegalArgumentException ignored) {
            return true
        }
        false
    }

    def "full API"() {
        given:
        def c = new MockConnection()

        expect:
        def d = new Is31fl3731Full(c)
        def w = c.writes()

        w.size() == 10
        w[0] == b(0xFD, 0x0B)
        w[1] == b(0x0A, 0x00)
        w[2] == b(0x00, 0x00, 0x00)
        w[3] == b(0x05, 0x00)
        w[4] == b(0xFD, 0x00)
        w[5] == rep(0x00, 18, 0xFF)
        w[6] == rep(0x12, 18, 0)
        w[7] == rep(0x24, 144, 0)
        w[8] == b(0xFD, 0x0B)
        w[9] == b(0x0A, 0x01)

        w.clear()
        d.setPixel(2, 5, 200)
        w.size() == 2
        w[0] == b(0xFD, 0x00)
        w[1] == b(0x24 + 37, 200)
        w.clear()
        d.setPixel(8, 15, 1)
        w.size() == 1
        w[0] == b(0x24 + 143, 1)
        d.getPixel(2, 5) == 200
        d.getPixel(0, 0) == 0
        throwsIae { d.setPixel(9, 0, 0) }
        throwsIae { d.setPixel(0, 16, 0) }
        throwsIae { d.setPixel(0, 0, 256) }

        d.fill(0x40)
        last(c) == rep(0x24, 144, 0x40)
        d.clear()
        last(c) == rep(0x24, 144, 0)
        d.getPixel(2, 5) == 0

        byte[] ramp = new byte[144]
        for (int i = 0; i < 144; i++) ramp[i] = (byte) i
        d.setPixels(ramp)
        def expRamp = prepend(0x24, ramp)
        last(c) == expRamp
        d.getPixel(1, 1) == 17
        throwsIae { d.setPixels(new byte[143]) }

        d.setLedEnabled(1, 9, false)
        last(c) == b(0x03, 0xFD)
        d.setLedEnabled(1, 9, true)
        last(c) == b(0x03, 0xFF)
        d.setBlink(1, 9, true)
        last(c) == b(0x15, 0x02)
        d.setBlinkAll(true)
        last(c) == rep(0x12, 18, 0xFF)
        d.setBlinkAll(false)
        last(c) == rep(0x12, 18, 0)

        w.clear()
        d.selectFrame(3)
        w.size() == 1
        w[0] == b(0xFD, 0x03)
        d.setPixel(0, 0, 9)
        last(c) == b(0x24, 9)
        d.copyFrame(3, 5)
        w[w.size() - 2] == b(0xFD, 0x05)
        def copied = withAt(rep(0x24, 144, 0), 1, 9)
        last(c) == copied
        throwsIae { d.selectFrame(8) }
        throwsIae { d.copyFrame(0, 8) }
        w.clear()
        d.clearAllFrames()
        w.count { it.length == 145 } == 8
        d.selectFrame(0)

        w.clear()
        d.showFrame(4)
        w.size() == 2
        w[0] == b(0xFD, 0x0B)
        w[1] == b(0x00, 0x00, 0x04)
        d.setPictureMode()
        last(c) == b(0x00, 0x00)

        d.startAutoPlay(3, 2, 121, 1)
        w[w.size() - 2] == b(0x02, 0x23, 11)
        last(c) == b(0x00, 0x09)
        d.startAutoPlay(0, 0, 704, 0)
        w[w.size() - 2] == b(0x02, 0x00, 0x00)
        throwsIae { d.startAutoPlay(0, 0, 2000, 0) }
        throwsIae { d.startAutoPlay(0, 8, 121, 0) }
        c.setRegister(0x07, 0x15)
        d.stopAutoPlay()
        last(c) == b(0x00, 0x00, 0x05)

        d.setAudioMode(true)
        last(c) == b(0x00, 0x10)
        d.setAudioMode(false)
        last(c) == b(0x00, 0x00)
        d.setAudioSync(true)
        last(c) == b(0x06, 0x01)
        d.setAudioGain(9, true, true)
        last(c) == b(0x0B, 0x1B)
        throwsIae { d.setAudioGain(4, false, false) }
        throwsIae { d.setAudioGain(24, false, false) }
        d.setAudioRate(100)
        last(c) == b(0x0C, 100)

        d.enableBlink(true, 3)
        last(c) == b(0x05, 0x0B)
        d.setIntensityControl(true)
        last(c) == b(0x05, 0x2B)
        d.enableBlink(false, 1)
        last(c) == b(0x05, 0x21)
        throwsIae { d.enableBlink(true, 8) }
        d.setBreath(true, 2, 5, 3)
        last(c) == b(0x08, 0x52, 0x13)
        d.setBreath(false, 0, 0, 0)
        last(c) == b(0x08, 0x00, 0x00)

        d.shutdown(true)
        last(c) == b(0x0A, 0x00)
        d.shutdown(false)
        last(c) == b(0x0A, 0x01)

        c.setRegister(0x07, 0x13)
        d.frameState() == 0x13
        d.currentFrame() == 3
        d.movieFinished()
        c.setRegister(0x07, 0x02)
        !d.movieFinished()

        d.selectFrame(2)
        w.clear()
        c.setRegister(0x03, 0x2A)
        d.readFunctionRegister(0x03) == 0x2A
        last(c) == b(0xFD, 0x02)
        w.clear()
        d.writeFunctionRegister(0x03, 5)
        w.size() == 3
        w[0] == b(0xFD, 0x0B)
        w[1] == b(0x03, 5)
        w[2] == b(0xFD, 0x02)
        throwsIae { d.readFunctionRegister(0x0D) }

        c.setRegister(0x07, 0x14)
        d.pollInterrupt() == 0x14
        last(c) == b(0xFD, 0x02)

        Is31fl3731Full.GAMMA_32.length == 32
        Is31fl3731Full.GAMMA_64.length == 64
        Is31fl3731Full.GAMMA_64[63] == 255

        def m = new Is31fl3731Minimal(new MockConnection())
        m.setPixel(0, 0, 1)
        Is31fl3731Minimal.ROWS == 9
    }
}
