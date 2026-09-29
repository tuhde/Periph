package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Bmp384Spec extends Specification {

    // Arbitrary but fixed calibration NVM block (21 bytes at 0x31).
    // NVM: T1=27664, T2=27728, T3=3, P1=-4079, P2=802, P3=-8, P4=5, P5=32832,
    //      P6=7696, P7=-16, P8=10, P9=4064, P10=-5, P11=2.
    static void preloadCalibration(MockConnection connection) {
        connection.setRegister(0x31,
            0x10, 0x6C, // T1 u16 LE
            0x50, 0x6C, // T2 u16 LE
            0x03,       // T3 s8
            0x11, 0xF0, // P1 s16 LE
            0x22, 0x03, // P2 s16 LE
            0xF8,       // P3 s8
            0x05,       // P4 s8
            0x40, 0x80, // P5 u16 LE
            0x10, 0x1E, // P6 u16 LE
            0xF0,       // P7 s8
            0x0A,       // P8 s8
            0xE0, 0x0F, // P9 s16 LE
            0xFB,       // P10 s8
            0x02)       // P11 s8
        connection.setRegister(0x00, 0x50) // CHIP_ID
    }

    static MockConnection newConnection() {
        def c = new MockConnection()
        preloadCalibration(c)
        return c
    }

    static int lastWriteTo(MockConnection connection, int reg) {
        def writes = connection.writes()
        for (int i = writes.size() - 1; i >= 0; i--) {
            def w = writes[i]
            if (w.length == 2 && (w[0] & 0xFF) == reg) return w[1] & 0xFF
        }
        return -1
    }

    // uncomp_press=6000000, uncomp_temp=8000000 -> t_lin=23.715563300065696 degC,
    // pressure=1447.6955007429672 hPa (computed independently from the same
    // Bosch compensation formula; cross-checked across all language ports).
    static final int[] PRESS_BYTES = [0x80, 0x8D, 0x5B]
    static final int[] TEMP_BYTES = [0x00, 0x12, 0x7A]
    static final double EXPECTED_T_LIN = 23.715563300065696
    static final double EXPECTED_PRESSURE_HPA = 1447.6955007429672

    static void setBurst(MockConnection connection) {
        connection.setRegister(0x04,
            PRESS_BYTES[0], PRESS_BYTES[1], PRESS_BYTES[2],
            TEMP_BYTES[0], TEMP_BYTES[1], TEMP_BYTES[2])
    }

    def "construction writes default config"() {
        given:
        def connection = newConnection()

        when:
        new Bmp384Minimal(connection)

        then:
        lastWriteTo(connection, 0x1C) == ((1 << 3) | 4)
        lastWriteTo(connection, 0x1F) == (2 << 1)
        lastWriteTo(connection, 0x1D) == 0x03
        lastWriteTo(connection, 0x1B) == ((0x03 << 4) | 0x02 | 0x01)
    }

    def "construction with bad chip ID throws"() {
        given:
        def connection = new MockConnection()
        preloadCalibration(connection)
        connection.setRegister(0x00, 0x00)

        when:
        new Bmp384Minimal(connection)

        then:
        thrown(IOException)
    }

    def "temperature and pressure"() {
        given:
        def connection = newConnection()
        def chip = new Bmp384Minimal(connection)

        when:
        setBurst(connection)

        then:
        Math.abs(chip.temperature() - EXPECTED_T_LIN) < 1e-6

        when:
        setBurst(connection)

        then:
        Math.abs(chip.pressure() - EXPECTED_PRESSURE_HPA) < 1e-6
    }

    def "read combined burst"() {
        given:
        def connection = newConnection()
        def full = new Bmp384Full(connection)
        setBurst(connection)

        when:
        double[] result = full.read()

        then:
        Math.abs(result[0] - EXPECTED_PRESSURE_HPA) < 1e-6
        Math.abs(result[1] - EXPECTED_T_LIN) < 1e-6
    }

    // Regression: read() must trigger a forced measurement exactly like
    // temperature()/pressure()/readForced() do -- it was previously missing
    // this entirely in forced mode.
    def "read in forced mode triggers PWR_CTRL"() {
        given:
        def connection = newConnection()
        def full = new Bmp384Full(connection)
        full.setMode(Bmp384Full.MODE_FORCED)
        setBurst(connection)

        when:
        full.read()

        then:
        lastWriteTo(connection, 0x1B) == ((Bmp384Full.MODE_FORCED << 4) | 0x02 | 0x01)
    }

    def "readForced restores previous mode"() {
        given:
        def connection = newConnection()
        def full = new Bmp384Full(connection)
        setBurst(connection)

        when:
        double[] result = full.readForced()

        then:
        Math.abs(result[0] - EXPECTED_PRESSURE_HPA) < 1e-6
        lastWriteTo(connection, 0x1B) == ((Bmp384Full.MODE_NORMAL << 4) | 0x02 | 0x01)
    }

    def "setMode writes PWR_CTRL"() {
        given:
        def connection = newConnection()
        def full = new Bmp384Full(connection)

        when:
        full.setMode(Bmp384Full.MODE_SLEEP)

        then:
        lastWriteTo(connection, 0x1B) == ((Bmp384Full.MODE_SLEEP << 4) | 0x02 | 0x01)
    }

    def "isDataReady"() {
        given:
        def connection = newConnection()
        def full = new Bmp384Full(connection)

        when:
        connection.setRegister(0x03, 1 << 5)

        then:
        full.isDataReady()

        when:
        connection.setRegister(0x03, 0x00)

        then:
        !full.isDataReady()
    }

    def "softreset writes CMD and reapplies config"() {
        given:
        def connection = newConnection()
        def full = new Bmp384Full(connection)

        when:
        full.softreset()

        then:
        lastWriteTo(connection, 0x7E) == 0xB6
        lastWriteTo(connection, 0x1B) == ((Bmp384Full.MODE_NORMAL << 4) | 0x02 | 0x01)
    }

    def "fifoConfigure writes FIFO registers"() {
        given:
        def connection = newConnection()
        def full = new Bmp384Full(connection)

        when:
        full.fifoConfigure(true, true, 300, true)

        then:
        lastWriteTo(connection, 0x17) == ((1 << 4) | (1 << 3) | (1 << 1) | 1)
        lastWriteTo(connection, 0x15) == (300 & 0xFF)
        lastWriteTo(connection, 0x16) == ((300 >> 8) & 0x01)
    }

    def "fifoRead parses all frame types"() {
        given:
        def connection = newConnection()
        def full = new Bmp384Full(connection)
        int[] fifoBytes = [
            0x84, PRESS_BYTES[0], PRESS_BYTES[1], PRESS_BYTES[2], // pressure
            0x90, TEMP_BYTES[0], TEMP_BYTES[1], TEMP_BYTES[2],    // temperature
            0xA0, 0x01, 0x02, 0x03,                               // sensortime
            0x44,                                                  // error
            0x80,                                                  // empty
            0xFF,                                                  // unknown
        ]
        connection.setRegister(0x12, fifoBytes.length & 0xFF, (fifoBytes.length >> 8) & 0x01)
        connection.setRegister(0x14, fifoBytes)
        full.tLin = EXPECTED_T_LIN // so a lone pressure frame is comparable to the fixture

        when:
        def frames = full.fifoRead()

        then:
        frames.size() == 6
        frames[0].type == 'pressure'
        Math.abs(frames[0].value - EXPECTED_PRESSURE_HPA) < 1e-6
        frames[1].type == 'temperature'
        Math.abs(frames[1].value - EXPECTED_T_LIN) < 1e-6
        frames[2].type == 'sensortime'
        frames[2].value == (double) 0x030201
        frames[3].type == 'error'
        frames[4].type == 'empty'
        frames[5].type == 'unknown'
    }

    def "fifoRead on empty FIFO returns no frames"() {
        given:
        def connection = newConnection()
        def full = new Bmp384Full(connection)
        connection.setRegister(0x12, 0x00, 0x00)

        expect:
        full.fifoRead().length == 0
    }

    def "fifoFlush writes CMD"() {
        given:
        def connection = newConnection()
        def full = new Bmp384Full(connection)

        when:
        full.fifoFlush()

        then:
        lastWriteTo(connection, 0x7E) == 0xB0
    }

    def "altitude is negative for high fixture pressure"() {
        given:
        def connection = newConnection()
        def full = new Bmp384Full(connection)
        setBurst(connection)

        expect:
        // Fixture pressure (1447 hPa) is above the standard sea-level reference.
        full.altitude() < 0
    }
}
