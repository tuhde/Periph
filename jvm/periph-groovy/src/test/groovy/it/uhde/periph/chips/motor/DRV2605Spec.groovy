package it.uhde.periph.chips.motor

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class DRV2605Spec extends Specification {

    /** Mock whose DEV_RESET and GO bits self-clear like the real chip. */
    static class ChipMock extends MockConnection {
        ChipMock() { setRegister(0x00, 0x60) }

        @Override
        void write(byte[] data) {
            super.write(data)
            if (data.length >= 2 && data[0] == 0x01) setRegister(0x01, data[1] & 0x7F)
            if (data.length >= 2 && data[0] == 0x0C) setRegister(0x0C, 0)
        }
    }

    private static byte[] lastWrite(MockConnection conn, int reg) {
        def w = conn.writes().findAll { byte[] b -> b.length >= 2 && (b[0] & 0xFF) == reg }
        return w ? Arrays.copyOfRange(w.last(), 1, w.last().length) : null
    }

    private static int lastByte(MockConnection conn, int reg) {
        def v = lastWrite(conn, reg)
        return v ? (v[0] & 0xFF) : -1
    }

    private static boolean wrote(MockConnection conn, int reg, int value) {
        return conn.writes().any { byte[] b -> b.length == 2 && (b[0] & 0xFF) == reg && (b[1] & 0xFF) == value }
    }

    def "init sequence for ERM and LRA"() {
        when:
        def erm = new ChipMock()
        new DRV2605Minimal(erm)
        def lra = new ChipMock()
        new DRV2605Minimal(lra, true)

        then:
        erm.writes()[0] == [0x01, 0x80] as byte[]
        wrote(erm, 0x01, 0x00)
        lastByte(erm, 0x1A) == 0x36
        lastByte(erm, 0x03) == 0x01
        lastByte(lra, 0x1A) == 0xB6
        lastByte(lra, 0x03) == 0x06
    }

    def "init rejects a wrong DEVICE_ID"() {
        given:
        def conn = new ChipMock()
        conn.setRegister(0x00, 0xE0)

        when:
        new DRV2605Minimal(conn)

        then:
        thrown(IllegalStateException)
    }

    def "minimal playback"() {
        given:
        def conn = new ChipMock()
        def chip = new DRV2605Minimal(conn)

        when:
        chip.playEffect(47)

        then:
        lastWrite(conn, 0x04) == [47, 0] as byte[]
        lastByte(conn, 0x0C) == 1

        when:
        chip.playEffect(effect)

        then:
        thrown(IllegalArgumentException)

        when:
        chip.stop()
        conn.setRegister(0x0C, 1)
        def playing = chip.isPlaying()
        conn.setRegister(0x0C, 0)

        then:
        lastByte(conn, 0x0C) == 0
        playing
        !chip.isPlaying()

        where:
        effect << [0, 124]
    }

    def "sequence encoding and waitDone"() {
        given:
        def conn = new ChipMock()
        def chip = new DRV2605Full(conn)

        when:
        chip.playSequence(DRV2605Full.SequenceStep.effect(1), DRV2605Full.SequenceStep.delay(100), DRV2605Full.SequenceStep.effect(10))

        then:
        lastWrite(conn, 0x04) == [1, 0x8A, 10, 0] as byte[]

        when:
        chip.playSequence((1..8).collect { DRV2605Full.SequenceStep.effect(it) } as DRV2605Full.SequenceStep[])

        then:
        lastWrite(conn, 0x04) == [1, 2, 3, 4, 5, 6, 7, 8] as byte[]

        when:
        chip.playSequence((1..9).collect { DRV2605Full.SequenceStep.effect(1) } as DRV2605Full.SequenceStep[])

        then:
        thrown(IllegalArgumentException)

        when:
        chip.playSequence(DRV2605Full.SequenceStep.delay(2000))

        then:
        thrown(IllegalArgumentException)

        when:
        conn.setRegister(0x0C, 0)
        def done = chip.waitDone(50)
        conn.setRegister(0x0C, 1)
        def timedOut = !chip.waitDone(20)

        then:
        done
        timedOut
    }

    def "mode, standby, library and hi-z"() {
        given:
        def conn = new ChipMock()
        def chip = new DRV2605Full(conn)

        when:
        chip.setMode(5)
        def m = lastByte(conn, 0x01)
        chip.standby()
        def s = lastByte(conn, 0x01)
        chip.wake()
        def w = lastByte(conn, 0x01)
        chip.setHiZ(true)
        def hiz = lastByte(conn, 0x03)
        chip.setLibrary(6)
        def lib = lastByte(conn, 0x03)

        then:
        m == 0x05
        s == 0x45
        w == 0x05
        (hiz & 0x10) == 0x10
        lib == 0x16

        when:
        chip.setLibrary(7)

        then:
        thrown(IllegalArgumentException)

        when:
        conn.setRegister(0x1A, 0x36)
        chip.setActuator(true)

        then:
        lastByte(conn, 0x1A) == 0xB6
    }

    def "real-time playback"() {
        given:
        def conn = new ChipMock()
        def chip = new DRV2605Full(conn)

        when:
        chip.rtpLevel(0.5d)

        then:
        wrote(conn, 0x01, 0x05)
        lastByte(conn, 0x02) == 64

        when:
        chip.rtpWrite(200)

        then:
        lastByte(conn, 0x02) == 200

        when:
        chip.stop()

        then:
        lastByte(conn, 0x02) == 0
        lastByte(conn, 0x01) == 0x00

        when:
        chip.rtpLevel(1.5d)

        then:
        thrown(IllegalArgumentException)

        when:
        chip.setRtpFormat(true)

        then:
        (lastByte(conn, 0x1D) & 0x08) == 0x08
    }

    def "loop constraint"() {
        given:
        def conn = new ChipMock()
        def chip = new DRV2605Full(conn)
        conn.setRegister(0x1D, 0x20)

        when:
        chip.setBidirectional(false)

        then:
        thrown(IllegalStateException)

        when:
        chip.setOpenLoop(false)
        chip.setBidirectional(false)

        then:
        lastByte(conn, 0x1D) == 0x00
        (lastByte(conn, 0x1C) & 0x80) == 0
    }

    def "voltage conversions"() {
        given:
        def conn = new ChipMock()
        def chip = new DRV2605Full(conn)
        conn.setRegister(0x1A, 0x36)

        when:
        chip.setRatedVoltage(3.0d)

        then:
        lastByte(conn, 0x16) == 141

        when:
        chip.setRatedVoltageCode(77)

        then:
        lastByte(conn, 0x16) == 77

        when:
        conn.setRegister(0x1D, 0x20)
        chip.setOverdriveClamp(3.0d)

        then:
        lastByte(conn, 0x17) == 137

        when:
        conn.setRegister(0x1D, 0x00)
        chip.setOverdriveClamp(3.0d)

        then:
        thrown(IllegalStateException)

        when:
        conn.setRegister(0x1A, 0xB6)
        conn.setRegister(0x1C, 0x30)
        chip.setRatedVoltage(2.0d, 200.0d)
        chip.setDriveTime(2.5d)

        then:
        lastByte(conn, 0x16) == 81
        (lastByte(conn, 0x1B) & 0x1F) == 20

        when:
        chip.setRatedVoltage(2.0d)

        then:
        thrown(IllegalArgumentException)

        when:
        conn.setRegister(0x1A, 0x36)
        chip.setDriveTime(5.0d)

        then:
        (lastByte(conn, 0x1B) & 0x1F) == 20
    }

    def "feedback and time offsets"() {
        given:
        def conn = new ChipMock()
        def chip = new DRV2605Full(conn)
        conn.setRegister(0x1A, 0x80)

        when:
        chip.setFeedback(2, 2, 1)
        chip.setTimeOffsets(10, -5, 0, -640)

        then:
        lastByte(conn, 0x1A) == (0x80 | (2 << 4) | (2 << 2) | 1)
        lastWrite(conn, 0x0D) == [2, 0xFF, 0, 0x80] as byte[]

        when:
        chip.setTimeOffsets(0, 0, 0, 700)

        then:
        thrown(IllegalArgumentException)
    }

    def "calibration flow"() {
        given:
        def conn = new ChipMock()
        def chip = new DRV2605Full(conn)
        conn.setRegister(0x18, 0x0D, 0x6D)
        conn.setRegister(0x1A, 0x36)

        when:
        def r = chip.calibrate(false, 3.0d, 3.0d)

        then:
        wrote(conn, 0x01, 0x07)
        lastByte(conn, 0x16) == 141
        lastByte(conn, 0x17) == 137
        (lastByte(conn, 0x1E) & 0x30) == 0x30
        wrote(conn, 0x0C, 0x01)
        r == new DRV2605Full.Calibration(true, 0x0D, 0x6D, 2)
        lastByte(conn, 0x01) == 0x00

        when:
        conn.setRegister(0x00, 0x68)
        def failed = chip.calibrate(false, 3.0d, 3.0d)
        conn.setRegister(0x00, 0x60)

        then:
        !failed.ok

        when:
        chip.calibrate(true, 2.0d, 2.5d)

        then:
        thrown(IllegalArgumentException)

        when:
        chip.calibrate(true, 2.0d, 2.5d, 200.0d)
        chip.setCalibration(0x20, 0x80, 3)

        then:
        lastWrite(conn, 0x18) == [0x20, 0x80] as byte[]
        chip.getCalibration() == new DRV2605Full.Calibration(true, 0x20, 0x80, 3)
    }

    def "diagnose"() {
        given:
        def conn = new ChipMock()
        def chip = new DRV2605Full(conn)

        expect:
        chip.diagnose()
        wrote(conn, 0x01, 0x06)

        when:
        conn.setRegister(0x00, 0x68)

        then:
        !chip.diagnose()
    }

    def "audio to vibe"() {
        given:
        def conn = new ChipMock()
        def chip = new DRV2605Full(conn)

        when:
        chip.setAudioToVibe(true, 30, 150, 0.0d, 1.8d, 0.0d, 1.0d)

        then:
        lastWrite(conn, 0x11) == [(2 << 2) | 2, 0, 0xFF, 0, 0xFF] as byte[]
        lastByte(conn, 0x01) == 0x04
        (lastByte(conn, 0x1B) & 0x20) == 0x20

        when:
        chip.setAudioToVibe(true, 20, 300)

        then:
        thrown(IllegalArgumentException)
    }

    def "status and readouts"() {
        given:
        def conn = new ChipMock()
        def chip = new DRV2605Full(conn)

        when:
        conn.setRegister(0x00, 0x60 | 0x08 | 0x01)
        def st = chip.readStatus()

        then:
        st == new DRV2605Full.Status(true, false, false, true)
        chip.readDeviceId() == 3

        when:
        conn.setRegister(0x21, 255)
        conn.setRegister(0x22, 0)
        def none = chip.readLraFrequency()
        conn.setRegister(0x22, 52)

        then:
        Math.abs(chip.readSupplyVoltage() - 5.6d) < 1e-9
        !none.present
        Math.abs(chip.readLraFrequency().asDouble - 195.3d) < 0.2d
    }

    def "OTP guards"() {
        given:
        def conn = new ChipMock()
        def chip = new DRV2605Full(conn)
        int n = conn.writes().size()

        when:
        chip.programOtp(false)

        then:
        thrown(IllegalArgumentException)
        conn.writes().size() == n

        when:
        conn.setRegister(0x1E, 0x04)
        chip.programOtp(true)

        then:
        chip.otpProgrammed()
        thrown(IllegalStateException)
    }
}
