package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Bmp085Spec extends Specification {

    static void preloadCalibration(MockConnection connection) {
        // Datasheet worked example: AC1=408, AC2=-72, AC3=-14383, AC4=32741,
        // AC5=32757, AC6=23153, B1=6190, B2=4, MB=-32768, MC=-8711, MD=2868.
        connection.setRegister(0xAA,
            0x01, 0x98, // AC1 = 408
            0xFF, 0xB8, // AC2 = -72
            0xC7, 0xD1, // AC3 = -14383
            0x7F, 0xE5, // AC4 = 32741
            0x7F, 0xF5, // AC5 = 32757
            0x5A, 0x71, // AC6 = 23153
            0x18, 0x2E, // B1 = 6190
            0x00, 0x04, // B2 = 4
            0x80, 0x00, // MB = -32768
            0xDD, 0xF9, // MC = -8711
            0x0B, 0x34) // MD = 2868
        connection.setRegister(0xD0, 0x55) // chip ID (constructor verifies it)
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

    def "construction and readings"() {
        given:
        def connection = newConnection()
        def chip = new Bmp085Minimal(connection)

        when:
        connection.setRegister(0xF6, 0x6C, 0xFA) // UT = 27898

        then:
        chip.temperature() == 15.0
        lastWriteTo(connection, 0xF4) == 0x2E

        when:
        connection.setRegister(0xF6, 0x6C, 0xFA)

        then:
        Math.abs(chip.pressure() - 82080.0) < 1e-6
    }

    def "construction with bad chip ID throws"() {
        given:
        def connection = new MockConnection()
        preloadCalibration(connection)
        connection.setRegister(0xD0, 0x00) // wrong chip ID

        when:
        new Bmp085Minimal(connection)

        then:
        thrown(IOException)
    }

    def "construction with bad calibration throws"() {
        given:
        def connection = new MockConnection()
        connection.setRegister(0xD0, 0x55)
        connection.setRegister(0xAA, new int[22]) // every word 0x0000

        when:
        new Bmp085Minimal(connection)

        then:
        thrown(IOException)

        when:
        def connection2 = new MockConnection()
        connection2.setRegister(0xD0, 0x55)
        def ffff = new int[22]
        Arrays.fill(ffff, 0xFF)
        connection2.setRegister(0xAA, ffff) // every word raw 0xFFFF
        new Bmp085Minimal(connection2)

        then:
        thrown(IOException)
    }

    def "oversampling"() {
        given:
        def connection = newConnection()
        def full = new Bmp085Full(connection)

        expect:
        full.oversampling() == 0

        when:
        full.setOversampling(2)

        then:
        full.oversampling() == 2

        when:
        connection.setRegister(0xF6, 0x6C, 0xFA, 0x00)
        full.pressure()

        then:
        lastWriteTo(connection, 0xF4) == 0xB4 // 0x34 | (2<<6)
    }

    def "altitude and seaLevelPressure"() {
        given:
        def connection = newConnection()
        def full = new Bmp085Full(connection)

        when:
        connection.setRegister(0xF6, 0x6C, 0xFA)

        then:
        full.altitude() > 0

        when:
        connection.setRegister(0xF6, 0x6C, 0xFA)

        then:
        Math.abs(full.seaLevelPressure(0.0) - 82080.0) < 1e-6
    }

    def "chipId and reset"() {
        given:
        def connection = newConnection()
        def full = new Bmp085Full(connection)

        expect:
        full.chipId() == 0x55

        when:
        full.reset()

        then:
        lastWriteTo(connection, 0xE0) == 0xB6
    }
}
