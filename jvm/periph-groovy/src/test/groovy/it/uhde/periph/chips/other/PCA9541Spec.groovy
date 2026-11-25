package it.uhde.periph.chips.other

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicInteger

class PCA9541Spec extends Specification {

    /** Recomputes the read-only N bits of CONTROL after a write (this master's view). */
    static class SimMock extends MockConnection {
        @Override
        void write(byte[] data) {
            int prior = registers().getOrDefault(0x01, 0)
            super.write(data)
            if (data.length == 2 && data[0] == 0x01) {
                int w = data[1] & 0xFF
                int mybus = w & 1, buson = (w >> 2) & 1
                int nmybusPrior = (prior >> 1) & 1, nbusonPrior = (prior >> 3) & 1
                boolean control = mybus == nmybusPrior
                boolean on = buson != nbusonPrior
                int nmybus = control ? mybus : 1 - mybus
                int nbuson = on ? 1 - buson : buson
                setRegister(0x01, (w & 0xF5) | (nmybus << 1) | (nbuson << 3))
            }
        }
    }

    static MockConnection mock(int control, int ie = 0, int istat = 0) {
        def c = new SimMock()
        c.setRegister(0x00, ie); c.setRegister(0x01, control); c.setRegister(0x02, istat)
        c.setRegister(0x10, ie); c.setRegister(0x11, control); c.setRegister(0x12, istat)
        return c
    }

    static int reg(MockConnection c, int r) { c.registers().get(r) }
    static List<byte[]> dataWrites(MockConnection c) { c.writes().findAll { it.length > 1 } }
    static int lastWrite(MockConnection c) { dataWrites(c).last()[1] & 0xFF }

    def "init reads CONTROL and ISTAT and writes nothing"() {
        when:
        def c = mock(0x04)
        new PCA9541Full(c)

        then:
        dataWrites(c).isEmpty()
        c.writes().collect { it[0] as int } == [1, 2]

        when:
        def bad = mock(0)
        bad.failNextWrites(1, 0)
        new PCA9541Minimal(bad)

        then:
        thrown(IOException)
    }

    def "acquire follows Table 12"() {
        expect:
        {
            def c = mock(read)
            new PCA9541Full(c).acquire(false, 0)
            lastWrite(c) == want
        }()

        where:
        read | want
        0x0  | 0x4
        0x1  | 0x4
        0x2  | 0x5
        0x3  | 0x5
        0x5  | 0x4
        0x6  | 0x5
        0x9  | 0x0
        0xA  | 0x1
        0xC  | 0x0
        0xD  | 0x0
        0xE  | 0x1
        0xF  | 0x1
    }

    def "already in control keeps control and hasControl decodes the bits"() {
        expect:
        new PCA9541Full(mock(read)).acquire(false, 0)
        new PCA9541Full(mock(read)).hasControl()

        where:
        read << [0x4, 0x7, 0x8, 0xB]
    }

    def "hasControl values and bus initialization"() {
        expect:
        new PCA9541Full(mock(0x04)).hasControl()
        !new PCA9541Full(mock(0x0A)).hasControl()
        !new PCA9541Full(mock(0x05)).hasControl()

        when:
        def c = mock(0x0A, 0, 0x02)
        def ok = new PCA9541Full(c).acquire(true, 20)

        then:
        ok
        lastWrite(c) == 0x11
        !new PCA9541Full(mock(0x0A, 0, 0x00)).acquire(true, 3)

        when:
        def r = mock(0x0A, 0, 0x02)
        def rec = new PCA9541Full(r).recoverBus(20)

        then:
        rec
        (lastWrite(r) & 0x10) != 0
    }

    def "release, connect and disconnect keep ownership"() {
        given:
        def c = mock(0x04)
        def chip = new PCA9541Full(c)

        when:
        chip.release()
        then:
        lastWrite(c) == 0x05
        !chip.hasControl()

        when:
        def c2 = mock(0x0D)
        new PCA9541Full(c2).connect()
        then:
        lastWrite(c2) == 0x01

        when:
        def c3 = mock(0x04)
        def chip3 = new PCA9541Full(c3)
        chip3.disconnect()
        then:
        lastWrite(c3) == 0x00
        !chip3.connected

        when:
        chip3.connect()
        then:
        chip3.connected
    }

    def "status and readAll"() {
        when:
        def chip = new PCA9541Full(mock(0xD4))
        def st = chip.status()

        then:
        chip.control == 0xD4
        st.hasControl && st.connected && st.busInitRequested && st.ownTest && st.otherTest

        when:
        def c = mock(0x04, 0x03, 0x08)
        def chip2 = new PCA9541Full(c)
        int n = c.writes().size()
        def all = chip2.readAll()

        then:
        all == [0x03, 0x04, 0x08] as int[]
        c.writes().size() == n + 1
        c.writes().last()[0] == 0x10
    }

    def "interrupt mask, sources and the INT test"() {
        given:
        def c = mock(0x0D)
        def chip = new PCA9541Full(c)

        when:
        chip.setInterruptMask(true, false, false, true)
        then:
        chip.interruptMask == 0x09

        when:
        chip.setInterruptMask()
        chip.disableInterrupt(PCA9541Full.SOURCE_BUS_INIT)
        then:
        reg(c, 0) == 0x02

        when:
        chip.disableInterrupt(PCA9541Full.SOURCE_INT_IN)
        chip.enableInterrupt(PCA9541Full.SOURCE_BUS_INIT)
        int n = dataWrites(c).size()
        chip.enableInterrupt(PCA9541Full.SOURCE_TEST)
        then:
        reg(c, 0) == 0x01
        dataWrites(c).size() == n

        when:
        int bits = reg(c, 1) & 0x05
        chip.testInterrupt(true, false)
        then:
        (reg(c, 1) & 0xC5) == (bits | 0x40)

        when:
        chip.testInterrupt(false, true)
        then:
        (reg(c, 1) & 0xC0) == 0x80

        when:
        chip.testInterrupt(false, false)
        then:
        (reg(c, 1) & 0xC0) == 0
        (reg(c, 1) & 0x05) == bits
    }

    def "pollInterrupt and callback delivery"() {
        given:
        def c = mock(0x04, 0, 0x0A)
        def chip = new PCA9541Full(c)

        expect:
        chip.pollInterrupt() == 0x0A

        when:
        def calls = new AtomicInteger()
        def last = new AtomicInteger()
        chip.onInterrupt({ int s -> calls.incrementAndGet(); last.set(s) } as java.util.function.IntConsumer)
        long end = System.currentTimeMillis() + 2000
        while (calls.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(10)
        c.setRegister(0x02, 0x00)
        chip.offInterrupt()

        then:
        calls.get() >= 1
        last.get() == 0x0A
    }
}
