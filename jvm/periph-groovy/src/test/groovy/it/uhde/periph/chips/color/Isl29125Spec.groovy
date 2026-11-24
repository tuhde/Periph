package it.uhde.periph.chips.color

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicInteger

class Isl29125Spec extends Specification {

    def setupSpec() {
        Isl29125Minimal.pollStepMs = 0
    }

    static MockConnection mock(int id) {
        def c = new MockConnection()
        c.setRegister(0x00, id)
        c.setRegister(0x01, 0x00)
        c.setRegister(0x02, 0x00)
        c.setRegister(0x03, 0x00)
        c.setRegister(0x08, 0x02)
        return c
    }

    static int reg(MockConnection c, int r) { c.registers().get(r) }

    def "init writes the defaults"() {
        given:
        def c = mock(0x7D)

        when:
        new Isl29125Full(c)
        def seq = c.writes().findAll { it.length > 1 }.collect { [it[0] & 0xFF, it[1] & 0xFF] }

        then:
        seq == [[0x08, 0x00], [0x03, 0x00], [0x02, 0xBF], [0x01, 0x0D]]
    }

    def "init rejects a wrong device id and writes nothing"() {
        given:
        def c = mock(0x00)

        when:
        new Isl29125Minimal(c)

        then:
        thrown(IllegalStateException)
        c.writes().every { it.length == 1 }
    }

    def "read decodes the three channels"() {
        given:
        def c = mock(0x7D)
        def chip = new Isl29125Full(c)
        c.setRegister(0x08, 0x02)
        c.setRegister(0x09, 0x34, 0x12, 0xFF, 0xFF, 0x01, 0x00)

        when:
        def n = chip.read()

        then:
        n.green == 0x1234
        n.red == 0xFFFF
        n.blue == 1
        chip.readRgb().red == 0xFFFF
        chip.readRed() == 0xFFFF
        chip.readGreen() == 0x1234
        chip.readBlue() == 1
        chip.deviceId() == 0x7D
    }

    def "configuration is read-modify-write"() {
        given:
        def c = mock(0x7D)
        def chip = new Isl29125Full(c)
        c.setRegister(0x01, 0x0D)

        expect:
        (0..7).every { mode -> chip.setMode(mode); reg(c, 0x01) == (0x08 | mode) }

        when:
        chip.setRange(375)
        then:
        reg(c, 0x01) == 0x07

        when:
        chip.setRange(10000)
        then:
        reg(c, 0x01) == 0x0F

        when:
        chip.setResolution(12)
        then:
        reg(c, 0x01) == 0x1F

        when:
        chip.setResolution(16)
        chip.setSync(true)
        then:
        reg(c, 0x01) == 0x2F

        when:
        chip.setSync(false)
        chip.setIrCompensation(false, 0x3F)
        then:
        reg(c, 0x01) == 0x0F
        reg(c, 0x02) == 0x3F

        when:
        chip.setIrCompensation(true, 0x3F)
        then:
        reg(c, 0x02) == 0xBF
    }

    def "invalid arguments write nothing"() {
        given:
        def c = mock(0x7D)
        def chip = new Isl29125Full(c)
        def before = c.writes().size()

        when:
        call.call(chip)

        then:
        thrown(IllegalArgumentException)
        c.writes().size() == before

        where:
        call << [
            { Isl29125Full x -> x.setMode(8) },
            { Isl29125Full x -> x.setRange(100) },
            { Isl29125Full x -> x.setResolution(8) },
            { Isl29125Full x -> x.setIrCompensation(false, 64) },
            { Isl29125Full x -> x.setPersistence(3) },
            { Isl29125Full x -> x.setInterruptChannel(4) },
            { Isl29125Full x -> x.setThresholds(5, 4) },
            { Isl29125Full x -> x.setThresholds(0, 0x10000) },
        ]
    }

    def "soft reset writes the magic value"() {
        given:
        def c = mock(0x7D)
        def chip = new Isl29125Full(c)
        def before = c.writes().size()

        when:
        chip.softReset()
        def w = c.writes().get(before)

        then:
        w.length == 2
        (w[0] & 0xFF) == 0x00
        (w[1] & 0xFF) == 0x46
    }

    def "thresholds are two bytes each"() {
        given:
        def c = mock(0x7D)
        def chip = new Isl29125Full(c)

        when:
        chip.setThresholds(0x1234, 0xABCD)

        then:
        reg(c, 0x04) == 0x34
        reg(c, 0x05) == 0x12
        reg(c, 0x06) == 0xCD
        reg(c, 0x07) == 0xAB
        chip.getThresholds() == [0x1234, 0xABCD] as int[]
    }

    def "interrupt configuration"() {
        given:
        def c = mock(0x7D)
        def chip = new Isl29125Full(c)

        when:
        chip.setInterruptChannel(Isl29125Full.CHANNEL_RED)
        then:
        reg(c, 0x03) == 0x02

        when:
        chip.setInterruptChannel(Isl29125Full.CHANNEL_NONE)
        chip.setPersistence(8)
        then:
        reg(c, 0x03) == 0x0C

        when:
        chip.setPersistence(4)
        chip.setInterruptChannel(Isl29125Full.CHANNEL_GREEN)
        then:
        reg(c, 0x03) == 0x09

        when:
        chip.enableInterrupt(Isl29125Full.SOURCE_CONVERSION)
        then:
        reg(c, 0x03) == 0x19

        when:
        chip.disableInterrupt(Isl29125Full.SOURCE_CONVERSION)
        chip.disableInterrupt()
        then:
        reg(c, 0x03) == 0x08

        when:
        chip.enableInterrupt()
        then:
        reg(c, 0x03) == 0x09

        when:
        chip.setInterruptChannel(Isl29125Full.CHANNEL_RED)
        chip.enableInterrupt(Isl29125Full.SOURCE_THRESHOLD)
        then:
        reg(c, 0x03) == 0x0A
    }

    def "data ready keeps the threshold flag"() {
        given:
        def c = mock(0x7D)
        def chip = new Isl29125Full(c)

        when:
        c.setRegister(0x08, 0x03)
        then:
        chip.dataReady()

        when:
        c.setRegister(0x08, 0x00)
        then:
        !chip.dataReady()
        chip.pollInterrupt() == 0x03
        chip.pollInterrupt() == 0x00

        when:
        c.setRegister(0x08, 0x04)
        then:
        chip.status() == 0x04
    }

    def "callback receives the status"() {
        given:
        def c = mock(0x7D)
        def chip = new Isl29125Full(c)
        def calls = new AtomicInteger()
        def last = new AtomicInteger()
        c.setRegister(0x08, 0x01)

        when:
        chip.onInterrupt({ int s -> calls.incrementAndGet(); last.set(s) } as java.util.function.IntConsumer)
        long end = System.currentTimeMillis() + 2000
        while (calls.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(10)
        c.setRegister(0x08, 0x00)
        chip.offInterrupt()

        then:
        calls.get() >= 1
        last.get() == 0x01
    }
}
