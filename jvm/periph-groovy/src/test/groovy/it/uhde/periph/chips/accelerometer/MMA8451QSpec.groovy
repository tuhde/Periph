package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class MMA8451QSpec extends Specification {

    def newConnection() {
        def c = new MockConnection()
        c.setRegister(MMA8451QMinimal.REG_WHO_AM_I, 0x1A)
        c.setRegister(MMA8451QMinimal.REG_CTRL_REG2, 0x00)
        c.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x00)
        c.setRegister(MMA8451QMinimal.REG_XYZ_DATA_CFG, 0x00)
        c.setRegister(MMA8451QMinimal.REG_HP_FILTER, 0x00)
        c.setRegister(MMA8451QMinimal.REG_CTRL_REG3, 0x00)
        c.setRegister(MMA8451QMinimal.REG_CTRL_REG4, 0x00)
        c.setRegister(MMA8451QMinimal.REG_CTRL_REG5, 0x00)
        // 14-bit data: X = 0x4000 >> 2 = 4096 counts = 1 g; Y = -0x1000 >> 2
        // = -1024 counts = -0.25 g; Z = 0.
        c.setRegister(MMA8451QMinimal.REG_OUT_X_MSB, 0x40, 0x00)
        c.setRegister(MMA8451QMinimal.REG_OUT_Y_MSB, 0xF0, 0x00)
        c.setRegister(MMA8451QMinimal.REG_OUT_Z_MSB, 0x00, 0x00)
        return c
    }

    def lastWriteTo(MockConnection connection, int reg) {
        def writes = connection.writes()
        for (int i = writes.size() - 1; i >= 0; i--) {
            def w = writes.get(i)
            if (w.length == 2 && (w[0] & 0xFF) == reg) return w[1] & 0xFF
        }
        return -1
    }

    def writeCountTo(MockConnection connection, int reg) {
        def writes = connection.writes()
        int n = 0
        for (int i = 0; i < writes.size(); i++) {
            def w = writes.get(i)
            if (w.length == 2 && (w[0] & 0xFF) == reg) n++
        }
        return n
    }

    def "init sequence writes"() {
        given:
        def connection = newConnection()

        when:
        new MMA8451QMinimal(connection)

        then:
        // The software reset (CTRL_REG2 = 0x40) must have been issued.
        def resetWritten = connection.writes().any { w ->
            w.length == 2 && (w[0] & 0xFF) == MMA8451QMinimal.REG_CTRL_REG2 && (w[1] & 0xFF) == 0x40
        }
        resetWritten
        lastWriteTo(connection, MMA8451QMinimal.REG_XYZ_DATA_CFG) == 0x00
        lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG2) == 0x00
        lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG1) == 0x19
        // WHO_AM_I must have been read.
        connection.writes().any { w -> w.length == 1 && (w[0] & 0xFF) == MMA8451QMinimal.REG_WHO_AM_I }
    }

    def "a bad WHO_AM_I throws"() {
        given:
        def connection = newConnection()
        connection.setRegister(MMA8451QMinimal.REG_WHO_AM_I, 0x00)

        when:
        new MMA8451QMinimal(connection)

        then:
        thrown(IOException)
    }

    def "read converts the 14-bit counts"() {
        given:
        def connection = newConnection()
        def accel = new MMA8451QMinimal(connection)

        when:
        def xyz = accel.read()

        then:
        xyz[0] == 1.0d
        xyz[1] == -0.25d
        xyz[2] == 0.0d
    }

    def "read raw returns the signed counts"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)

        when:
        def raw = full.readRaw()

        then:
        raw[0] == 4096
        raw[1] == -1024
        raw[2] == 0
    }

    def "set range updates the sensitivity"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19)

        when:
        full.setRange(4)

        then:
        // XYZ_DATA_CFG bits 1:0 = 0b01 for ±4 g.
        (lastWriteTo(connection, MMA8451QMinimal.REG_XYZ_DATA_CFG) & 0x03) == 0x01
        // 4096 counts at ±4 g = 2048 counts/g = 2 g.
        full.read()[0] == 2.0d

        when:
        connection.setRegister(MMA8451QMinimal.REG_XYZ_DATA_CFG, 0x01)
        full.setRange(8)

        then:
        (lastWriteTo(connection, MMA8451QMinimal.REG_XYZ_DATA_CFG) & 0x03) == 0x02
    }

    def "set range rejects an invalid value"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)

        when:
        full.setRange(3)

        then:
        thrown(IllegalArgumentException)
    }

    def "set data rate writes the DR bits"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19)

        when:
        full.setDataRate(50)

        then:
        // CTRL_REG1 bits 5:3 = 0b100 for 50 Hz.
        ((lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG1) & 0x38) >> 3) == 0x04

        when:
        full.setDataRate(123)

        then:
        thrown(IllegalArgumentException)
    }

    def "set oversampling writes MODS and SMODS"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG2, 0x00)

        when:
        full.setOversampling(2)

        then:
        // MODS bits 1:0 = 0b10, SMODS bits 4:3 = 0b10 << 3 = 0x10.
        (lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG2) & 0x1B) == 0x12

        when:
        full.setOversampling(3, 1)

        then:
        // SMODS = 0b01 << 3 = 0x08, MODS = 0b11.
        (lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG2) & 0x1B) == 0x0B

        when:
        full.setOversampling(4)

        then:
        thrown(IllegalArgumentException)
    }

    def "low noise mode limits the range to ±4 g"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19)

        when:
        full.setLowNoise(true)

        then:
        (lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG1) & 0x04) == 0x04

        when:
        full.setRange(8)

        then:
        thrown(IllegalArgumentException)
    }

    def "set resolution switches to the 8-bit fast read"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19)

        when:
        full.setResolution(8)

        then:
        // CTRL_REG1 F_READ = 0x02.
        (lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG1) & 0x02) == 0x02
        // 8-bit mode reads only the MSB registers: X = 0x40 = 64 counts.
        def raw = full.readRaw()
        raw[0] == 64
        raw[1] == -16

        when:
        full.setResolution(12)

        then:
        thrown(IllegalArgumentException)
    }

    def "the resolution cannot change while the FIFO is enabled"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)

        when:
        full.setFifo(1, 8)
        full.setResolution(8)

        then:
        thrown(IllegalArgumentException)
    }

    def "standby and activate toggle the ACTIVE bit"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19)

        when:
        full.standby()

        then:
        (lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG1) & 0x01) == 0

        when:
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x18)
        full.activate()

        then:
        (lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG1) & 0x01) == 0x01
    }

    def "reset writes the RST bit"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)

        when:
        full.reset()

        then:
        (lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG2) & 0x40) == 0x40
    }

    def "offsets use the 2 mg LSB"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19)

        when:
        full.setOffset(0.010d, -0.020d, 0.0d)

        then:
        lastWriteTo(connection, MMA8451QMinimal.REG_OFF_X) == 5
        lastWriteTo(connection, MMA8451QMinimal.REG_OFF_Y) == 0xF6
        lastWriteTo(connection, MMA8451QMinimal.REG_OFF_Z) == 0

        when:
        connection.setRegister(MMA8451QMinimal.REG_OFF_X, 5)
        connection.setRegister(MMA8451QMinimal.REG_OFF_Y, 0xF6)
        connection.setRegister(MMA8451QMinimal.REG_OFF_Z, 0)
        def off = full.getOffset()

        then:
        off[0] == 0.010d
        off[1] == -0.020d
        off[2] == 0.0d
    }

    def "status and system mode are decoded"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        connection.setRegister(MMA8451QMinimal.REG_STATUS, 0x08)

        expect:
        full.newDataAvailable()
        full.readStatus() == 0x08

        when:
        connection.setRegister(MMA8451QMinimal.REG_STATUS, 0x00)

        then:
        !full.newDataAvailable()

        when:
        connection.setRegister(MMA8451QMinimal.REG_SYSMOD, 0x01)

        then:
        full.readSystemMode() == 1

        when:
        connection.setRegister(MMA8451QMinimal.REG_SYSMOD, 0x02)

        then:
        full.readSystemMode() == 2
    }

    def "FIFO configuration and status"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19)

        when:
        full.setFifo(1, 16)

        then:
        // F_MODE = 0b01 in bits 7:6, watermark 16 in bits 5:0.
        lastWriteTo(connection, MMA8451QMinimal.REG_F_SETUP) == 0x50

        when:
        connection.setRegister(MMA8451QMinimal.REG_STATUS, 0xC2)
        def st = full.readFifoStatus()

        then:
        st.isOverflow()
        st.isWatermark()
        st.getCount() == 2
        full.fifoLevel() == 2
    }

    def "reading the FIFO drains the samples"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        connection.setRegister(MMA8451QMinimal.REG_STATUS, 0x02)

        when:
        def samples = full.readFifo()

        then:
        samples.length == 2
        samples[0][0] == 1.0d
        samples[0][1] == -0.25d
    }

    def "FIFO trigger sources land in TRIG_CFG"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19)

        when:
        full.setFifoTrigger(true, false, true, false)

        then:
        // TRIG_CFG bits 5..2: transient = 0x20, pulse = 0x08.
        lastWriteTo(connection, MMA8451QMinimal.REG_TRIG_CFG) == 0x28
    }

    def "the free-fall threshold uses the 0.063 g LSB"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19)

        when:
        full.setFreefallMotion(false, 7, 0.2d, 60, true, true)

        then:
        // FF_MT_CFG: ELE = 0x80, axes 0x38, freefall (OAE = 0).
        lastWriteTo(connection, MMA8451QMinimal.REG_FF_MT_CFG) == 0xB8
        // FF_MT_THS: DBCNTM = 0x80, THS = round(0.2 / 0.063) = 3.
        lastWriteTo(connection, MMA8451QMinimal.REG_FF_MT_THS) == 0x83
        // FF_MT_COUNT: 60 ms / 10 ms step at 100 Hz = 6.
        lastWriteTo(connection, MMA8451QMinimal.REG_FF_MT_COUNT) == 6
    }

    def "transient configuration"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19)

        when:
        full.setTransient(7, 0.5d, 0, false, true)

        then:
        // TRANSIENT_CFG: ELE = 0x10, axes 0x0E.
        lastWriteTo(connection, MMA8451QMinimal.REG_TRANSIENT_CFG) == 0x1E
        // THS = round(0.5 / 0.063) = 8.
        lastWriteTo(connection, MMA8451QMinimal.REG_TRANSIENT_THS) == 8
    }

    def "tap configuration"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19)
        connection.setRegister(MMA8451QMinimal.REG_HP_FILTER, 0x00)

        when:
        full.setPulse(0, 0x04, 0.5d, 0.5d, 1.0d, 40, 100, 300, true, false, true, true)

        then:
        // PULSE_CFG: DPA = 0, ELE = 0x40, ZDPEFE = 0x20.
        lastWriteTo(connection, MMA8451QMinimal.REG_PULSE_CFG) == 0x60
        // PULSE_THSX = round(0.5 / 0.063) = 8; PULSE_THSZ = round(1.0 / 0.063) = 16.
        lastWriteTo(connection, MMA8451QMinimal.REG_PULSE_THSX) == 8
        lastWriteTo(connection, MMA8451QMinimal.REG_PULSE_THSZ) == 16
        // PULSE_TMLT: 40 ms / 10 ms step (LPF on, 100 Hz) = 4.
        lastWriteTo(connection, MMA8451QMinimal.REG_PULSE_TMLT) == 4
        // PULSE_LTCY: 100 ms / 20 ms step = 5.
        lastWriteTo(connection, MMA8451QMinimal.REG_PULSE_LTCY) == 5
        // PULSE_WIND: 300 ms / 20 ms step = 15.
        lastWriteTo(connection, MMA8451QMinimal.REG_PULSE_WIND) == 15
        // HP_FILTER_CUTOFF: Pulse_HPF_BYP = 0, Pulse_LPF_EN = 1.
        lastWriteTo(connection, MMA8451QMinimal.REG_HP_FILTER) == 0x10
    }

    def "orientation configuration and the trip-angle table"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19)

        when:
        full.setOrientation(true, 100, 45, 4, 4, 1)

        then:
        // PL_CFG: DBCNTM = 0x80, PL_EN = 0x40.
        lastWriteTo(connection, MMA8451QMinimal.REG_PL_CFG) == 0xC0
        // PL_COUNT: 100 ms / 10 ms step = 10.
        lastWriteTo(connection, MMA8451QMinimal.REG_PL_COUNT) == 10
        // P_L_THS_REG: 45 deg -> 0x10 << 3 = 0x80, HYS = 4.
        lastWriteTo(connection, MMA8451QMinimal.REG_P_L_THS) == 0x84
        // PL_BF_ZCOMP: BKFR = 1 -> 0x40, ZLOCK = 4 -> 0x04.
        lastWriteTo(connection, MMA8451QMinimal.REG_PL_BF_ZCOMP) == 0x44

        when:
        // An unsupported trip angle is rejected.
        full.setOrientation(true, 0, 47, 4, 4, 1)

        then:
        thrown(IllegalArgumentException)

        when:
        // THS + HYS outside 1..31 is rejected (75 deg -> 0x19, HYS = 7 -> 0x1C).
        full.setOrientation(true, 0, 75, 7, 4, 1)

        then:
        thrown(IllegalArgumentException)
    }

    def "read orientation decodes PL_STATUS"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        // NEWLP | LO | LAPO = 0b10 | BAFRO
        connection.setRegister(MMA8451QMinimal.REG_PL_STATUS, 0xC5)

        when:
        def o = full.readOrientation()

        then:
        o.isChanged()
        o.isLockout()
        o.getPortraitLandscape() == 2
        o.isBack()
    }

    def "auto-sleep configuration"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG2, 0x00)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG3, 0x00)

        when:
        full.setAutoSleep(true, 12.5d, 10, MMA8451QFull.SOURCE_PULSE | MMA8451QFull.SOURCE_FF_MT)

        then:
        // CTRL_REG1 bits 7:6 = 0b01 for 12.5 Hz.
        ((lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG1) & 0xC0) >> 6) == 0x01
        // CTRL_REG2 SLPE = 0x04.
        (lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG2) & 0x04) == 0x04
        // CTRL_REG3 WAKE_PULSE = 0x10, WAKE_FF_MT = 0x08.
        (lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG3) & 0x78) == 0x18
        // ASLP_COUNT: 10 s / 0.32 s = 31.
        lastWriteTo(connection, MMA8451QMinimal.REG_ASLP_COUNT) == 31

        when:
        full.setAutoSleep(true, 99)

        then:
        thrown(IllegalArgumentException)
    }

    def "FIFO gate and interrupt pin configuration"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG3, 0x00)

        when:
        full.setFifoGate(true)

        then:
        (lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG3) & 0x80) == 0x80

        when:
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG3, 0x00)
        full.setInterruptPins(true, true)

        then:
        // IPOL = 0x02, PP_OD = 0x01.
        (lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG3) & 0x03) == 0x03

        when:
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG3, 0x03)
        full.setInterruptPins(false, false)

        then:
        (lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG3) & 0x03) == 0
    }

    def "enable and disable an interrupt source"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG4, 0x00)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG5, 0x00)

        when:
        full.enableInterrupt(MMA8451QFull.SOURCE_ORIENTATION, 1)

        then:
        // CTRL_REG4 INT_EN_LNDPRT = 0x10.
        (lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG4) & 0x10) == 0x10
        // CTRL_REG5 INT_CFG_LNDPRT = 0x10 routes to INT1.
        (lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG5) & 0x10) == 0x10

        when:
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG4, 0x10)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG5, 0x10)
        full.enableInterrupt(MMA8451QFull.SOURCE_PULSE, 2)

        then:
        // CTRL_REG4 now has LNDPRT | PULSE.
        (lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG4) & 0x18) == 0x18
        // CTRL_REG5: PULSE routed to INT2 -> bit cleared.
        (lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG5) & 0x18) == 0x10

        when:
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG4, 0x18)
        full.disableInterrupt(MMA8451QFull.SOURCE_PULSE)

        then:
        (lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG4) & 0x18) == 0x10

        when:
        full.enableInterrupt(0x02, 1)

        then:
        thrown(IllegalArgumentException)

        when:
        full.enableInterrupt(0x10, 3)

        then:
        thrown(IllegalArgumentException)
    }

    def "poll interrupt clears the per-source flags"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        // FIFO | PULSE | DRDY set.
        connection.setRegister(MMA8451QMinimal.REG_INT_SOURCE,
                MMA8451QFull.SOURCE_FIFO | MMA8451QFull.SOURCE_PULSE | MMA8451QFull.SOURCE_DATA_READY)

        when:
        def status = full.pollInterrupt()

        then:
        status == (MMA8451QFull.SOURCE_FIFO | MMA8451QFull.SOURCE_PULSE | MMA8451QFull.SOURCE_DATA_READY)
        // F_STATUS (0x00) and PULSE_SRC (0x22) must have been read.
        connection.writes().any { w -> w.length == 1 && (w[0] & 0xFF) == MMA8451QMinimal.REG_STATUS }
        connection.writes().any { w -> w.length == 1 && (w[0] & 0xFF) == MMA8451QMinimal.REG_PULSE_SRC }
    }

    def "poll interrupt leaves the data-ready flag set"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        connection.setRegister(MMA8451QMinimal.REG_INT_SOURCE, MMA8451QFull.SOURCE_DATA_READY)

        when:
        def status = full.pollInterrupt()

        then:
        status == MMA8451QFull.SOURCE_DATA_READY
        // The XYZ data registers must not have been touched.
        !connection.writes().any { w -> w.length == 1 && (w[0] & 0xFF) == MMA8451QMinimal.REG_OUT_X_MSB }
    }

    def "on and off interrupt are accepted"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        def seen = [0]

        when:
        full.onInterrupt({ s -> seen[0] = s })
        full.offInterrupt()

        then:
        seen[0] == 0
    }

    def "the self-test restores the configuration"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19)

        when:
        def r = full.selfTest()

        then:
        // The mock returns the same sample for every read, so the deltas are 0
        // and the heuristic reports "not ok".
        !r.isOk()
        r.getDx() == 0
        r.getDy() == 0
        r.getDz() == 0
        // The self-test forces ST on CTRL_REG2 at least once.
        connection.writes().any { w ->
            w.length == 2 && (w[0] & 0xFF) == MMA8451QMinimal.REG_CTRL_REG2 && (w[1] & 0x80) != 0
        }
        // The configuration is restored at the end (±2 g -> FS bits 0b00).
        (lastWriteTo(connection, MMA8451QMinimal.REG_XYZ_DATA_CFG) & 0x03) == 0x00
    }

    def "the high-pass output is configured"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19)
        connection.setRegister(MMA8451QMinimal.REG_HP_FILTER, 0x00)

        when:
        full.setHighPassOutput(true, 2)

        then:
        // XYZ_DATA_CFG HPF_OUT = 0x10.
        (lastWriteTo(connection, MMA8451QMinimal.REG_XYZ_DATA_CFG) & 0x10) == 0x10
        // HP_FILTER_CUTOFF SEL = 2.
        (lastWriteTo(connection, MMA8451QMinimal.REG_HP_FILTER) & 0x03) == 0x02

        when:
        full.setHighPassOutput(true, 4)

        then:
        thrown(IllegalArgumentException)
    }

    def "a STANDBY-only write clears and restores ACTIVE"() {
        given:
        def connection = newConnection()
        def full = new MMA8451QFull(connection)
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19)
        def before = writeCountTo(connection, MMA8451QMinimal.REG_CTRL_REG1)

        when:
        full.setRange(4)

        then:
        // STANDBY (clear ACTIVE) + restore ACTIVE = 2 extra writes.
        writeCountTo(connection, MMA8451QMinimal.REG_CTRL_REG1) == before + 2
        // The last CTRL_REG1 write restores the original value.
        lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG1) == 0x19
    }
}
