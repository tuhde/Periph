package it.uhde.periph.chips.power

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Ina260Spec extends Specification {

    private static boolean isWrite(byte[] w, int reg, int hi, int lo) {
        w.length == 3 && w[0] == (byte) reg && w[1] == (byte) hi && w[2] == (byte) lo
    }

    def "full API"() {
        given:
        def connection = new MockConnection()
        def writes = connection.writes()

        when: "construction makes no bus access (no calibration)"
        def sensor = new Ina260Full(connection)

        then:
        writes.isEmpty()

        when: "Bus voltage: raw=6400 (0x1900) -> 8.0 V"
        connection.setRegister(Ina260Minimal.REG_BUS, 0x19, 0x00)

        then:
        sensor.voltage() == 8.0d

        when: "Current: raw signed = -100 (0xFF9C) -> -0.125 A"
        connection.setRegister(Ina260Minimal.REG_CURRENT, 0xFF, 0x9C)

        then:
        Math.abs(sensor.current() - (-0.125d)) < 1e-12

        when: "Current: datasheet example 0x2710 -> 12.5 A"
        connection.setRegister(Ina260Minimal.REG_CURRENT, 0x27, 0x10)

        then:
        Math.abs(sensor.current() - 12.5d) < 1e-12

        when: "Power: datasheet example 0x3A7F = 14975 -> 149.75 W"
        connection.setRegister(Ina260Minimal.REG_POWER, 0x3A, 0x7F)

        then:
        Math.abs(sensor.power() - 149.75d) < 1e-9

        when: "configure(2, 3, 5, 6) -> 0x64EE"
        sensor.configure(2, 3, 5, 6)

        then:
        isWrite(writes.last(), Ina260Minimal.REG_CONFIG, 0x64, 0xEE)

        when: "trigger(): same settings, MODE = 3 -> 0x64EB"
        sensor.trigger()

        then:
        isWrite(writes.last(), Ina260Minimal.REG_CONFIG, 0x64, 0xEB)

        when: "conversionReady(): CVRF bit (0x0008)"
        connection.setRegister(Ina260Minimal.REG_MASK_EN, 0x00, 0x08)

        then:
        sensor.conversionReady()

        when:
        connection.setRegister(Ina260Minimal.REG_MASK_EN, 0x00, 0x00)

        then:
        !sensor.conversionReady()

        when: "overflow(): OVF bit (0x0004)"
        connection.setRegister(Ina260Minimal.REG_MASK_EN, 0x00, 0x04)

        then:
        sensor.overflow()

        when: "setAlert(POL, 1.5 W, polarity, latch): limit 150 first, then mask 0x0803"
        writes.clear()
        sensor.setAlert(Ina260Full.POL, 1.5d, true, true)

        then:
        writes.size() == 2
        isWrite(writes[0], Ina260Minimal.REG_ALERT_LIM, 0x00, 0x96)
        isWrite(writes[1], Ina260Minimal.REG_MASK_EN, 0x08, 0x03)

        when: "setAlert(OCL, 5.0 A): 4000 (0x0FA0), mask 0x8000"
        writes.clear()
        sensor.setAlert(Ina260Full.OCL, 5.0d)

        then:
        isWrite(writes[0], Ina260Minimal.REG_ALERT_LIM, 0x0F, 0xA0)
        isWrite(writes[1], Ina260Minimal.REG_MASK_EN, 0x80, 0x00)

        when: "setAlert(UCL, -1.0 A): -800 -> 0xFCE0"
        writes.clear()
        sensor.setAlert(Ina260Full.UCL, -1.0d)

        then:
        isWrite(writes[0], Ina260Minimal.REG_ALERT_LIM, 0xFC, 0xE0)

        when: "setAlert(CNVR): limit register not written"
        writes.clear()
        sensor.setAlert(Ina260Full.CNVR, 0.0d)

        then:
        writes.size() == 1
        isWrite(writes[0], Ina260Minimal.REG_MASK_EN, 0x04, 0x00)

        when: "clearAlert(): Mask/Enable = 0"
        sensor.clearAlert()

        then:
        isWrite(writes.last(), Ina260Minimal.REG_MASK_EN, 0x00, 0x00)

        when: "alertFlags(): raw Mask/Enable register"
        connection.setRegister(Ina260Minimal.REG_MASK_EN, 0x08, 0x03)

        then:
        sensor.alertFlags() == 0x0803

        when: "reset(): CONFIG = 0x8000"
        sensor.reset()

        then:
        isWrite(writes.last(), Ina260Minimal.REG_CONFIG, 0x80, 0x00)

        when: "shutdown(): MODE = 000 -> 0x6120"
        sensor.shutdown()

        then:
        isWrite(writes.last(), Ina260Minimal.REG_CONFIG, 0x61, 0x20)

        when: "wake(): MODE = 111 -> 0x6127"
        sensor.wake()

        then:
        isWrite(writes.last(), Ina260Minimal.REG_CONFIG, 0x61, 0x27)

        when: "manufacturerId()"
        connection.setRegister(Ina260Minimal.REG_MFR_ID, 0x54, 0x49)

        then:
        sensor.manufacturerId() == 0x5449

        when: "dieId() (0xFE/0xFF overlap in the mock's byte-slot map, so set after the check above)"
        connection.setRegister(Ina260Minimal.REG_DIE_ID, 0x22, 0x70)

        then:
        sensor.dieId() == 0x2270
    }
}
