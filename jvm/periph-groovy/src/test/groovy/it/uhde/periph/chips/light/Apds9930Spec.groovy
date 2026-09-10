package it.uhde.periph.chips.light

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Apds9930Spec extends Specification {

    private int cw(reg) { return Apds9930Minimal.CMD_WRITE | (reg & 0x1F) }
    private int cr(reg) { return Apds9930Minimal.CMD_READ  | (reg & 0x1F) }

    def "full APDS-9930 API"() {
        given:
        def connection = new MockConnection()
        // APDS-9930 uses a command-register protocol — preload registers at
        // the command-byte form (0x80|reg for write, 0xA0|reg for read).
        connection.setRegister(cr(Apds9930Minimal.REG_ID), 0x39)

        when:
        def sensor = new Apds9930Full(connection)

        then:
        (connection.registers()[cw(Apds9930Minimal.REG_ATIME)] & 0xFF) == Apds9930Minimal.ATIME_DEFAULT
        (connection.registers()[cw(Apds9930Minimal.REG_CONTROL)] & 0xFF) == Apds9930Minimal.CONTROL_DEFAULT
        (connection.registers()[cw(Apds9930Minimal.REG_ENABLE)] & 0xFF) == Apds9930Minimal.ENABLE_DEFAULT

        when: "bad ID rejects construction"
        def badConnection = new MockConnection()
        badConnection.setRegister(cr(Apds9930Minimal.REG_ID), 0xAB)
        new Apds9930Full(badConnection)

        then:
        thrown(java.io.IOException)

        when: "lux with visible-only light"
        connection.setRegister(cr(Apds9930Minimal.REG_CH0DATAL), 0x10, 0x00)
        connection.setRegister(cr(Apds9930Minimal.REG_CH1DATAL), 0x00, 0x00)
        connection.setRegister(cr(Apds9930Minimal.REG_CONTROL), Apds9930Minimal.CONTROL_DEFAULT)
        connection.setRegister(cr(Apds9930Minimal.REG_CONFIG), 0)
        connection.setRegister(cr(Apds9930Minimal.REG_ATIME), Apds9930Minimal.ATIME_DEFAULT)

        then:
        sensor.lux() > 0.0f

        when: "dark channels → lux = 0"
        connection.setRegister(cr(Apds9930Minimal.REG_CH0DATAL), 0, 0)
        connection.setRegister(cr(Apds9930Minimal.REG_CH1DATAL), 0, 0)

        then:
        sensor.lux() == 0.0f

        when: "proximity read"
        connection.setRegister(cr(Apds9930Minimal.REG_PDATAL), 0x34, 0x12)

        then:
        sensor.proximity() == 0x1234

        when: "configureAls"
        sensor.configureAls(0xF6, 2, false)

        then:
        (connection.registers()[cw(Apds9930Minimal.REG_ATIME)] & 0xFF) == 0xF6
        (connection.registers()[cw(Apds9930Minimal.REG_CONTROL)] & 0x03) == 2

        when: "configureProximity"
        sensor.configureProximity(8, 1, 2, false, 0xFF)

        then:
        (connection.registers()[cw(Apds9930Minimal.REG_PPULSE)] & 0xFF) == 8
        (connection.registers()[cw(Apds9930Minimal.REG_PTIME)] & 0xFF) == 0xFF

        when: "configureWait with WLONG"
        sensor.configureWait(0x80, true)

        then:
        (connection.registers()[cw(Apds9930Minimal.REG_WTIME)] & 0xFF) == 0x80
        (connection.registers()[cw(Apds9930Minimal.REG_CONFIG)] & 0x02) == 0x02

        when: "disableWait"
        sensor.disableWait()

        then:
        (connection.registers()[cw(Apds9930Minimal.REG_ENABLE)] & 0x08) == 0

        when: "status with STATUS=0x01"
        connection.setRegister(cr(Apds9930Minimal.REG_STATUS), 0x01)

        then:
        def st = sensor.status()
        st.avalid == true
        st.pvalid == false

        when: "ALS thresholds enable AIEN"
        sensor.setAlsThresholds(100, 60000, 1)

        then:
        (connection.registers()[cw(Apds9930Minimal.REG_ENABLE)] & 0x10) == 0x10

        when: "proximity thresholds enable PIEN"
        sensor.setProximityThresholds(10, 200, 1)

        then:
        (connection.registers()[cw(Apds9930Minimal.REG_ENABLE)] & 0x20) == 0x20

        when: "setProximityOffset(-50) → 0x32 sign-magnitude"
        sensor.setProximityOffset(-50)

        then:
        (connection.registers()[cw(Apds9930Minimal.REG_POFFSET)] & 0xFF) == 0x32

        when: "sleepAfterInterrupt toggles SAI"
        sensor.sleepAfterInterrupt(true)

        then:
        (connection.registers()[cw(Apds9930Minimal.REG_ENABLE)] & 0x40) == 0x40

        when:
        sensor.sleepAfterInterrupt(false)

        then:
        (connection.registers()[cw(Apds9930Minimal.REG_ENABLE)] & 0x40) == 0

        when: "clearInterrupt emits the special-function command"
        sensor.clearInterrupt(0)

        then:
        connection.writes().last()[0] == (byte) 0xE7

        when:
        sensor.clearInterrupt(1)

        then:
        connection.writes().last()[0] == (byte) 0xE6

        when:
        sensor.clearInterrupt(2)

        then:
        connection.writes().last()[0] == (byte) 0xE5

        expect:
        sensor.chipId() == 0x39
    }
}