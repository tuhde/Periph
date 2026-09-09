package it.uhde.periph.chips.power

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Ina3221Spec extends Specification {

    def "full API"() {
        given:
        def connection = new MockConnection()

        when: "construction (default rShunt=0.1 for all 3 channels) writes nothing"
        def sensor = new Ina3221Full(connection)

        then:
        connection.writes().isEmpty()

        when: "Bus1 raw=10000 (0x2710) -> (10000>>3)*8e-3 = 10.0 V"
        connection.setRegister(0x02, 0x27, 0x10)

        then:
        sensor.voltage(1) == 10.0d

        when: "Shunt1 raw signed = -400 (0xFE70) -> (-400>>3)*40e-6 = -0.002 V"
        connection.setRegister(0x01, 0xFE, 0x70)
        def sv1 = sensor.shuntVoltage(1)

        then:
        Math.abs(sv1 - (((short) 0xFE70) >> 3) * 40e-6) < 1e-9
        Math.abs(sensor.current(1) - sv1 / 0.1d) < 1e-9

        when: "power(1): Shunt1 (0x01) and Bus1 (0x02) are adjacent registers, and the\n" +
                "mock's byte-slot model can't hold two independent 16-bit values across\n" +
                "adjacent addresses at once - so the Shunt1 low byte and Bus1 high byte\n" +
                "are chosen equal (0x10) to survive either write order. Shunt1=0xFF10,\n" +
                "Bus1=0x1000 (4096) -> 4.096 V."
        connection.setRegister(0x01, 0xFF, 0x10)
        connection.setRegister(0x02, 0x10, 0x00)
        def expectedShunt1 = (((short) 0xFF10) >> 3) * 40e-6

        then:
        Math.abs(sensor.power(1) - 4.096d * (expectedShunt1 / 0.1d)) < 1e-9

        when: "Bus2 raw=4096 (0x1000) -> (4096>>3)*8e-3 = 4.096 V"
        connection.setRegister(0x04, 0x10, 0x00)

        then:
        sensor.voltage(2) == 4.096d

        when: "Shunt2 raw=800 (0x0320) -> (800>>3)*40e-6"
        connection.setRegister(0x03, 0x03, 0x20)
        def sv2 = sensor.shuntVoltage(2)

        then:
        Math.abs(sv2 - (800 >> 3) * 40e-6) < 1e-9
        Math.abs(sensor.current(2) - sv2 / 0.1d) < 1e-9

        when:
        sensor.voltage(4)

        then:
        thrown(IllegalArgumentException)

        when: "configure(3, 2, 1, 5) preserves channel-enable bits (0x7000)"
        connection.setRegister(0x00, 0x71, 0x27)
        sensor.configure(3, 2, 1, 5)
        def configWrite1 = connection.writes().findAll { it.length == 3 && (it[0] & 0xFF) == 0x00 }.last()

        then:
        configWrite1[1] == (byte) 0x76
        configWrite1[2] == (byte) 0x8D

        when: "enableChannel(2, true): CH2en is bit 13"
        connection.setRegister(0x00, 0x01, 0x27)
        sensor.enableChannel(2, true)
        def configWrite2 = connection.writes().findAll { it.length == 3 && (it[0] & 0xFF) == 0x00 }.last()

        then:
        configWrite2[1] == (byte) 0x21
        configWrite2[2] == (byte) 0x27

        when: "channelEnabled(1): CH1en is bit 14"
        connection.setRegister(0x00, 0x41, 0x27)

        then:
        sensor.channelEnabled(1)

        when: "conversionReady(): CVRF is bit 0"
        connection.setRegister(0x0F, 0x00, 0x01)

        then:
        sensor.conversionReady()

        when: "setCriticalAlert(2, 0.048): raw = (round(1200) << 3) & 0xFFF8 = 0x2580"
        sensor.setCriticalAlert(2, 0.048d)
        def critWrite = connection.writes().findAll { it.length == 3 && (it[0] & 0xFF) == 0x09 }.last()

        then:
        critWrite[1] == (byte) 0x25
        critWrite[2] == (byte) 0x80

        when: "setWarningAlert(1, 0.024): raw = (round(600) << 3) & 0xFFF8 = 0x12C0"
        sensor.setWarningAlert(1, 0.024d)
        def warnWrite = connection.writes().findAll { it.length == 3 && (it[0] & 0xFF) == 0x08 }.last()

        then:
        warnWrite[1] == (byte) 0x12
        warnWrite[2] == (byte) 0xC0

        when: "alertFlags(): raw Mask/Enable register"
        connection.setRegister(0x0F, 0x02, 0x41)

        then:
        sensor.alertFlags() == 0x0241

        when: "setSummationChannels([1], 0.1) with a stale SCC3 bit (0x1000) already set"
        connection.setRegister(0x0F, 0x10, 0x00)
        sensor.setSummationChannels([1] as int[], 0.1d)
        def summationMaskWrite = connection.writes().findAll { it.length == 3 && (it[0] & 0xFF) == 0x0F }.last()
        def summationLimitWrite = connection.writes().findAll { it.length == 3 && (it[0] & 0xFF) == 0x0E }.last()

        then:
        summationMaskWrite[1] == (byte) 0x40
        summationMaskWrite[2] == (byte) 0x00
        summationLimitWrite[1] == (byte) 0x13
        summationLimitWrite[2] == (byte) 0x88

        when: "summationValue(): raw=0x2328 (9000) -> (9000>>1)*40e-6 = 0.18 V"
        connection.setRegister(0x0D, 0x23, 0x28)

        then:
        Math.abs(sensor.summationValue() - 0.18d) < 1e-9

        when: "setPowerValidLimits(8.112, 4.096)"
        sensor.setPowerValidLimits(8.112d, 4.096d)
        def pvUpperWrite = connection.writes().findAll { it.length == 3 && (it[0] & 0xFF) == 0x10 }.last()
        def pvLowerWrite = connection.writes().findAll { it.length == 3 && (it[0] & 0xFF) == 0x11 }.last()

        then:
        pvUpperWrite[1] == (byte) 0x1F
        pvUpperWrite[2] == (byte) 0xB0
        pvLowerWrite[1] == (byte) 0x10
        pvLowerWrite[2] == (byte) 0x00

        when: "powerValid(): PVF is bit 2"
        connection.setRegister(0x0F, 0x00, 0x04)

        then:
        sensor.powerValid()

        when: "shutdown(): reads CONFIG, saves MODE bits, writes CONFIG & 0xFFF8"
        connection.setRegister(0x00, 0x71, 0x27)
        sensor.shutdown()
        def shutdownWrite = connection.writes().last()

        then:
        shutdownWrite[1] == (byte) 0x71
        shutdownWrite[2] == (byte) 0x20

        when: "wake(): reads CONFIG, restores saved MODE bits (7, from shutdown())"
        connection.setRegister(0x00, 0x71, 0x20)
        sensor.wake()
        def wakeWrite = connection.writes().last()

        then:
        wakeWrite[1] == (byte) 0x71
        wakeWrite[2] == (byte) 0x27

        when: "reset(): writes CONFIG=0x8000 (RST), then restores hardware defaults\n" +
                "(0x7127) with the saved MODE (7, from shutdown())"
        sensor.reset()
        def writes = connection.writes()
        def resetWrite1 = writes[writes.size() - 2]
        def resetWrite2 = writes[writes.size() - 1]

        then:
        resetWrite1[1] == (byte) 0x80
        resetWrite1[2] == (byte) 0x00
        resetWrite2[1] == (byte) 0x71
        resetWrite2[2] == (byte) 0x27

        when: "manufacturerId() / dieId()"
        connection.setRegister(0xFE, 0x54, 0x49)

        then:
        sensor.manufacturerId() == 0x5449

        when:
        connection.setRegister(0xFF, 0x32, 0x20)

        then:
        sensor.dieId() == 0x3220
    }
}
