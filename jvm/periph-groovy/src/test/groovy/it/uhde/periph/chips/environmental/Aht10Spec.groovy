package it.uhde.periph.chips.environmental

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Aht10Spec extends Specification {

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
        // CAL bit set after the init command: no soft-reset retry.
        connection.queueRead([0x08] as byte[])

        when:
        def sensor = new Aht10Full(connection)

        then:
        writesContain(connection, Aht10Minimal.CMD_INIT)
        !writesContain(connection, Aht10Minimal.CMD_SOFT_RESET)

        when:
        connection.queueRead(MEASUREMENT.clone())
        def data = sensor.read()

        then:
        writesContain(connection, Aht10Minimal.CMD_TRIGGER)
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
        sensor.softReset()

        then:
        writesContain(connection, Aht10Minimal.CMD_SOFT_RESET)

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

    def "uncalibrated after retry throws"() {
        given:
        def connection = new MockConnection()
        connection.queueRead([0x00] as byte[])
        connection.queueRead([0x00] as byte[])

        when:
        new Aht10Full(connection)

        then:
        thrown(IOException)
        writesContain(connection, Aht10Minimal.CMD_SOFT_RESET)
    }
}
