package it.uhde.periph.chips.power

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Ina226Spec extends Specification {

    def "full API"() {
        given:
        def connection = new MockConnection()

        when: "construction: rShunt=0.1, maxCurrent=2.0 (defaults) writes CONFIG then CAL"
        def sensor = new Ina226Full(connection)

        then:
        connection.registers().get(Ina226Minimal.REG_CONFIG) == 0x41
        connection.registers().get(Ina226Minimal.REG_CONFIG + 1) == 0x27
        connection.registers().get(Ina226Minimal.REG_CAL) == 0x03
        connection.registers().get(Ina226Minimal.REG_CAL + 1) == 0x46

        when: "Bus voltage: raw=6400 (0x1900) -> 6400 * 1.25e-3 = 8.0 V"
        connection.setRegister(Ina226Minimal.REG_BUS, 0x19, 0x00)

        then:
        sensor.voltage() == 8.0d

        when: "Shunt voltage: raw signed = -100 (0xFF9C) -> -100 * 2.5e-6 V"
        connection.setRegister(Ina226Minimal.REG_SHUNT, 0xFF, 0x9C)

        then:
        Math.abs(sensor.shuntVoltage() - (-100 * 2.5e-6d)) < 1e-12

        when: "Current: raw signed = 1000 (0x03E8) -> 1000 * currentLsb"
        connection.setRegister(Ina226Minimal.REG_CURRENT, 0x03, 0xE8)
        double currentLsb = 2.0d / 32768.0d

        then:
        Math.abs(sensor.current() - (1000 * currentLsb)) < 1e-12

        when: "Power: raw = 500 (0x01F4) -> 500 * 25 * currentLsb"
        connection.setRegister(Ina226Minimal.REG_POWER, 0x01, 0xF4)

        then:
        Math.abs(sensor.power() - (500 * 25.0d * currentLsb)) < 1e-9

        when: "configure(avg=2, vbusCt=3, vshCt=5, mode=6) -> config = 0x04EE"
        sensor.configure(2, 3, 5, 6)

        then:
        connection.registers().get(Ina226Minimal.REG_CONFIG) == 0x04
        connection.registers().get(Ina226Minimal.REG_CONFIG + 1) == 0xEE

        when: "conversionReady(): CVRF bit (0x0008)"
        connection.setRegister(Ina226Minimal.REG_MASK_EN, 0x00, 0x08)

        then:
        sensor.conversionReady()

        when:
        connection.setRegister(Ina226Minimal.REG_MASK_EN, 0x00, 0x00)

        then:
        !sensor.conversionReady()

        when: "overflow(): OVF bit (0x0004)"
        connection.setRegister(Ina226Minimal.REG_MASK_EN, 0x00, 0x04)

        then:
        sensor.overflow()

        when: "setAlert(POL, 1.5): raw = 983 (0x03D7); checked via writes() log, not registers() -\n" +
                "REG_ALERT_LIM (0x07) is REG_MASK_EN+1, so the byte-slot register map has the ALERT\n" +
                "write clobber the MASK write's low byte slot; the write log is unaffected by that."
        sensor.setAlert(Ina226Full.POL, 1.5d)
        def maskWrite = connection.writes().findAll { it.length == 3 && (it[0] & 0xFF) == Ina226Minimal.REG_MASK_EN }.last()
        def alertWrite = connection.writes().findAll { it.length == 3 && (it[0] & 0xFF) == Ina226Minimal.REG_ALERT_LIM }.last()

        then:
        maskWrite[1] == (byte) 0x08
        maskWrite[2] == (byte) 0x00
        alertWrite[1] == (byte) 0x03
        alertWrite[2] == (byte) 0xD7

        when: "alertFlags(): raw Mask/Enable register"
        connection.setRegister(Ina226Minimal.REG_MASK_EN, 0x08, 0x03)

        then:
        sensor.alertFlags() == 0x0803

        when: "reset(): writes CONFIG=0x8000, then re-writes CAL"
        sensor.reset()

        then:
        connection.registers().get(Ina226Minimal.REG_CONFIG) == 0x80
        connection.registers().get(Ina226Minimal.REG_CONFIG + 1) == 0x00
        connection.registers().get(Ina226Minimal.REG_CAL) == 0x03
        connection.registers().get(Ina226Minimal.REG_CAL + 1) == 0x46

        when: "shutdown(): reads CONFIG, saves mode, writes CONFIG & ~0x07"
        connection.setRegister(Ina226Minimal.REG_CONFIG, 0x41, 0x27)
        sensor.shutdown()
        def shutdownWrite = connection.writes().last()

        then:
        shutdownWrite[1] == (byte) 0x41
        shutdownWrite[2] == (byte) 0x20

        when: "wake(): reads CONFIG, writes back with saved mode restored"
        connection.setRegister(Ina226Minimal.REG_CONFIG, 0x41, 0x20)
        sensor.wake()
        def wakeWrite = connection.writes().last()

        then:
        wakeWrite[1] == (byte) 0x41
        wakeWrite[2] == (byte) 0x27

        when: "manufacturerId() / dieId()"
        connection.setRegister(Ina226Minimal.REG_MFR_ID, 0x54, 0x49)

        then:
        sensor.manufacturerId() == 0x5449

        when:
        connection.setRegister(Ina226Minimal.REG_DIE_ID, 0x22, 0x60)

        then:
        sensor.dieId() == 0x2260
    }
}
