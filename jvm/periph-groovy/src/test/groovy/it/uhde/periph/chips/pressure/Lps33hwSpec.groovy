package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Lps33hwSpec extends Specification {

    private static void preloadDefaults(MockConnection connection) {
        connection.setRegister(Lps33hwMinimal.REG_WHO_AM_I, 0xB1)
    }

    def "full API"() {
        given: "STATUS indicates both P_DA and T_DA so the driver doesn't loop"
        def connection = new MockConnection()
        preloadDefaults(connection)
        connection.setRegister(Lps33hwMinimal.REG_STATUS, 0x03)
        connection.setRegister(Lps33hwMinimal.REG_PRESS_XL,
                0x00, 0x10, 0x00, 0xC4, 0x09)            // raw_pressure=4096 (=100 Pa), raw_temperature=2500 (=25 °C)
        connection.setRegister(Lps33hwMinimal.REG_RES_CONF, 0x00)

        when:
        def sensor = new Lps33hwFull(connection)

        then:
        noExceptionThrown()

        when: "pressure() and temperature()"
        def p = sensor.pressure()
        def t = sensor.temperature()

        then: "raw_pressure = 4096 → 100 Pa, raw_temperature = 2500 → 25 °C"
        Math.abs(p - 100.0d) < 1e-3d
        Math.abs(t - 25.0d) < 1e-3d

        when: "status() and interruptStatus()"
        connection.setRegister(Lps33hwMinimal.REG_STATUS, 0x03)
        def st = sensor.status()
        connection.setRegister(Lps33hwMinimal.REG_INT_SOURCE, 0x04)
        def intsrc = sensor.interruptStatus()

        then:
        st == 0x03
        intsrc == 0x04

        when: "configure(odr=2, bdu=true, enLpfp=true, lpfpCfg=1, lcEn=false, sim=false)"
        sensor.configure(2, true, true, 1, false, false)
        def ctrl1Write = connection.writes().findAll {
            it.length == 2 && (it[0] & 0xFF) == Lps33hwMinimal.REG_CTRL_REG1
        }.last()

        then: "ctrl1 = (2<<4)|(1<<3)|(1<<2)|(1<<1)|0 = 0x2E"
        ctrl1Write[1] == (byte) 0x2E

        when: "setPressureOffset(offsetHPa=16.0)"
        sensor.setPressureOffset(16.0d)
        def rpdsL = connection.writes().findAll {
            it.length == 2 && (it[0] & 0xFF) == Lps33hwMinimal.REG_RPDS_L
        }.last()
        def rpdsH = connection.writes().findAll {
            it.length == 2 && (it[0] & 0xFF) == Lps33hwMinimal.REG_RPDS_H
        }.last()

        then: "raw = 16 * 16 = 256 → RPDS_L=0x00, RPDS_H=0x01"
        rpdsL[1] == (byte) 0x00
        rpdsH[1] == (byte) 0x01

        when: "setAutozero / clearAutozero / setAutorifp / clearAutorifp"
        sensor.setAutozero()
        sensor.clearAutozero()
        sensor.setAutorifp()
        sensor.clearAutorifp()

        then:
        noExceptionThrown()

        when: "configureInterrupt(all true, intS=3)"
        sensor.configureInterrupt(true, true, true, true, 3, true, true)
        def ctrl3Write = connection.writes().findAll {
            it.length == 2 && (it[0] & 0xFF) == Lps33hwMinimal.REG_CTRL_REG3
        }.last()

        then: "ctrl3 = 0xFF"
        ctrl3Write[1] == (byte) 0xFF

        when: "enableFifo(mode=1, watermark=16)"
        sensor.enableFifo(1, 16)
        def fifoCtrlWrite = connection.writes().findAll {
            it.length == 2 && (it[0] & 0xFF) == Lps33hwMinimal.REG_FIFO_CTRL
        }.last()

        then: "fifo_ctrl = (1<<5)|16 = 0x30"
        fifoCtrlWrite[1] == (byte) 0x30

        when: "fifoStatus()"
        connection.setRegister(Lps33hwMinimal.REG_FIFO_STATUS, 0x80)
        def fst = sensor.fifoStatus()

        then:
        fst == 0x80

        when: "disableFifo() and resetLpf()"
        sensor.disableFifo()
        connection.setRegister(Lps33hwMinimal.REG_LPFP_RES, 0x00)
        sensor.resetLpf()

        then:
        noExceptionThrown()

        when: "reset() and reboot()"
        sensor.reset()
        sensor.reboot()

        then:
        noExceptionThrown()
    }

    def "wrong chip ID throws"() {
        given:
        def connection = new MockConnection()
        connection.setRegister(Lps33hwMinimal.REG_WHO_AM_I, 0x00)  // wrong

        when:
        new Lps33hwMinimal(connection)

        then:
        thrown(java.io.IOException)
    }
}