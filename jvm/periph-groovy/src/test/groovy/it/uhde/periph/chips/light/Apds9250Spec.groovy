package it.uhde.periph.chips.light

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicInteger

class Apds9250Spec extends Specification {

    def setupSpec() {
        Apds9250Minimal.startWaitMs = 0
        Apds9250Minimal.pollStepMs = 0
    }

    static MockConnection mock(int part) {
        def c = new MockConnection()
        c.setRegister(0x06, part)
        c.setRegister(0x07, 0x08)
        c.setRegister(0x04, 0x22)
        c.setRegister(0x05, 0x01)
        c.setRegister(0x19, 0x10)
        return c
    }

    static int reg(MockConnection c, int r) { c.registers().get(r) }

    def "init writes the defaults"() {
        given:
        def c = mock(0xB5)

        when:
        new Apds9250Full(c)
        def seq = c.writes().findAll { it.length > 1 }.collect { [it[0] & 0xFF, it[1] & 0xFF] }

        then:
        seq == [[0x04, 0x22], [0x05, 0x01], [0x19, 0x10], [0x00, 0x06]]
    }

    def "init rejects a wrong part id and writes nothing"() {
        given:
        def c = mock(part)

        when:
        new Apds9250Minimal(c)

        then:
        thrown(IllegalStateException)
        c.writes().every { it.length == 1 }

        where:
        part << [0x00, 0xC5]
    }

    def "read decodes the four channels"() {
        given:
        def c = mock(0xB5)
        def chip = new Apds9250Full(c)
        c.setRegister(0x0A, 0x01, 0x02, 0x03, 0x10, 0x00, 0x00, 0xFF, 0xFF, 0x0F, 0x00, 0x00, 0xF4)

        when:
        def n = chip.read()

        then:
        n.ir == 0x030201
        n.green == 16
        n.blue == 0xFFFFF
        n.red == 0x40000
        chip.readChannels().red == 0x40000
        chip.readRed() == 0x40000
        chip.readGreen() == 16
        chip.readBlue() == 0xFFFFF
        chip.readIr() == 0x030201
    }

    def "resolution rate and gain are read-modify-write"() {
        given:
        def c = mock(0xB5)
        def chip = new Apds9250Full(c)

        when:
        chip.setResolution(20)
        then:
        reg(c, 0x04) == 0x02
        chip.getResolution() == 20

        when:
        chip.setResolution(13)
        then:
        reg(c, 0x04) == 0x52
        Math.abs(chip.integrationTime() - 0.003125d) < 1e-9

        when:
        chip.setResolution(16)
        chip.setMeasurementRate(2000)
        then:
        reg(c, 0x04) == 0x46
        chip.getMeasurementRate() == 2000

        when:
        chip.setMeasurementRate(25)
        then:
        reg(c, 0x04) == 0x40
    }

    def "gains map to codes and normalisation uses them"() {
        given:
        def c = mock(0xB5)
        def chip = new Apds9250Full(c)

        when:
        chip.setGain(gain)

        then:
        reg(c, 0x05) == code
        chip.getGain() == gain

        where:
        gain | code
        1    | 0
        3    | 1
        6    | 2
        9    | 3
        18   | 4
    }

    def "normalized divides by gain and integration time"() {
        given:
        def chip = new Apds9250Full(mock(0xB5))

        expect:
        Math.abs(chip.normalized(3000) - 10000.0d) < 1e-6
    }

    def "invalid arguments throw and write nothing"() {
        given:
        def c = mock(0xB5)
        def chip = new Apds9250Full(c)
        int before = c.writes().size()

        when:
        action(chip)

        then:
        thrown(IllegalArgumentException)
        c.writes().size() == before

        where:
        action << [
            { Apds9250Full x -> x.setGain(2) },
            { Apds9250Full x -> x.setResolution(12) },
            { Apds9250Full x -> x.setMeasurementRate(30) },
            { Apds9250Full x -> x.setPersistence(0) },
            { Apds9250Full x -> x.setPersistence(17) },
            { Apds9250Full x -> x.setVariance(12) },
            { Apds9250Full x -> x.setInterruptChannel(4) },
            { Apds9250Full x -> x.setThresholds(5, 4) },
            { Apds9250Full x -> x.setThresholds(0, 0x100000) }
        ]
    }

    def "mode enable disable and reset"() {
        given:
        def c = mock(0xB5)
        def chip = new Apds9250Full(c)
        c.setRegister(0x00, 0x06)

        when:
        chip.setMode(false)
        then:
        reg(c, 0x00) == 0x02

        when:
        chip.setMode(true)
        then:
        reg(c, 0x00) == 0x06

        when:
        chip.disable()
        then:
        reg(c, 0x00) == 0x04

        when:
        chip.enable()
        then:
        reg(c, 0x00) == 0x06

        when:
        chip.softReset()
        def last = c.writes().last()
        then:
        last.length == 2
        last[1] == 0x10
        chip.partId() == [0xB, 0x5] as int[]
    }

    def "thresholds use three bytes each"() {
        given:
        def c = mock(0xB5)
        def chip = new Apds9250Full(c)

        when:
        chip.setThresholds(0x12345, 0xABCDE)

        then:
        [reg(c, 0x24), reg(c, 0x25), reg(c, 0x26)] == [0x45, 0x23, 0x01]
        [reg(c, 0x21), reg(c, 0x22), reg(c, 0x23)] == [0xDE, 0xBC, 0x0A]
        chip.getThresholds() == [0x12345, 0xABCDE] as int[]
    }

    def "interrupt configuration"() {
        given:
        def c = mock(0xB5)
        def chip = new Apds9250Full(c)

        when:
        chip.setInterruptChannel(Apds9250Full.CHANNEL_RED)
        then:
        reg(c, 0x19) == 0x20

        when:
        chip.setInterruptChannel(Apds9250Full.CHANNEL_BLUE)
        chip.enableInterrupt(Apds9250Full.SOURCE_VARIATION)
        then:
        reg(c, 0x19) == 0x3C

        when:
        chip.enableInterrupt(Apds9250Full.SOURCE_THRESHOLD)
        then:
        reg(c, 0x19) == 0x34

        when:
        chip.disableInterrupt()
        then:
        reg(c, 0x19) == 0x30

        when:
        chip.setPersistence(16)
        chip.setVariance(1024)
        then:
        reg(c, 0x1A) == 0xF0
        reg(c, 0x27) == 7
    }

    def "dataReady keeps the interrupt bit for pollInterrupt"() {
        given:
        def c = mock(0xB5)
        def chip = new Apds9250Full(c)

        when:
        c.setRegister(0x07, 0x18)
        then:
        chip.dataReady()

        when:
        c.setRegister(0x07, 0x00)
        then:
        !chip.dataReady()
        chip.pollInterrupt() == 0x10
        chip.pollInterrupt() == 0x00

        when:
        c.setRegister(0x07, 0x20)
        then:
        chip.status() == 0x20
    }

    def "callback receives the status byte"() {
        given:
        def c = mock(0xB5)
        def chip = new Apds9250Full(c)
        def calls = new AtomicInteger()
        def last = new AtomicInteger()
        c.setRegister(0x07, 0x10)

        when:
        chip.onInterrupt({ int s -> calls.incrementAndGet(); last.set(s) } as java.util.function.IntConsumer)
        long end = System.currentTimeMillis() + 2000
        while (calls.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(10)
        c.setRegister(0x07, 0x00)
        chip.offInterrupt()

        then:
        calls.get() >= 1
        last.get() == 0x10
    }
}
