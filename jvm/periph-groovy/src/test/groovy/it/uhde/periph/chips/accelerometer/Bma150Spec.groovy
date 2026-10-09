package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Bma150Spec extends Specification {

    def newConnection() {
        def c = new MockConnection()
        c.setRegister(Bma150Minimal.REG_CHIP_ID, 0x02)
        c.setRegister(Bma150Minimal.REG_RANGE_BW, 0x00)
        c.setRegister(Bma150Minimal.REG_ACC_X_LSB, 0x00, 0x80)
        c.setRegister(Bma150Minimal.REG_ACC_Y_LSB, 0x00, 0x00)
        c.setRegister(Bma150Minimal.REG_ACC_Z_LSB, 0x00, 0x40)
        return c
    }

    def lastWriteTo(MockConnection connection, int reg) {
        def writes = connection.writes()
        for (int i = writes.size() - 1; i >= 0; i--) {
            def w = writes.get(i)
            if (w.length == 2 && (w[0] & 0xFF) == reg) return w[1] & 0xFF
        }
        return -1
    }

    def "construction and read"() {
        given:
        def connection = newConnection()
        def accel = new Bma150Minimal(connection)

        when:
        def xyz = accel.read()

        then:
        lastWriteTo(connection, Bma150Minimal.REG_RANGE_BW) == 0x02
        xyz[0] == -2.0d
        xyz[1] == 0.0d
        xyz[2] == 1.0d
    }

    def "construction with bad CHIP_ID throws"() {
        given:
        def connection = new MockConnection()
        connection.setRegister(Bma150Minimal.REG_CHIP_ID, 0xFF)

        when:
        new Bma150Minimal(connection)

        then:
        thrown(IOException)
    }

    def "set range and bandwidth"() {
        given:
        def connection = newConnection()
        def full = new Bma150Full(connection)

        when:
        full.setRange(4)

        then:
        lastWriteTo(connection, Bma150Minimal.REG_RANGE_BW) == 0x0A

        when:
        connection.setRegister(Bma150Minimal.REG_RANGE_BW, 0x0A)
        full.setBandwidth(190)

        then:
        lastWriteTo(connection, Bma150Minimal.REG_RANGE_BW) == 0x0B
    }

    def "read raw"() {
        given:
        def connection = newConnection()
        def full = new Bma150Full(connection)

        when:
        def raw = full.readRaw()

        then:
        raw[0] == -512
        raw[1] == 0
        raw[2] == 256
    }

    def "read temperature"() {
        given:
        def connection = newConnection()
        def full = new Bma150Full(connection)
        connection.setRegister(Bma150Minimal.REG_TEMP, 0x40)

        when:
        def temp = full.readTemperature()

        then:
        temp == 2.0d
    }

    def "low-g and high-g"() {
        given:
        def connection = newConnection()
        def full = new Bma150Full(connection)

        when:
        full.setLowG(0.4d, 40, 0.0d, 0)
        full.setHighG(4.0d, 2, 0.0d, 0)

        then:
        lastWriteTo(connection, Bma150Minimal.REG_LG_THRES) == 51
        lastWriteTo(connection, Bma150Minimal.REG_LG_DUR) == 40
        (lastWriteTo(connection, Bma150Minimal.REG_INT_CTRL) & 0x01) != 0
        lastWriteTo(connection, Bma150Minimal.REG_HG_THRES) == 255
        lastWriteTo(connection, Bma150Minimal.REG_HG_DUR) == 2
        (lastWriteTo(connection, Bma150Minimal.REG_INT_CTRL) & 0x02) != 0
    }

    def "any motion"() {
        given:
        def connection = newConnection()
        def full = new Bma150Full(connection)

        when:
        full.setAnyMotion(0.5d, 3)

        then:
        lastWriteTo(connection, Bma150Minimal.REG_ANY_MOTION_THRES) == 32
        (lastWriteTo(connection, Bma150Minimal.REG_HYST_DUR) & 0xC0) == 0x40
        (lastWriteTo(connection, Bma150Minimal.REG_CONFIG) & 0x40) != 0
        (lastWriteTo(connection, Bma150Minimal.REG_INT_CTRL) & 0x40) != 0
    }

    def "latch and clear interrupt"() {
        given:
        def connection = newConnection()
        def full = new Bma150Full(connection)

        when:
        full.setLatch(true)
        full.clearInterrupt()

        then:
        (lastWriteTo(connection, Bma150Minimal.REG_CONFIG) & 0x10) != 0
        (lastWriteTo(connection, Bma150Minimal.REG_CTRL) & 0x40) != 0
    }

    def "sleep and wake"() {
        given:
        def connection = newConnection()
        def full = new Bma150Full(connection)

        when:
        full.sleep()
        full.wake()

        then:
        (lastWriteTo(connection, Bma150Minimal.REG_CTRL) & 0x01) != 0
        (lastWriteTo(connection, Bma150Minimal.REG_CTRL) & 0x01) == 0
    }

    def "soft reset"() {
        given:
        def connection = newConnection()
        def full = new Bma150Full(connection)

        when:
        full.softReset()

        then:
        (lastWriteTo(connection, Bma150Minimal.REG_CTRL) & 0x02) != 0
    }

    def "self test passes on st_result set"() {
        given:
        def connection = newConnection()
        def full = new Bma150Full(connection)
        connection.setRegister(Bma150Minimal.REG_STATUS, 0x80)

        expect:
        full.selfTest()
    }

    def "version and customer"() {
        given:
        def connection = newConnection()
        def full = new Bma150Full(connection)
        connection.setRegister(Bma150Minimal.REG_VERSION, 0xAB)

        when:
        def v = full.readVersion()
        full.writeCustomer(1, 0x5A)

        then:
        v[0] == 0x0A
        v[1] == 0x0B
        lastWriteTo(connection, Bma150Minimal.REG_CUSTOMER_2) == 0x5A
    }
}
