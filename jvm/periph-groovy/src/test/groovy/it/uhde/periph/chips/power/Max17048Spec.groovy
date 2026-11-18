package it.uhde.periph.chips.power

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Max17048Spec extends Specification {

    private static List<byte[]> words(MockConnection c) {
        c.writes().findAll { byte[] w -> w.length == 3 }
    }

    private static boolean lastIs(MockConnection c, int reg, int msb, int lsb) {
        byte[] g = words(c).last()
        (g[0] & 0xFF) == reg && (g[1] & 0xFF) == msb && (g[2] & 0xFF) == lsb
    }

    def "construction makes no bus access"() {
        when:
        def c = new MockConnection()
        new Max17048Full(c)

        then:
        c.writes().isEmpty()
    }

    def "voltage, percent and raw registers"() {
        given:
        def c = new MockConnection()
        def g = new Max17048Full(c)

        when:
        c.setRegister(0x02, 0xC3, 0x50)
        c.setRegister(0x04, 0x4B, 0x80)

        then:
        Math.abs(g.voltage() - 3.90625d) < 1e-9
        Math.abs(new Max17048Minimal(c, 2).voltage() - 7.8125d) < 1e-9
        g.rawVoltage() == 0xC350
        Math.abs(g.percent() - 75.5d) < 1e-9
        g.rawSoc() == 0x4B80

        when:
        c.setRegister(0x04, 0xFF, 0xFF)

        then:
        g.percent() == 100.0d
    }

    def "charge rate, version and id"() {
        given:
        def c = new MockConnection()
        def g = new Max17048Full(c)

        when:
        c.setRegister(0x16, 0xFF, 0xEC)

        then:
        Math.abs(g.chargeRate() - (-4.16d)) < 1e-9

        when:
        c.setRegister(0x16, 0x00, 0x64)
        c.setRegister(0x08, 0x00, 0x12)
        c.setRegister(0x18, 0x96, 0xAB)

        then:
        Math.abs(g.chargeRate() - 20.8d) < 1e-9
        g.version() == 0x12
        g.chipId() == 0xAB
    }

    def "RCOMP and temperature compensation"() {
        given:
        def c = new MockConnection()
        def g = new Max17048Full(c)
        c.setRegister(0x0C, 0x97, 0x1C)

        expect:
        g.getRcomp() == 0x97

        when:
        g.setRcomp(0x80)

        then:
        lastIs(c, 0x0C, 0x80, 0x1C)

        when:
        c.setRegister(0x0C, 0x97, 0x1C)
        g.compensateTemperature(35.0d)

        then:
        lastIs(c, 0x0C, 0x90, 0x1C)

        when:
        g.compensateTemperature(5.0d)

        then:
        lastIs(c, 0x0C, 0xE2, 0x1C)
    }

    def "alert thresholds"() {
        given:
        def c = new MockConnection()
        def g = new Max17048Full(c)
        c.setRegister(0x0C, 0x97, 0x1C)

        when:
        g.setAlertSoc(10)

        then:
        lastIs(c, 0x0C, 0x97, 0x16)
        g.getAlertSoc() == 10

        when:
        g.setSocChangeAlert(true)

        then:
        lastIs(c, 0x0C, 0x97, 0x56)

        when:
        g.setSocChangeAlert(false)

        then:
        lastIs(c, 0x0C, 0x97, 0x16)

        when:
        g.setVoltageAlert(3.3d, 4.2d)

        then:
        lastIs(c, 0x14, 0xA5, 0xD2)
        Math.abs(g.getVoltageAlert()[0] - 3.3d) < 1e-9
        Math.abs(g.getVoltageAlert()[1] - 4.2d) < 1e-9

        when:
        g.setVoltageAlert(0.0d, 5.1d)

        then:
        lastIs(c, 0x14, 0x00, 0xFF)

        when:
        g.setVoltageAlert(4.0d, 3.0d)

        then:
        thrown(IllegalArgumentException)
    }

    def "reset voltage and comparator"() {
        given:
        def c = new MockConnection()
        def g = new Max17048Full(c)
        c.setRegister(0x18, 0x01, 0x5A)

        when:
        g.setResetVoltage(3.0d)

        then:
        lastIs(c, 0x18, 0x97, 0x5A)
        Math.abs(g.getResetVoltage() - 3.0d) < 1e-9

        when:
        g.setComparatorDisabled(false)

        then:
        lastIs(c, 0x18, 0x96, 0x5A)

        when:
        g.setComparatorDisabled(true)

        then:
        lastIs(c, 0x18, 0x97, 0x5A)

        when:
        c.setRegister(0x1A, 0x01, 0x00)
        g.enableVoltageResetAlert(true)

        then:
        lastIs(c, 0x1A, 0x41, 0x00)
    }

    def "hibernate, sleep and quick start"() {
        given:
        def c = new MockConnection()
        def g = new Max17048Full(c)

        when:
        g.setHibernateThresholds(26.624d, 0.06d)

        then:
        lastIs(c, 0x0A, 0x80, 0x30)
        Math.abs(g.getHibernateThresholds()[0] - 26.624d) < 1e-9
        Math.abs(g.getHibernateThresholds()[1] - 0.06d) < 1e-9

        when:
        g.disableHibernate()

        then:
        lastIs(c, 0x0A, 0x00, 0x00)

        when:
        g.forceHibernate()

        then:
        lastIs(c, 0x0A, 0xFF, 0xFF)

        when:
        c.setRegister(0x06, 0x10, 0x00)

        then:
        g.isHibernating()

        when:
        c.setRegister(0x06, 0x00, 0x00)

        then:
        !g.isHibernating()

        when:
        g.enableSleep(true)

        then:
        lastIs(c, 0x06, 0x20, 0x00)

        when:
        g.quickStart()

        then:
        lastIs(c, 0x06, 0x60, 0x00)

        when:
        g.enableSleep(false)
        g.quickStart()

        then:
        lastIs(c, 0x06, 0x40, 0x00)

        when:
        c.setRegister(0x0C, 0x97, 0x1C)
        g.sleep()

        then:
        lastIs(c, 0x0C, 0x97, 0x9C)

        when:
        g.wake()

        then:
        lastIs(c, 0x0C, 0x97, 0x1C)
    }

    def "status, config and interrupts"() {
        given:
        def c = new MockConnection()
        def g = new Max17048Full(c)

        when:
        c.setRegister(0x1A, 0x11, 0x00)
        c.setRegister(0x0C, 0x97, 0x3C)

        then:
        g.readStatus() == 0x1100
        g.readConfig() == 0x973C

        when:
        g.clearStatus(0x0100)

        then:
        c.registers().get(0x1A) == 0x10
        c.registers().get(0x0D) == 0x1C

        when:
        c.setRegister(0x1A, 0x51, 0x00)
        c.setRegister(0x0C, 0x97, 0x3C)

        then:
        g.pollInterrupt() == 0x1100
        c.registers().get(0x1A) == 0x40
        c.registers().get(0x0D) == 0x1C
        g.pollInterrupt() == 0

        when:
        c.setRegister(0x0C, 0x97, 0x1C)
        g.enableInterrupt(Max17048Full.SOURCE_SOC_CHANGE)

        then:
        lastIs(c, 0x0C, 0x97, 0x5C)

        when:
        g.disableInterrupt(Max17048Full.SOURCE_SOC_CHANGE)

        then:
        lastIs(c, 0x0C, 0x97, 0x1C)

        when:
        c.setRegister(0x14, 0xA5, 0xD2)
        g.disableInterrupt(Max17048Full.SOURCE_UNDERVOLTAGE)

        then:
        lastIs(c, 0x14, 0x00, 0xD2)

        when:
        g.disableInterrupt(Max17048Full.SOURCE_OVERVOLTAGE)

        then:
        lastIs(c, 0x14, 0x00, 0xFF)

        when:
        g.writeConfig(0x9700)

        then:
        lastIs(c, 0x0C, 0x97, 0x00)
    }

    def "reset tolerates a missing ACK"() {
        given:
        def c = new MockConnection()
        def g = new Max17048Full(c)
        c.failNextWrites(1, 0)

        when:
        g.reset()

        then:
        lastIs(c, 0xFE, 0x54, 0x00)
    }
}
