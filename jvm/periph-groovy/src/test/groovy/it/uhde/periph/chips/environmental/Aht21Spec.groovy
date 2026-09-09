package it.uhde.periph.chips.environmental

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Aht21Spec extends Specification {

    // 6-byte measurement response encoding humidity=50.0%, temperature=50.0 degC:
    // rawRh = data[1]<<12 | data[2]<<4 | data[3]>>4      = 0x80000 (524288 / 2**20 * 100 = 50.0)
    // rawT  = (data[3]&0x0F)<<16 | data[4]<<8 | data[5]  = 0x80000 (524288 / 2**20 * 200 - 50 = 50.0)
    private static final byte[] MEASUREMENT = [0x00, (byte) 0x80, 0x00, 0x08, 0x00, 0x00] as byte[]

    private static boolean writesContain(MockConnection connection, byte[] expected) {
        connection.writes().any { it == expected }
    }

    def "full API"() {
        given:
        def connection = new MockConnection()
        // Status byte with both CAL (0x08) and the paired 0x10 bit set, so the
        // (status & 0x18) != 0x18 check in the constructor passes and no
        // soft-reset/recalibration path runs.
        connection.queueRead([0x18] as byte[])

        when:
        def sensor = new Aht21Full(connection)

        then:
        !writesContain(connection, Aht21Minimal.CMD_SOFT_RESET)

        when:
        connection.queueRead(MEASUREMENT.clone())
        def data = sensor.read()

        then:
        writesContain(connection, Aht21Minimal.CMD_TRIGGER)
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

        when: "read_with_crc: same 6 bytes plus a correct CRC-8 (poly 0x31, init 0xFF) trailer"
        connection.queueRead((MEASUREMENT.clone().toList() << (byte) 0x8B) as byte[])
        def withCrc = sensor.readWithCrc()

        then:
        withCrc[0] == 50.0d
        withCrc[1] == 50.0d
        withCrc[2] == 1.0d

        when:
        connection.queueRead((MEASUREMENT.clone().toList() << (byte) 0x00) as byte[])

        then:
        sensor.readWithCrc()[2] == 0.0d

        when:
        sensor.softReset()

        then:
        writesContain(connection, Aht21Minimal.CMD_SOFT_RESET)

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
}
