package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class ADS7828Spec extends Specification {

    private static byte[] last(MockConnection c) {
        return c.writes().last()
    }

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length]
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i]
        return out
    }

    def "construction makes no bus traffic and init enables the reference"() {
        given:
        def c = new MockConnection()
        def adc = new ADS7828Full(c)

        expect:
        c.writes().size() == 0

        when:
        adc.init()

        then:
        c.writes().size() == 1
        last(c) == b(0x08)
        adc.reference == 2.5d
        adc.usesInternalReference()
    }

    def "single-ended commands and results"() {
        given:
        def c = new MockConnection()
        def adc = new ADS7828Full(c)
        adc.init()

        when:
        c.setRegister(0x8C, 0x0A, 0xBC)
        def raw = adc.readRaw(0)

        then:
        raw == 0xABC
        last(c) == b(0x8C)

        when:
        c.setRegister(0xCC, 0x0F, 0xFF)

        then:
        adc.readRaw(1) == 0xFFF
        last(c) == b(0xCC)

        when:
        adc.readRaw(2)

        then:
        last(c) == b(0x9C)

        when:
        adc.readRaw(7)

        then:
        last(c) == b(0xFC)

        when:
        c.setRegister(0x8C, 0xF1, 0x23)

        then:
        adc.readRaw(0) == 0x123

        when:
        adc.readRaw(99)

        then:
        last(c) == b(0xFC)

        when:
        adc.readRaw(-3)

        then:
        last(c) == b(0x8C)

        when:
        c.setRegister(0x8C, 0x08, 0x00)

        then:
        Math.abs(adc.readVoltage(0) - 1.25d) < 1e-9d
        adc.readAll().length == 8
    }

    def "differential and signed reads"() {
        given:
        def c = new MockConnection()
        def adc = new ADS7828Full(c)

        when:
        c.setRegister(0x0C, 0x04, 0x00)

        then:
        adc.readDifferentialRaw(0, false) == 0x400
        last(c) == b(0x0C)

        when:
        adc.readDifferentialRaw(3, true)

        then:
        last(c) == b(0x7C)

        when:
        c.setRegister(0x0C, 0x00, 0x00)
        c.setRegister(0x4C, 0x02, 0x00)

        then:
        Math.abs(adc.readSignedDifferential(0) - (-0x200 / 4096.0d * 2.5d)) < 1e-9d

        when:
        c.setRegister(0x0C, 0x02, 0x00)
        c.setRegister(0x4C, 0x00, 0x00)

        then:
        Math.abs(adc.readSignedDifferential(0) - (0x200 / 4096.0d * 2.5d)) < 1e-9d
    }

    def "reference, power mode and scan"() {
        given:
        def c = new MockConnection()
        def adc = new ADS7828Full(c, 5.0d)
        adc.init()

        expect:
        c.writes().size() == 0
        !adc.usesInternalReference()
        adc.reference == 5.0d

        when:
        c.setRegister(0x80, 0x08, 0x00)

        then:
        Math.abs(adc.readVoltage(0) - 2.5d) < 1e-9d
        last(c) == b(0x80)

        when:
        adc.setReference(null)

        then:
        last(c) == b(0x08)
        adc.reference == 2.5d

        when:
        adc.setPowerMode(1)

        then:
        thrown(IllegalArgumentException)

        when:
        adc.setPowerMode(7)

        then:
        thrown(IllegalArgumentException)

        when:
        adc.setPowerMode(ADS7828Full.REF_ON_ADC_OFF)
        adc.readRaw(0)

        then:
        last(c) == b(0x88)

        when:
        adc.setPowerMode(ADS7828Full.REF_ON_ADC_ON)
        adc.powerDown()

        then:
        last(c) == b(0x00)

        when:
        c.setRegister(0x8C, 0x00, 0x01)
        c.setRegister(0xCC, 0x00, 0x02)
        def scan = adc.readScan([0, 1] as int[])

        then:
        scan == [1, 2] as int[]
        c.writes()[c.writes().size() - 3] == b(0x08)
        Math.abs(adc.lsb() - 2.5d / 4096) < 1e-12d
    }

    def "minimal stands alone"() {
        given:
        def c = new MockConnection()
        def adc = new ADS7828Minimal(c)

        expect:
        adc.readRaw(0) == 0
        c.writes().size() == 2
        c.writes()[0] == b(0x08)
        c.writes()[1] == b(0x8C)
    }
}
