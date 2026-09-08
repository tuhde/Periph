package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Bmp180Spec extends Specification {

    private static void preloadCalibration(MockConnection connection) {
        // Datasheet worked example (Figure 4, page 15): AC1=408, AC2=-72,
        // AC3=-14383, AC4=32741, AC5=32757, AC6=23153, B1=6190, B2=4,
        // MB=-32768, MC=-8711, MD=2868.
        connection.setRegister(Bmp180Minimal.REG_CAL_START,
                0x01, 0x98,  // AC1 = 408
                0xFF, 0xB8,  // AC2 = -72
                0xC7, 0xD1,  // AC3 = -14383
                0x7F, 0xE5,  // AC4 = 32741
                0x7F, 0xF5,  // AC5 = 32757
                0x5A, 0x71,  // AC6 = 23153
                0x18, 0x2E,  // B1 = 6190
                0x00, 0x04,  // B2 = 4
                0x80, 0x00,  // MB = -32768
                0xDD, 0xF9,  // MC = -8711
                0x0B, 0x34)  // MD = 2868
    }

    def "full API"() {
        given:
        def connection = new MockConnection()
        preloadCalibration(connection)
        // Unlike Python/C++/JS/Rust, the Groovy constructor verifies the
        // chip ID register before reading calibration.
        connection.setRegister(Bmp180Minimal.REG_ID, 0x55)

        when:
        def sensor = new Bmp180Full(connection)

        then:
        noExceptionThrown()

        when: "pressure() re-reads OUT_MSB for both UT (2 bytes) and UP (3 bytes) from\n" +
                "the same register within one call, and this mock always returns the\n" +
                "register map's current contents - it cannot hand back a different UT\n" +
                "then a different UP within a single call. So UT and the top 16 bits of\n" +
                "UP are necessarily the same value here (0x6CFA = 27898); the expected\n" +
                "T/p below are computed from the real compensation formula with\n" +
                "UT=UP=27898, not the datasheet's mismatched worked example."
        connection.setRegister(Bmp180Minimal.REG_OUT_MSB, 0x6C, 0xFA)

        then:
        sensor.temperature() == 15.0d

        when:
        connection.setRegister(Bmp180Minimal.REG_OUT_MSB, 0x6C, 0xFA)

        then:
        Math.abs(sensor.pressure() - 820.8d) < 1e-6

        when: "chipId(): expect 0x55"
        connection.setRegister(Bmp180Minimal.REG_ID, 0x55)

        then:
        sensor.chipId() == 0x55

        when: "oversampling()/setOversampling()"

        then:
        sensor.oversampling() == 0

        when:
        sensor.setOversampling(Bmp180Full.OSS_STANDARD)

        then:
        sensor.oversampling() == 1

        when:
        sensor.setOversampling(0)
        connection.setRegister(Bmp180Minimal.REG_OUT_MSB, 0x6C, 0xFA)

        then: "altitude(): pressure() re-reads UT/UP internally"
        Math.abs(sensor.altitude() - 1741.7604174d) < 0.5d

        when:
        connection.setRegister(Bmp180Minimal.REG_OUT_MSB, 0x6C, 0xFA)

        then: "seaLevelPressure(altitudeM=100)"
        Math.abs(sensor.seaLevelPressure(100.0d) - 830.599010429d) < 0.5d

        when: "reset(): writes soft-reset command, then re-reads calibration coefficients"
        preloadCalibration(connection)
        sensor.reset()
        def sawSoftReset = connection.writes().any {
            it.length == 2 && (it[0] & 0xFF) == Bmp180Minimal.REG_SOFT_RST && (it[1] & 0xFF) == 0xB6
        }
        def calReads = connection.writes().findAll {
            it.length == 1 && (it[0] & 0xFF) == Bmp180Minimal.REG_CAL_START
        }.size()

        then:
        sawSoftReset
        calReads >= 2

        when: "invalid calibration data (a coefficient of 0x0000) throws at construction"
        def badConnection = new MockConnection()
        badConnection.setRegister(Bmp180Minimal.REG_ID, 0x55)
        badConnection.setRegister(Bmp180Minimal.REG_CAL_START,
                0x00, 0x00, // AC1 = 0 (invalid)
                0xFF, 0xB8, 0xC7, 0xD1, 0x7F, 0xE5, 0x7F, 0xF5, 0x5A, 0x71,
                0x18, 0x2E, 0x00, 0x04, 0x80, 0x00, 0xDD, 0xF9, 0x0B, 0x34)
        new Bmp180Full(badConnection)

        then:
        thrown(IOException)
    }
}
