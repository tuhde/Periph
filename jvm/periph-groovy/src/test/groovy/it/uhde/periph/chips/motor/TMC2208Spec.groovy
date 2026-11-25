package it.uhde.periph.chips.motor

import it.uhde.periph.connection.OutputPin
import it.uhde.periph.connection.UartConnectionMock
import spock.lang.Specification

class TMC2208Spec extends Specification {

    static class Pin implements OutputPin {
        List<Boolean> levels = []
        void set(boolean high) { levels << high }
        void close() {}
    }

    def "crc vectors"() {
        expect:
        TMC2208Minimal.crc8([5, 0, 0x80, 0, 0, 1, 0xC1, 0] as byte[], 7) == 0x7F
        TMC2208Minimal.crc8([5, 0, 6, 0] as byte[], 3) == 0x6F
    }

    def "init writes defaults"() {
        given:
        def mock = new UartConnectionMock()

        when:
        new TMC2208Minimal(mock)

        then:
        mock.lastWrite(0x00) == 0x1C1L
        mock.lastWrite(0x01) == 0x07L
        mock.lastWrite(0x11) == 20L
        mock.lastWrite(0x70) == 0xC10D0024L
        mock.datagrams().any { Arrays.equals(it, [5, 0, 0x80, 0, 0, 1, 0xC1, 0x7F] as byte[]) }
        mock.datagrams().any { Arrays.equals(it, [5, 0, 6, 0x6F] as byte[]) }
        def chop = mock.lastWrite(0x6C)
        (chop & 0xF) == 3
        ((chop >> 15) & 3) == 2
        ((chop >> 4) & 7) == 5
        ((chop >> 24) & 0xF) == 4
        ((chop >> 28) & 1) == 1
        def ih = mock.lastWrite(0x10)
        ((ih >> 8) & 0x1F) == 9
        (ih & 0x1F) == 2
        ((ih >> 16) & 0xF) == 6
    }

    def "current and motion"() {
        given:
        def mock = new UartConnectionMock()
        def m = new TMC2208Minimal(mock)

        when:
        m.setCurrent(1.0d)

        then:
        ((mock.lastWrite(0x10) >> 8) & 0x1F) == 18

        when:
        m.setCurrent(5.0d)

        then:
        ((mock.lastWrite(0x10) >> 8) & 0x1F) == 31

        when:
        m.run(200.0d)

        then:
        mock.lastWrite(0x22) == 4474L

        when:
        m.run(-200.0d)

        then:
        mock.lastWrite(0x22) == ((-4474L) & 0xFFFFFFL)

        when:
        m.stop()

        then:
        mock.lastWrite(0x22) == 0L

        when:
        m.disable()

        then:
        (mock.lastWrite(0x6C) & 0xF) == 0

        when:
        m.enable()

        then:
        (mock.lastWrite(0x6C) & 0xF) == 3
    }

    def "status and faults"() {
        given:
        def mock = new UartConnectionMock()
        def m = new TMC2208Minimal(mock)
        mock.regs().put(0x6F, (1L << 31) | 0x01L | 0x08L | 0x20L)

        expect:
        m.isStandstill()
        m.readFault() == (TMC2208Minimal.FAULT_OVERTEMP_WARNING | TMC2208Minimal.FAULT_SHORT_GND | TMC2208Minimal.FAULT_SHORT_SUPPLY)
    }

    def "enn pin, no echo and errors"() {
        given:
        def pin = new Pin()
        def m = new TMC2208Minimal(new UartConnectionMock(), 0.11d, pin, true)

        expect:
        pin.levels == [true]

        when:
        m.enable()

        then:
        !pin.levels.last()

        when:
        def ne = new UartConnectionMock(false)
        new TMC2208Minimal(ne, 0.11d, null, false)

        then:
        ne.lastWrite(0x00) == 0x1C1L

        when:
        def bad = new UartConnectionMock()
        bad.regs().put(0x06, 0L)
        new TMC2208Minimal(bad)

        then:
        thrown(IOException)

        when:
        def flaky = new UartConnectionMock()
        flaky.corruptNextReply()
        new TMC2208Minimal(flaky)

        then:
        flaky.lastWrite(0x00) == 0x1C1L
    }

    def "full configuration"() {
        given:
        def mock = new UartConnectionMock()
        def f = new TMC2208Full(mock)

        when:
        f.setMicrosteps(256)

        then:
        ((mock.lastWrite(0x6C) >> 24) & 0xF) == 0

        when:
        f.setMicrosteps(1)

        then:
        ((mock.lastWrite(0x6C) >> 24) & 0xF) == 8

        when:
        f.setMicrosteps(3)

        then:
        thrown(IllegalArgumentException)

        when:
        f.setMicrosteps(32)
        f.run(100.0d)

        then:
        mock.lastWrite(0x22) == 4474L

        when:
        f.setInterpolation(false)

        then:
        ((mock.lastWrite(0x6C) >> 28) & 1) == 0

        when:
        f.setChopperMode(true)
        f.setDirectionInverted(true)
        f.setIndexOutput(1)

        then:
        (mock.lastWrite(0x00) & 0x4) == 0x4
        (mock.lastWrite(0x00) & 0x8) == 0x8
        (mock.lastWrite(0x00) & 0x30) == 0x10

        when:
        f.setDoubleEdge(true)
        f.setDriverProtection(false, true)

        then:
        ((mock.lastWrite(0x6C) >> 29) & 1) == 1
        ((mock.lastWrite(0x6C) >> 31) & 1) == 1
        ((mock.lastWrite(0x6C) >> 30) & 1) == 0

        when:
        f.setVsense(true)

        then:
        ((mock.lastWrite(0x6C) >> 17) & 1) == 1

        when:
        f.setHoldCurrent(0.2d)

        then:
        (mock.lastWrite(0x10) & 0x1F) == 6

        when:
        f.setCurrentDelay(2, 100)

        then:
        ((mock.lastWrite(0x10) >> 16) & 0xF) == 2
        mock.lastWrite(0x11) == 100L

        when:
        f.setStealthchopThreshold(300L)

        then:
        mock.lastWrite(0x13) == 300L

        when:
        f.configureChopper(4, 1, 3, 2)

        then:
        def c = mock.lastWrite(0x6C)
        (c & 0xF) == 4
        ((c >> 15) & 3) == 1
        ((c >> 4) & 7) == 3
        ((c >> 7) & 0xF) == 2

        when:
        f.configureStealthchop(true, false, 40, 5, 1, 4, 12)

        then:
        def p = mock.lastWrite(0x70)
        (p & 0xFF) == 40
        ((p >> 8) & 0xFF) == 5
        ((p >> 16) & 3) == 1
        ((p >> 18) & 1) == 1
        ((p >> 19) & 1) == 0
        ((p >> 24) & 0xF) == 4
        (p >> 28) == 12

        when:
        f.setFreewheel(1)

        then:
        ((mock.lastWrite(0x70) >> 20) & 3) == 1

        when:
        f.setSendDelay(4)

        then:
        mock.lastWrite(0x03) == (4L << 8)

        when:
        f.setSendDelay(16)

        then:
        thrown(IllegalArgumentException)

        when:
        f.setInternalRsense(true)
        f.setCurrent(0.5d)

        then:
        thrown(IllegalStateException)
    }

    def "full readbacks"() {
        given:
        def mock = new UartConnectionMock()
        def f = new TMC2208Full(mock)
        mock.regs().put(0x6A, 513L)
        mock.regs().put(0x6B, (0x1FFL << 16) | 0x100L)
        mock.regs().put(0x12, 1234L)
        mock.regs().put(0x6F, (1L << 31) | (1L << 30) | (17L << 16) | (0x5L << 8) | 0x02L)
        mock.regs().put(0x71, (0x1FEL << 16) | 77L)
        mock.regs().put(0x72, (9L << 16) | 33L)
        mock.regs().put(0x01, 0x03L)
        mock.regs().put(0x05, 0x123456L)

        expect:
        f.microstepPosition() == 513
        f.readCoilCurrents() == new TMC2208Full.CoilCurrents(-256, -1)
        f.readTstep() == 1234L
        f.readCurrentScale() == 17
        f.readTemperatureFlags() == 5
        f.readPwmAuto() == new TMC2208Full.PwmAuto(77, -2, 33, 9)
        f.readOtp() == 0x123456L
        f.readIfcnt() == mock.regs().get(0x02).intValue()
        f.readRegister(0x6A) == 513L
        def st = f.readStatus()
        st.stst && st.stealth && st.ot && st.t120 && st.t150 && !st.t143
        st.csActual == 17
        def io = f.readIoin()
        io.selA
        io.version == 0x21
        f.readGlobalStatus() == new TMC2208Full.GlobalStatus(true, true, false)

        when:
        f.readGlobalStatus(true)

        then:
        mock.lastWrite(0x01) == 0x07L

        when:
        f.readRegister(0x10)

        then:
        thrown(IllegalArgumentException)

        when:
        f.writeRegister(0x13, 5L)

        then:
        mock.lastWrite(0x13) == 5L

        when:
        f.writeRegister(0x04, 1L)

        then:
        thrown(IllegalArgumentException)
    }

    def "step pulses and identity"() {
        given:
        def step = new Pin()
        def dir = new Pin()
        def f = new TMC2208Full(new UartConnectionMock(), 0.11d, null, true, step, dir, 12_000_000L, false)

        when:
        f.step(3, 0.0d)

        then:
        step.levels.count { it } == 3
        dir.levels.last()

        when:
        f.step(-2, 0.0d)

        then:
        !dir.levels.last()

        when:
        new TMC2208Full(new UartConnectionMock()).step(1, 0.0d)

        then:
        thrown(IllegalStateException)

        when:
        def m24 = new UartConnectionMock()
        m24.regs().put(0x06, 0x21000000L)
        new TMC2208Full(m24, 0.11d, null, true, null, null, 12_000_000L, true)

        then:
        noExceptionThrown()

        when:
        new TMC2208Full(new UartConnectionMock(), 0.11d, null, true, null, null, 12_000_000L, true)

        then:
        thrown(IOException)
    }
}
