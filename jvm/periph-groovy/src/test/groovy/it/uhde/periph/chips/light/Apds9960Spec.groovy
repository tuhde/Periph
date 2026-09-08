package it.uhde.periph.chips.light

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Apds9960Spec extends Specification {

    def "full API"() {
        given:
        def connection = new MockConnection()
        connection.setRegister(Apds9960Minimal.REG_ID, 0xAB)

        when:
        def sensor = new Apds9960Full(connection)

        then:
        connection.registers().get(Apds9960Minimal.REG_ATIME) == 0xB6
        connection.registers().get(Apds9960Minimal.REG_CONTROL) == 0x01
        connection.registers().get(Apds9960Minimal.REG_CONFIG2) == 0x01
        connection.registers().get(Apds9960Minimal.REG_ENABLE) == 0x03

        when: "a bad ID must reject construction"
        def badConnection = new MockConnection()
        badConnection.setRegister(Apds9960Minimal.REG_ID, 0x00)
        new Apds9960Full(badConnection)

        then:
        thrown(IOException)

        when: "RGBC burst: clear=0x1234, red=0x0102, green=0x0304, blue=0x0506 (LE)"
        connection.setRegister(Apds9960Minimal.REG_CDATAL, 0x34, 0x12, 0x02, 0x01, 0x04, 0x03, 0x06, 0x05)
        def rgbc = sensor.color()

        then:
        rgbc == [0x1234, 0x0102, 0x0304, 0x0506] as int[]
        sensor.colorClear() == 0x1234
        sensor.colorRed() == 0x0102
        sensor.colorGreen() == 0x0304
        sensor.colorBlue() == 0x0506

        when:
        sensor.enableProximity(true)

        then:
        connection.registers().get(Apds9960Minimal.REG_ENABLE) == 0x07

        when:
        sensor.enableProximity(false)

        then:
        connection.registers().get(Apds9960Minimal.REG_ENABLE) == 0x03

        when:
        connection.setRegister(Apds9960Minimal.REG_PDATA, 200)

        then:
        sensor.proximity() == 200

        when:
        sensor.enableWait(true)

        then:
        connection.registers().get(Apds9960Minimal.REG_ENABLE) == 0x0B

        when:
        sensor.enableWait(false)

        then:
        connection.registers().get(Apds9960Minimal.REG_ENABLE) == 0x03

        when:
        sensor.configureWait(100, true)

        then:
        connection.registers().get(Apds9960Minimal.REG_WTIME) == 100
        connection.registers().get(Apds9960Minimal.REG_CONFIG1) == 0x62

        when:
        sensor.configureWait(50, false)

        then:
        connection.registers().get(Apds9960Minimal.REG_CONFIG1) == 0x60

        when:
        sensor.configureAls(0xDB, 2)

        then:
        connection.registers().get(Apds9960Minimal.REG_ATIME) == 0xDB
        (connection.registers().get(Apds9960Minimal.REG_CONTROL) & 0x03) == 2

        when:
        sensor.configureProximityLed(1, 2, 10, 3)
        def ctrl = connection.registers().get(Apds9960Minimal.REG_CONTROL)

        then:
        ((ctrl >> 6) & 0x03) == 1
        ((ctrl >> 2) & 0x03) == 2
        connection.registers().get(Apds9960Minimal.REG_PPULSE) == ((3 << 6) | 10)

        when:
        sensor.setLedBoost(2)

        then:
        connection.registers().get(Apds9960Minimal.REG_CONFIG2) == ((2 << 4) | 0x01)

        when:
        sensor.alsThreshold(0x1234, 0x5678)

        then:
        connection.registers().get(Apds9960Minimal.REG_AILTL) == 0x34
        connection.registers().get(Apds9960Minimal.REG_AILTH) == 0x12
        connection.registers().get(Apds9960Minimal.REG_AIHTL) == 0x78
        connection.registers().get(Apds9960Minimal.REG_AIHTH) == 0x56

        when:
        sensor.proximityThreshold(10, 200)

        then:
        connection.registers().get(Apds9960Minimal.REG_PILT) == 10
        connection.registers().get(Apds9960Minimal.REG_PIHT) == 200

        when:
        sensor.setPersistence(5, 3)

        then:
        connection.registers().get(Apds9960Minimal.REG_PERS) == ((5 << 4) | 3)

        when:
        sensor.enableAlsInterrupt(true)
        sensor.enableProximityInterrupt(true)

        then:
        (connection.registers().get(Apds9960Minimal.REG_ENABLE) & 0x10) != 0
        (connection.registers().get(Apds9960Minimal.REG_ENABLE) & 0x20) != 0

        when:
        sensor.clearProximityInterrupt()

        then:
        connection.writes().last() == [(byte) Apds9960Minimal.REG_PICLEAR] as byte[]

        when:
        sensor.clearAlsInterrupt()

        then:
        connection.writes().last() == [(byte) Apds9960Minimal.REG_CICLEAR] as byte[]

        when:
        sensor.clearAllInterrupts()

        then:
        connection.writes().last() == [(byte) Apds9960Minimal.REG_AICLEAR] as byte[]

        when: "sign-magnitude proximity offset encoding: -50 -> 0x80|50=0xB2, 100 -> 0x64"
        sensor.setProximityOffset(-50, 100)

        then:
        connection.registers().get(Apds9960Minimal.REG_POFFSET_UR) == 0xB2
        connection.registers().get(Apds9960Minimal.REG_POFFSET_DL) == 0x64

        when:
        sensor.setProximityMask(true, false, true, false)

        then:
        connection.registers().get(Apds9960Minimal.REG_CONFIG3) == (0x08 | 0x02)

        when:
        sensor.enableGesture(true)

        then:
        (connection.registers().get(Apds9960Minimal.REG_ENABLE) & 0x40) != 0
        (connection.registers().get(Apds9960Minimal.REG_GCONF4) & 0x01) != 0

        when:
        sensor.enableGesture(false)

        then:
        (connection.registers().get(Apds9960Minimal.REG_ENABLE) & 0x40) == 0
        (connection.registers().get(Apds9960Minimal.REG_GCONF4) & 0x01) == 0

        when:
        sensor.configureGesture(1, 2, 20, 3, 5, 30, 10)

        then:
        connection.registers().get(Apds9960Minimal.REG_GPENTH) == 30
        connection.registers().get(Apds9960Minimal.REG_GEXTH) == 10
        connection.registers().get(Apds9960Minimal.REG_GCONF2) == ((1 << 5) | (2 << 3) | 5)
        connection.registers().get(Apds9960Minimal.REG_GPULSE) == ((3 << 6) | 20)

        when:
        connection.setRegister(Apds9960Minimal.REG_GSTATUS, 0x01)

        then:
        sensor.gestureAvailable()

        when:
        connection.setRegister(Apds9960Minimal.REG_GFLVL, 2)
        connection.setRegister(Apds9960Minimal.REG_GFIFO_U, 10, 20, 30, 40)
        def fifo = sensor.readGestureFifo()

        then:
        fifo.size() == 2
        fifo[0] == [10, 20, 30, 40] as int[]

        when:
        connection.setRegister(Apds9960Minimal.REG_GFLVL, 0)

        then:
        sensor.readGestureFifo().size() == 0
        sensor.gestureFifoLevel() == 0

        when:
        sensor.clearGestureFifo()

        then:
        (connection.registers().get(Apds9960Minimal.REG_GCONF4) & 0x04) != 0

        when:
        sensor.enableGestureInterrupt(true)

        then:
        (connection.registers().get(Apds9960Minimal.REG_GCONF4) & 0x02) != 0

        when: "STATUS=0x93 (CPSAT|PVALID|AVALID)"
        connection.setRegister(Apds9960Minimal.REG_STATUS, 0x93)

        then:
        sensor.status() == 0x93
        sensor.isAlsValid()
        sensor.isProximityValid()
        sensor.isAlsSaturated()
        !sensor.isProximitySaturated()
        sensor.chipId() == 0xAB
    }
}
