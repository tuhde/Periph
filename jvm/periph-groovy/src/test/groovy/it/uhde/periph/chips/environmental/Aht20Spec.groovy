package it.uhde.periph.chips.environmental

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Aht20Spec extends Specification {

    // 6-byte measurement response encoding humidity=50.0%, temperature=50.0 degC:
    // rawRh = data[1]<<12 | data[2]<<4 | data[3]>>4      = 0x80000 (524288 / 2**20 * 100 = 50.0)
    // rawT  = (data[3]&0x0F)<<16 | data[4]<<8 | data[5]  = 0x80000 (524288 / 2**20 * 200 - 50 = 50.0)
    private static final byte[] MEASUREMENT = [0x08, (byte) 0x80, 0x00, 0x08, 0x00, 0x00] as byte[]

    private static boolean writesContain(MockConnection connection, byte[] expected) {
        connection.writes().any { it == expected }
    }

    def "full API"() {
        given:
        def connection = new MockConnection()
        // Status 0x18 (idle + calibrated): no reset or calibration init.
        connection.queueRead([0x18] as byte[])

        when:
        def sensor = new Aht20Full(connection)

        then:
        writesContain(connection, Aht20Minimal.CMD_STATUS)
        !writesContain(connection, Aht20Minimal.CMD_SOFT_RESET)

        when:
        connection.queueRead(MEASUREMENT.clone())
        def data = sensor.read()

        then:
        writesContain(connection, Aht20Minimal.CMD_TRIGGER)
        data[0] == 50.0d
        data[1] == 50.0d

        when:
        connection.queueRead(MEASUREMENT.clone())

        then:
        sensor.readTemperature() == 50.0d

        when:
        connection.queueRead(MEASUREMENT.clone())

        then:
        sensor.readHumidity() == 50.0d

        when:
        connection.queueRead(MEASUREMENT.clone())
        def raw = sensor.readRaw()

        then:
        raw[0] == 0x80000
        raw[1] == 0x80000

        when: "BUSY is set on the first read"
        connection.queueRead([(byte) 0x88, (byte) 0x80, 0x00, 0x08, 0x00, 0x00] as byte[])
        connection.queueRead(MEASUREMENT.clone())

        then:
        sensor.read()[0] == 50.0d

        when: "spec example raw_t = 0x6B300"
        connection.queueRead([0x08, (byte) 0x80, 0x00, 0x06, (byte) 0xB3, 0x00] as byte[])

        then:
        Math.abs(sensor.readTemperature() - 33.74d) < 0.01d

        when:
        connection.queueRead(MEASUREMENT.clone())

        then:
        Math.abs(sensor.dewPoint() - 36.7d) < 0.1d

        when:
        connection.queueRead([0x08] as byte[])

        then:
        sensor.readStatusByte() == 0x08

        when:
        // CRC-8 (poly 0x31, init 0xFF).
        byte[] frame = [0x1C, (byte) 0x80, 0x00, 0x08, 0x00, 0x00] as byte[]
        byte crc = (byte) Aht20Full.crc8(frame, 6)
        byte[] good = (frame as List<Byte> + [crc]) as byte[]
        byte[] bad = (frame as List<Byte> + [(byte) (crc ^ 0xFF)]) as byte[]
        connection.queueRead(good)
        def withCrc = sensor.readWithCrc()
        connection.queueRead(bad)
        def badCrc = sensor.readWithCrc()

        then:
        withCrc[2] == 1.0d
        withCrc[0] == 50.0d
        withCrc[1] == 50.0d
        badCrc[2] == 0.0d
        Aht20Full.crc8([(byte) 0xBE, (byte) 0xEF] as byte[], 2) == 0x92

        when:
        // Soft reset re-runs the calibration check (status 0x18: no calibration init).
        connection.queueRead([0x18] as byte[])
        sensor.softReset()

        then:
        writesContain(connection, Aht20Minimal.CMD_SOFT_RESET)

        when:
        connection.queueRead([0x08] as byte[])

        then:
        sensor.isCalibrated()

        when:
        connection.queueRead([0x00] as byte[])

        then:
        !sensor.isCalibrated()

        when:
        connection.queueRead([(byte) 0x80] as byte[])

        then:
        sensor.isBusy()

        when:
        connection.queueRead([0x00] as byte[])

        then:
        !sensor.isBusy()
    }

    def "soft reset fixes calibration"() {
        given:
        def connection = new MockConnection()
        connection.queueRead([0x00] as byte[])
        connection.queueRead([0x18] as byte[])

        when:
        new Aht20Full(connection)

        then:
        writesContain(connection, Aht20Minimal.CMD_SOFT_RESET)
        !writesContain(connection, Aht20Minimal.CMD_CAL_INIT[0])
    }

    def "calibration registers are written when still uncalibrated"() {
        given:
        def connection = new MockConnection()
        connection.queueRead([0x00] as byte[])
        connection.queueRead([0x00] as byte[])
        connection.queueRead([0x18] as byte[])

        when:
        new Aht20Full(connection)

        then:
        Aht20Minimal.CMD_CAL_INIT.every { writesContain(connection, it) }
    }

    def "uncalibrated after retry throws"() {
        given:
        def connection = new MockConnection()
        connection.queueRead([0x00] as byte[])
        connection.queueRead([0x00] as byte[])
        connection.queueRead([0x00] as byte[])

        when:
        new Aht20Full(connection)

        then:
        thrown(IOException)
        writesContain(connection, Aht20Minimal.CMD_SOFT_RESET)
    }
}
