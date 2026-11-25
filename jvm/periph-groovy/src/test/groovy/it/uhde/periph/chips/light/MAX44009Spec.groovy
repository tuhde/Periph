package it.uhde.periph.chips.light

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicInteger

class MAX44009Spec extends Specification {

    def setupSpec() {
        MAX44009Minimal.initWaitMs = 0   // skip the 900 ms first-conversion wait
    }

    static MockConnection mock(int high, int low) {
        def c = new MockConnection()
        c.setRegister(0x02, 0x03)
        c.setRegister(0x03, high)
        c.setRegister(0x04, low)
        return c
    }

    static int reg(MockConnection c, int r) { c.registers().get(r) }

    def "init writes the reset state and reads INT_STATUS"() {
        given:
        def c = mock(0, 0)

        when:
        new MAX44009Full(c)
        def seq = c.writes().findAll { it.length > 1 }.collect { [it[0] & 0xFF, it[1] & 0xFF] }

        then:
        seq == [[1, 0], [2, 3], [5, 255], [6, 0], [7, 255]]
        c.writes().any { it.length == 1 && it[0] == 0 }
    }

    def "lux conversion"() {
        given:
        def c = mock(0, 0)
        def chip = new MAX44009Full(c)

        expect:
        chip.lux() == 0.0d

        when:
        c.setRegister(0x03, 0x2A)
        c.setRegister(0x04, 0x05)
        then:
        Math.abs(chip.lux() - 4 * 0xA5 * 0.045d) < 1e-9

        when:
        c.setRegister(0x03, 0xEF)
        c.setRegister(0x04, 0x0F)
        then:
        Math.abs(chip.lux() - 188006.4d) < 1e-6

        when:
        c.setRegister(0x03, 0xF3)
        then:
        Math.abs(chip.lux() - 188006.4d) < 1e-6

        when:
        c.setRegister(0x03, 0x01)
        c.setRegister(0x04, 0xF7)
        then:
        Math.abs(chip.lux() - 0x17 * 0.045d) < 1e-9
    }

    def "raw reading, overrange and resolution"() {
        given:
        def c = mock(0x5B, 0x0C)
        def chip = new MAX44009Full(c)

        when:
        def raw = chip.readRaw()

        then:
        raw.exponent == 5
        raw.mantissa == 0xBC
        !raw.overrange
        Math.abs(chip.resolution() - 32 * 0.045d) < 1e-9
        !chip.isOverrange()

        when:
        c.setRegister(0x03, 0xF0)

        then:
        chip.readRaw().overrange
        chip.isOverrange()
    }

    def "configuration"() {
        given:
        def c = mock(0, 0)
        def chip = new MAX44009Full(c)

        when:
        chip.setContinuous(true)
        then:
        reg(c, 2) == 0x83

        when:
        chip.setContinuous(false)
        then:
        reg(c, 2) == 0x03

        when:
        chip.setManual(50.0d)
        then:
        reg(c, 2) == 0x44

        when:
        chip.setManual(12.0d, true)
        then:
        reg(c, 2) == (0x40 | 0x08 | 0x06)

        when:
        chip.setContinuous(true)
        chip.setManual(1000.0d)
        then:
        reg(c, 2) == 0xC0

        when:
        chip.setAutomatic()
        then:
        (reg(c, 2) & 0x40) == 0
        (reg(c, 2) & 0x80) != 0

        when:
        c.setRegister(0x02, 0xCD)
        def cfg = chip.config
        then:
        cfg.continuous && cfg.manual && cfg.divideBy8
        cfg.integrationMs == 25.0d
    }

    def "thresholds and delay"() {
        given:
        def c = mock(0, 0)
        def chip = new MAX44009Full(c)

        when:
        chip.setThresholds(0.0d, 188006.4d)
        then:
        reg(c, 6) == 0x00
        reg(c, 5) == 0xEF

        when:
        chip.setThresholds(5.0d, 100.0d)
        def w = chip.thresholds
        then:
        reg(c, 6) == 0x07
        (reg(c, 5) >> 4) == 4
        (reg(c, 5) & 0x08) != 0
        Math.abs(w[0] - 5.0d) < 0.5d
        Math.abs(w[1] - 100.0d) < 13.0d
        Math.abs(MAX44009Full.decodeThreshold(0x25, true) - 4 * 0x5F * 0.045d) < 1e-9
        Math.abs(MAX44009Full.decodeThreshold(0x25, false) - 4 * 0x50 * 0.045d) < 1e-9

        when:
        chip.setThresholds(10.0d, 5.0d)
        then:
        thrown(IllegalArgumentException)

        when:
        chip.setThresholdDelay(2.0d)
        then:
        reg(c, 7) == 20
        Math.abs(chip.thresholdDelay - 2.0d) < 1e-9

        when:
        chip.setThresholdDelay(99.0d)
        then:
        reg(c, 7) == 255

        when:
        chip.setThresholdDelay(-1.0d)
        then:
        reg(c, 7) == 0
    }

    def "interrupt enable, status and callback delivery"() {
        given:
        def c = mock(0, 0)
        def chip = new MAX44009Full(c)

        when:
        chip.enableInterrupt()
        then:
        reg(c, 1) == 0x01

        when:
        chip.disableInterrupt()
        c.setRegister(0x00, 0x01)
        then:
        reg(c, 1) == 0x00
        chip.pollInterrupt() == 1

        when:
        c.setRegister(0x00, 0x00)
        then:
        chip.pollInterrupt() == 0

        when:
        def calls = new AtomicInteger()
        def last = new AtomicInteger()
        c.setRegister(0x00, 0x01)
        chip.onInterrupt({ int s -> calls.incrementAndGet(); last.set(s) } as java.util.function.IntConsumer)
        long end = System.currentTimeMillis() + 2000
        while (calls.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(10)
        c.setRegister(0x00, 0x00)
        chip.offInterrupt()

        then:
        calls.get() >= 1
        last.get() == 1
    }
}
