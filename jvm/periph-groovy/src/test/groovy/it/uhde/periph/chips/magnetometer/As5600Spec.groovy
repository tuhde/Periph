package it.uhde.periph.chips.magnetometer

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class As5600Spec extends Specification {

    def "full API"() {
        given:
        def connection = new MockConnection()
        // STATUS: MD=1 (magnet detected), MH=0, ML=0.
        connection.setRegister(As5600Minimal.REG_STATUS, 0x08)

        when:
        def sensor = new As5600Full(connection)

        then:
        sensor.isMagnetDetected()
        !sensor.isMagnetTooStrong()
        !sensor.isMagnetTooWeak()

        when: "ANGLE burst (0x0E-0x0F): H=0x01, L=0x23 -> raw = 0x0123 = 291"
        connection.setRegister(As5600Minimal.REG_ANGLE_H, 0x01, 0x23)

        then:
        sensor.angleRaw() == 291
        Math.abs(sensor.angle() - (291 * 360.0d / 4096.0d)) < 1e-9

        when: "RAW_ANGLE burst (0x0C-0x0D): H=0x02, L=0x00 -> raw = 512 -> 45.0 degrees"
        connection.setRegister(As5600Minimal.REG_RAW_ANGLE_H, 0x02, 0x00)

        then:
        sensor.rawAngle() == 512
        Math.abs(sensor.rawAngleDegrees() - 45.0d) < 1e-9

        when:
        connection.setRegister(As5600Minimal.REG_AGC, 128)

        then:
        sensor.agc() == 128

        when: "MAGNITUDE burst (0x1B-0x1C): H=0x00, L=0x64 -> raw = 100"
        connection.setRegister(As5600Minimal.REG_MAGNITUDE_H, 0x00, 0x64)

        then:
        sensor.magnitude() == 100

        when: "STATUS: MD=1, MH=1 (magnet too strong)"
        connection.setRegister(As5600Minimal.REG_STATUS, 0x28)

        then:
        sensor.isMagnetTooStrong()
        sensor.statusByte() == 0x28

        when: "configure() must preserve CONF_H[7:6] reserved bits (preloaded as 0xC5)"
        connection.setRegister(As5600Minimal.REG_CONF_H, 0xC5, 0x00)
        sensor.configure(1, 2, 1, 3, 2, 5, true)

        then:
        connection.registers().get(As5600Minimal.REG_CONF_H) == 0xF6
        connection.registers().get(As5600Minimal.REG_CONF_L) == 0xD9

        when:
        sensor.setZeroPosition(1000)

        then:
        sensor.zeroPosition() == 1000

        when:
        sensor.setMaxPosition(2000)

        then:
        sensor.maxPosition() == 2000

        when:
        sensor.setMaxAngle(2048)

        then:
        sensor.maxAngle() == 2048

        when:
        connection.setRegister(As5600Minimal.REG_ZMCO, 0x02)

        then:
        sensor.burnCount() == 2

        when: "burnAngle(): MD=1 (STATUS=0x28), ZMCO=2 < 3 -> succeeds, writes BURN=0x80 first"
        sensor.burnAngle()
        def burnAngleWrites = connection.writes().findAll {
            it.length == 2 && (it[0] & 0xFF) == As5600Minimal.REG_BURN && (it[1] & 0xFF) == 0x80
        }

        then:
        burnAngleWrites.size() == 1

        when: "burnSetting(): requires ZMCO=0"
        connection.setRegister(As5600Minimal.REG_ZMCO, 0x00)
        sensor.burnSetting()
        def burnSettingWrites = connection.writes().findAll {
            it.length == 2 && (it[0] & 0xFF) == As5600Minimal.REG_BURN && (it[1] & 0xFF) == 0x40
        }

        then:
        burnSettingWrites.size() == 1

        when: "burnAngle() must throw when magnet not detected"
        connection.setRegister(As5600Minimal.REG_STATUS, 0x00)
        sensor.burnAngle()

        then:
        thrown(Exception)

        when: "burnAngle() must throw when ZMCO limit (3) reached"
        connection.setRegister(As5600Minimal.REG_STATUS, 0x08)
        connection.setRegister(As5600Minimal.REG_ZMCO, 0x03)
        sensor.burnAngle()

        then:
        thrown(Exception)

        when: "burnSetting() must throw when ZMCO != 0"
        connection.setRegister(As5600Minimal.REG_ZMCO, 0x01)
        sensor.burnSetting()

        then:
        thrown(Exception)

        when: "construction must throw when no magnet is detected"
        def noMagnetConnection = new MockConnection()
        noMagnetConnection.setRegister(As5600Minimal.REG_STATUS, 0x00)
        new As5600Full(noMagnetConnection)

        then:
        thrown(Exception)
    }
}
