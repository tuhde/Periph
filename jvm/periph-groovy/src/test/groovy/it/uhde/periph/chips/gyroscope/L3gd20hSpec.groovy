package it.uhde.periph.chips.gyroscope

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class L3gd20hSpec extends Specification {

    def "full API"() {
        given:
        def connection = new MockConnection()
        connection.setRegister(L3gd20hMinimal.REG_WHO_AM_I, 0xD7)

        def sensor = new L3gd20hFull(connection, false)

        expect:
        connection.registers().get(L3gd20hMinimal.REG_CTRL_REG4) == L3gd20hFull.CTRL_REG4_DEFAULT
        connection.registers().get(L3gd20hMinimal.REG_CTRL_REG1) == L3gd20hFull.CTRL_REG1_DEFAULT

        // raw X=+16, Y=0, Z=-16 (LE). I²C multi-byte sub-address has bit 7 set.
        connection.setRegister(L3gd20hMinimal.REG_OUT_X_L | 0x80,
                0x10, 0x00,    // X=+16
                0x00, 0x00,    // Y=0
                0xF0, 0xFF)    // Z=-16

        def k = (float) (Math.PI / 180.0)
        def xyz = sensor.gyro()
        Math.abs(xyz[0] - 16.0f * 0.00875f * k) < 1e-6f
        Math.abs(xyz[1] - 0.0f) < 1e-6f
        Math.abs(xyz[2] - (-16.0f) * 0.00875f * k) < 1e-6f

        when:
        sensor.configure(L3gd20hFull.ODR_190_HZ, 0, L3gd20hFull.FS_500_DPS)

        then:
        connection.registers().get(L3gd20hMinimal.REG_CTRL_REG1) == (L3gd20hFull.CTRL_REG1_DEFAULT | (1 << 6))
        connection.registers().get(L3gd20hMinimal.REG_CTRL_REG4) == (L3gd20hFull.CTRL_REG4_DEFAULT | (1 << 4))

        when:
        connection.setRegister(L3gd20hMinimal.REG_OUT_X_L | 0x80,
                0x00, 0x80,    // X=-32768
                0xFF, 0x7F,    // Y=32767
                0x00, 0x00)    // Z=0
        def raw = sensor.gyroRaw()

        then:
        raw[0] == -32768
        raw[1] == 32767
        raw[2] == 0

        when:
        connection.setRegister(L3gd20hMinimal.REG_OUT_TEMP, 0x80)

        then:
        sensor.temperature() == -128

        when:
        connection.setRegister(L3gd20hMinimal.REG_STATUS, 0x08)

        then:
        sensor.dataReady()

        when:
        sensor.configureHpFilter(1, 5)

        then:
        connection.registers().get(L3gd20hMinimal.REG_CTRL_REG2) == 0x15

        when:
        connection.setRegister(L3gd20hMinimal.REG_CTRL_REG5, 0x00)
        sensor.enableHpFilter(true)

        then:
        connection.registers().get(L3gd20hMinimal.REG_CTRL_REG5) == 0x10

        when:
        sensor.enableHpFilter(false)

        then:
        connection.registers().get(L3gd20hMinimal.REG_CTRL_REG5) == 0x00

        when:
        connection.setRegister(L3gd20hMinimal.REG_CTRL_REG5, 0x00)
        sensor.configureFifo(L3gd20hFull.FIFO_FIFO, 10)

        then:
        connection.registers().get(L3gd20hMinimal.REG_CTRL_REG5) == 0x40
        connection.registers().get(L3gd20hMinimal.REG_FIFO_CTRL) == ((1 << 5) | 10)

        when:
        sensor.enableFifo(true)

        then:
        connection.registers().get(L3gd20hMinimal.REG_CTRL_REG5) == 0x40

        when:
        sensor.enableFifo(false)

        then:
        connection.registers().get(L3gd20hMinimal.REG_CTRL_REG5) == 0x00
        connection.registers().get(L3gd20hMinimal.REG_FIFO_CTRL) == 0x00

        when:
        connection.setRegister(L3gd20hMinimal.REG_FIFO_SRC, 0x05)

        then:
        sensor.fifoLevel() == 5

        when:
        connection.setRegister(L3gd20hMinimal.REG_OUT_X_L | 0x80,
                0x10, 0x00, 0x20, 0x00, 0x30, 0x00,
                0x40, 0x00, 0x50, 0x00, 0x60, 0x00,
                0x70, 0x00, 0x80, 0x00, 0x90, 0x00,
                0xA0, 0x00, 0xB0, 0x00, 0xC0, 0x00,
                0xD0, 0x00, 0xE0, 0x00, 0xF0, 0x00)
        def samples = sensor.readFifo()

        then:
        samples.size() == 5

        when:
        connection.setRegister(L3gd20hMinimal.REG_CTRL_REG1, 0x00)
        sensor.setPowerMode(L3gd20hFull.POWER_NORMAL)

        then:
        (connection.registers().get(L3gd20hMinimal.REG_CTRL_REG1) & 0x0F) == 0x0F

        when:
        sensor.setPowerMode(L3gd20hFull.POWER_SLEEP)

        then:
        (connection.registers().get(L3gd20hMinimal.REG_CTRL_REG1) & 0x0F) == 0x08

        when:
        sensor.setPowerMode(L3gd20hFull.POWER_POWERDOWN)

        then:
        (connection.registers().get(L3gd20hMinimal.REG_CTRL_REG1) & 0x08) == 0x00
    }
}
