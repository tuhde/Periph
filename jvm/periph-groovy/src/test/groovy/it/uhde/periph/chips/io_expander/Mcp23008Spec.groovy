package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicInteger

class Mcp23008Spec extends Specification {

    static final int REG_IODIR = 0x00, REG_IPOL = 0x01, REG_GPINTEN = 0x02, REG_DEFVAL = 0x03
    static final int REG_IOCON = 0x05, REG_GPPU = 0x06, REG_INTF = 0x07, REG_INTCAP = 0x08
    static final int REG_GPIO = 0x09, REG_OLAT = 0x0A

    /**
     * The MCP23S08 read header is [opcode, reg]; MockConnection treats the first
     * byte as the register, so resolve reads from the second byte instead.
     */
    static class SpiMock extends MockConnection {
        @Override
        byte[] writeRead(byte[] data, int n) {
            writes().add((byte[]) data.clone())
            byte[] out = new byte[n]
            for (int i = 0; i < n; i++) {
                Integer v = registers().get((data[1] & 0xFF) + i)
                out[i] = (byte) (v == null ? 0 : v)
            }
            return out
        }
    }

    static byte[] b(int... v) {
        byte[] r = new byte[v.length]
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i]
        return r
    }

    def "I2C init, port and pin access"() {
        given:
        def c = new MockConnection()

        when:
        def chip = new Mcp23008Full(c, 0x20)

        then: "OLAT cleared, IODIR untouched"
        c.registers().get(REG_OLAT) == 0x00
        chip.shadow == 0x00
        chip.direction == 0xFF
        c.writes().every { (it[0] & 0xFF) != REG_IODIR }

        when:
        c.setRegister(REG_GPIO, 0xA5)

        then:
        chip.readPort(0) == 0xA5
        chip.readPort(1) == 0xA5

        when:
        chip.writePort(0, 0x3C)

        then:
        c.registers().get(REG_OLAT) == 0x3C
        chip.shadow == 0x3C

        when: "GP7 is a normal bidirectional pin"
        chip.pin(7).setOutput()

        then:
        c.registers().get(REG_IODIR) == 0x7F

        when:
        chip.pin(7).setInput()

        then:
        c.registers().get(REG_IODIR) == 0xFF
    }

    def "OLAT is written before IODIR when a pin becomes an output"() {
        given:
        def c = new MockConnection()
        def chip = new Mcp23008Full(c, 0x20)
        chip.writePort(0, 0x00)
        int before = c.writes().size()

        when:
        chip.pin(1).setOutput()

        then:
        c.writes().size() == before + 2
        (c.writes().get(before)[0] & 0xFF) == REG_OLAT
        (c.writes().get(before + 1)[0] & 0xFF) == REG_IODIR
    }

    def "pin set, clear, toggle and OutputPin contract"() {
        given:
        def c = new MockConnection()
        def chip = new Mcp23008Full(c, 0x20)
        c.setRegister(REG_GPIO, 0x04)

        expect:
        chip.pin(2).read()

        when:
        chip.pin(0).setHigh()

        then:
        c.registers().get(REG_OLAT) == 0x01

        when:
        chip.pin(2).setHigh()

        then:
        c.registers().get(REG_OLAT) == 0x05

        when:
        chip.pin(0).setLow()

        then:
        c.registers().get(REG_OLAT) == 0x04

        when:
        chip.pin(0).toggle()

        then:
        c.registers().get(REG_OLAT) == 0x05

        when:
        chip.pin(0).toggle()

        then:
        c.registers().get(REG_OLAT) == 0x04

        when:
        chip.pin(3).set(true)

        then:
        c.registers().get(REG_OLAT) == 0x0C
    }

    def "Full configuration registers"() {
        given:
        def c = new MockConnection()
        def chip = new Mcp23008Full(c, 0x20)

        when:
        chip.configurePullup(0xF0)

        then:
        c.registers().get(REG_GPPU) == 0xF0

        when:
        chip.pin(0).setPullUp(true)

        then:
        c.registers().get(REG_GPPU) == 0xF1

        when:
        chip.configurePolarity(0x0F)
        chip.setDefaultValue(0x11)
        chip.enablePinInterrupt(0xF0)

        then:
        c.registers().get(REG_IPOL) == 0x0F
        c.registers().get(REG_DEFVAL) == 0x11
        c.registers().get(REG_GPINTEN) == 0xF0

        when:
        chip.configureIntPin(false, true)

        then:
        c.registers().get(REG_IOCON) == 0x02

        when:
        chip.configureIntPin(true, true)

        then:
        c.registers().get(REG_IOCON) == 0x04

        when:
        chip.configureIntPin()
        chip.setSequential(false)

        then:
        c.registers().get(REG_IOCON) == 0x20

        when:
        chip.setSequential(true)

        then:
        c.registers().get(REG_IOCON) == 0x00
    }

    def "interrupt status, capture, raw access and reset"() {
        given:
        def c = new MockConnection()
        def chip = new Mcp23008Full(c, 0x20)
        c.setRegister(REG_INTF, 0x10)
        c.setRegister(REG_INTCAP, 0xFF)

        expect:
        chip.pollInterrupt() == 0x10

        when:
        c.setRegister(REG_INTCAP, 0x22)

        then:
        chip.readCapture() == 0x22

        when:
        chip.writeRegister(REG_IODIR, 0x0F)
        chip.writeRegister(REG_OLAT, 0x81)

        then:
        chip.direction == 0x0F
        chip.shadow == 0x81
        chip.readRegister(REG_OLAT) == 0x81

        when:
        chip.configurePullup(0xF0)
        chip.reset()

        then:
        c.registers().get(REG_IODIR) == 0xFF
        c.registers().get(REG_OLAT) == 0x00
        c.registers().get(REG_GPPU) == 0x00
        c.registers().get(REG_GPINTEN) == 0x00
        chip.shadow == 0x00
        chip.direction == 0xFF
    }

    def "watch and onInterrupt deliver by polling"() {
        given:
        def wc = new MockConnection()
        def chip = new Mcp23008Full(wc, 0x20)
        def status = new AtomicInteger(-1)
        def watched = new AtomicInteger()

        when:
        chip.pin(4).watch({ p -> watched.incrementAndGet() } as java.util.function.Consumer, EdgeTrigger.FALLING)
        wc.setRegister(REG_INTF, 0x10)
        wc.setRegister(REG_INTCAP, 0x00)
        chip.onInterrupt({ int s -> status.set(s) } as java.util.function.IntConsumer)
        Thread.sleep(60)
        chip.offInterrupt()

        then:
        wc.registers().get(REG_GPINTEN) == 0x10
        status.get() == 0x10
        watched.get() >= 1

        when:
        chip.pin(4).unwatch()

        then:
        wc.registers().get(REG_GPINTEN) == 0x00
    }

    def "error cases"() {
        given:
        def chip = new Mcp23008Full(new MockConnection(), 0x20)

        when:
        chip.onInterrupt({ int s -> } as java.util.function.IntConsumer)

        then:
        thrown(IllegalStateException)

        when:
        chip.pin(0).setOutput()
        chip.pin(0).watch({ p -> } as java.util.function.Consumer)

        then:
        thrown(IllegalStateException)
    }

    def "SPI framing and HAEN"() {
        given:
        def c = new SpiMock()

        when:
        def chip = new Mcp23S08Full(c, 2)

        then: "IOCON <- HAEN under the address-0 opcode, then OLAT <- 0 under the real opcode"
        c.writes().get(0) == b(0x40, 0x05, 0x08)
        c.writes().get(1) == b(0x44, 0x0A, 0x00)

        when:
        chip.writePort(0, 0x5A)

        then:
        c.writes().last() == b(0x44, 0x0A, 0x5A)

        when:
        c.setRegister(REG_GPIO, 0xC3)
        int v = chip.readPort(0)

        then:
        v == 0xC3
        c.writes().last() == b(0x45, 0x09)

        when:
        chip.configurePullup(0x0F)

        then:
        c.writes().last() == b(0x44, 0x06, 0x0F)

        when:
        chip.configureIntPin(true, false)

        then: "HAEN is preserved"
        c.writes().last() == b(0x44, 0x05, 0x0C)

        when:
        chip.reset()

        then:
        c.writes().any { it == b(0x44, 0x05, 0x08) }
        new Mcp23S08Minimal(new SpiMock(), 0).readPort(0) == 0
        new Mcp23008Minimal(new MockConnection(), 0x20).readPort(0) == 0
    }
}
