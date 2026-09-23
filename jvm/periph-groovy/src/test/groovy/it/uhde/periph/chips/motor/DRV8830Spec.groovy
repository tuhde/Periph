package it.uhde.periph.chips.motor

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class DRV8830Spec extends Specification {

    private static int lastWrite(MockConnection conn, int reg) {
        def w = conn.writes().findAll { byte[] b -> b.length == 2 && (b[0] & 0xFF) == reg }
        return w ? (w.last()[1] & 0xFF) : -1
    }

    def "init makes no register writes"() {
        given:
        def conn = new MockConnection()

        when:
        new DRV8830Minimal(conn)

        then:
        conn.writes().size() == 1
        conn.writes()[0].length == 1
    }

    def "drive converts voltage and direction"() {
        given:
        def conn = new MockConnection()
        def motor = new DRV8830Minimal(conn)

        when:
        motor.drive(voltage)

        then:
        lastWrite(conn, 0x00) == expected

        where:
        voltage || expected
        3.0d    || 0x95  // VSET 37, forward
        -2.0d   || 0x66  // VSET 25, reverse
        0.0d    || 0x00  // coast
        0.4d    || 0x00  // below the floor -> coast
        0.48d   || 0x19  // VSET 6, forward
        9.0d    || 0xFD  // clamped to VSET 63, forward
    }

    def "brake and stop"() {
        given:
        def conn = new MockConnection()
        def motor = new DRV8830Minimal(conn)

        when:
        motor.brake()
        then:
        lastWrite(conn, 0x00) == 0x03

        when:
        motor.stop()
        then:
        lastWrite(conn, 0x00) == 0x00
    }

    def "setOutput writes raw fields and rejects reserved codes"() {
        given:
        def conn = new MockConnection()
        def motor = new DRV8830Full(conn)

        when:
        motor.setOutput(20, false, true)
        then:
        lastWrite(conn, 0x00) == ((20 << 2) | 0x02)

        when:
        motor.setOutput(5, true, false)
        then:
        thrown(IllegalArgumentException)
    }

    def "readOutput decodes CONTROL"() {
        given:
        def conn = new MockConnection()
        def motor = new DRV8830Full(conn)

        when:
        conn.setRegister(0x00, (63 << 2) | 0x01)
        def out = motor.readOutput()
        then:
        out.direction == DRV8830Full.Direction.FORWARD
        Math.abs(out.voltage - 5.06) < 0.01

        when:
        conn.setRegister(0x00, 0x03)
        out = motor.readOutput()
        then:
        out.direction == DRV8830Full.Direction.BRAKE
        out.voltage == 0.0d
    }

    def "faults are reported, not cleared until asked"() {
        given:
        def conn = new MockConnection()
        conn.setRegister(0x01, 0x11)
        def motor = new DRV8830Full(conn)

        when:
        def f = motor.readFault()
        then:
        f.fault && f.ilimit && !f.ocp && !f.uvlo && !f.ots
        lastWrite(conn, 0x01) == -1

        when:
        motor.clearFault()
        then:
        lastWrite(conn, 0x01) == 0x80
    }
}
