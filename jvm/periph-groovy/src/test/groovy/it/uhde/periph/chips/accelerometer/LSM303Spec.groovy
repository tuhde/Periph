package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class LSM303Spec extends Specification {

    private static List<MockConnection> mocks() {
        def accel = new MockConnection()
        def mag = new MockConnection()
        mag.setRegister(0x0A, 0x48, 0x34, 0x33)
        accel.setRegister(0x28 | 0x80, 0x10, 0x00, 0x20, 0x00, 0x30, 0x00)
        mag.setRegister(0x03, 0x04, 0x4C, 0x03, 0xD4, 0xFB, 0xB4)
        [accel, mag]
    }

    private static int lastWrite(MockConnection mock, int reg) {
        int value = -1
        for (w in mock.writes()) if (w.length == 2 && (w[0] as int & 0xFF) == reg) value = w[1] as int & 0xFF
        value
    }

    def "init and read"() {
        given:
        def (accel, mag) = mocks()
        def chip = new LSM303Minimal(accel, mag)

        expect:
        lastWrite(accel, 0x23) == 0x88
        lastWrite(accel, 0x20) == 0x57
        lastWrite(mag, 0x01) == 0x20
        Math.abs(chip.readAcceleration()[0] - 0.001d) < 1e-9
        Math.abs(chip.readAcceleration()[1] - 0.002d) < 1e-9
        Math.abs(chip.readAcceleration()[2] - 0.003d) < 1e-9
        Math.abs(chip.readMagnetic()[0] - 100d) < 1e-6
        Math.abs(chip.readMagnetic()[1] + 100d) < 1e-6
        Math.abs(chip.readMagnetic()[2] - 100d) < 1e-6
    }

    def "wrong identity throws"() {
        when:
        new LSM303Minimal(new MockConnection(), new MockConnection())

        then:
        thrown(IOException)
    }

    def "full configuration"() {
        given:
        def (accel, mag) = mocks()
        def chip = new LSM303Full(accel, mag)

        when:
        chip.setRange(4)

        then:
        Math.abs(chip.readAcceleration()[0] - 0.002d) < 1e-9
        (lastWrite(accel, 0x23) & 0x30) == 0x10

        when:
        chip.setDataRate(400)

        then:
        ((lastWrite(accel, 0x20) >> 4) & 0x0F) == 0x07

        when:
        chip.setMode(LSM303Minimal.MODE_NORMAL)

        then:
        (lastWrite(accel, 0x23) & 0x08) == 0

        when:
        chip.setRange(2)
        chip.setMotionInterrupt(1, 0x3F, 0.5d, 0d, true)
        chip.setFreeFall(1, 0.35d, 30d)
        chip.setClick(0, 0x04, 0.5d, 10d, 20d, 50d)
        chip.enableInterrupt(LSM303Source.SOURCE_DATA_READY, 1)
        chip.enableInterrupt(LSM303Source.SOURCE_CLICK, 2)
        chip.setMagRange(470)
        mag.setRegister(0x03, 0x01, 0x90, 0x01, 0x8F, 0x01, 0x8C)
        chip.enableTemperature(true)
        chip.setMagDataRate(30d)

        then:
        lastWrite(accel, 0x32) == 32
        lastWrite(accel, 0x30) == 0x95
        (lastWrite(accel, 0x38) & 0x20) == 0x20
        (lastWrite(accel, 0x22) & 0x10) == 0x10
        (lastWrite(accel, 0x25) & 0x80) == 0x80
        lastWrite(mag, 0x01) == 0xA0
        Math.abs(chip.readMagnetic()[0] - 100d) < 1e-6
        (lastWrite(mag, 0x00) & 0x1C) == 0x14
        (lastWrite(mag, 0x00) & 0x80) == 0x80
        chip.readMagIdentity() == [0x48, 0x34, 0x33] as byte[]

        when:
        mag.setRegister(0x31, 0x00, 0x80)
        def temp = chip.readTemperatureDelta()

        then:
        Math.abs(temp - 1.0d) < 1e-9

        when:
        chip.enableInterrupt(LSM303Source.SOURCE_MAG_DATA_READY, 1)

        then:
        thrown(IllegalArgumentException)
    }
}
