package it.uhde.periph.chips.gyroscope

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class L3g4200dSpec extends Specification {

    def "full API"() {
        given:
        def connection = new MockConnection()
        connection.setRegister(L3g4200dMinimal.REG_WHO_AM_I, 0xD3)

        def sensor = new L3g4200dFull(connection, false)

        expect:
        connection.registers().get(L3g4200dMinimal.REG_CTRL_REG4) == L3g4200dFull.CTRL_REG4_DEFAULT
        connection.registers().get(L3g4200dMinimal.REG_CTRL_REG1) == L3g4200dFull.CTRL_REG1_DEFAULT

        // raw X=+16, Y=0, Z=-16 (LE). I²C multi-byte sub-address has bit 7 set.
        connection.setRegister(L3g4200dMinimal.REG_OUT_X_L | 0x80,
                0x10, 0x00,    // X=+16
                0x00, 0x00,    // Y=0
                0xF0, 0xFF)    // Z=-16

        def k = (float) (Math.PI / 180.0)
        def xyz = sensor.angularRate()
        Math.abs(xyz[0] - 16.0f * 0.00875f * k) < 1e-6f
        Math.abs(xyz[1] - 0.0f) < 1e-6f
        Math.abs(xyz[2] - (-16.0f) * 0.00875f * k) < 1e-6f

        when:
        sensor.configure(L3g4200dFull.ODR_200_HZ, 0, L3g4200dFull.FS_500_DPS)

        then:
        connection.registers().get(L3g4200dMinimal.REG_CTRL_REG1) == (L3g4200dFull.CTRL_REG1_DEFAULT | (1 << 6))
        connection.registers().get(L3g4200dMinimal.REG_CTRL_REG4) == (L3g4200dFull.CTRL_REG4_DEFAULT | (1 << 4))
        sensor.fullScale == 500

        when:
        connection.setRegister(L3g4200dMinimal.REG_CTRL_REG4,
                L3g4200dFull.CTRL_REG4_DEFAULT | (1 << 4))
        sensor.setFullScale(L3g4200dFull.FS_2000_DPS)

        then:
        connection.registers().get(L3g4200dMinimal.REG_CTRL_REG4) == (L3g4200dFull.CTRL_REG4_DEFAULT | (2 << 4))
        sensor.fullScale == 2000

        when:
        connection.setRegister(L3g4200dMinimal.REG_STATUS, 0x08)

        then:
        sensor.status() == 0x08
        sensor.dataReady()

        when:
        connection.setRegister(L3g4200dMinimal.REG_OUT_TEMP, 0x80)

        then:
        sensor.temperature() == -128

        when:
        connection.setRegister(L3g4200dMinimal.REG_CTRL_REG1, 0x4F)
        sensor.powerDown()

        then:
        connection.registers().get(L3g4200dMinimal.REG_CTRL_REG1) == 0x47

        when:
        sensor.wakeUp()

        then:
        connection.registers().get(L3g4200dMinimal.REG_CTRL_REG1) == 0x4F

        when:
        sensor.sleep()

        then:
        connection.registers().get(L3g4200dMinimal.REG_CTRL_REG1) == 0x08

        when:
        connection.setRegister(L3g4200dMinimal.REG_CTRL_REG1, 0x08)
        sensor.enableAxes(false, true, false)

        then:
        connection.registers().get(L3g4200dMinimal.REG_CTRL_REG1) == 0x0A

        when:
        connection.setRegister(L3g4200dMinimal.REG_CTRL_REG5, 0x00)
        sensor.enableFifo(L3g4200dFull.FIFO_STREAM, 10)

        then:
        connection.registers().get(L3g4200dMinimal.REG_CTRL_REG5) == 0x40
        connection.registers().get(L3g4200dMinimal.REG_FIFO_CTRL) == ((2 << 5) | 10)

        when:
        connection.setRegister(L3g4200dMinimal.REG_FIFO_SRC, 0x1A)

        then:
        sensor.fifoSamples() == 26

        when:
        sensor.enableHighpass(2, 5)

        then:
        connection.registers().get(L3g4200dMinimal.REG_CTRL_REG2) == ((2 << 4) | 5)
        connection.registers().get(L3g4200dMinimal.REG_CTRL_REG5) == 0x50

        when:
        sensor.disableHighpass()

        then:
        connection.registers().get(L3g4200dMinimal.REG_CTRL_REG5) == 0x40

        when:
        sensor.setInterrupt(true, false, true, false, true, false, false, true)

        then:
        connection.registers().get(L3g4200dMinimal.REG_INT1_CFG) == 0x6A

        when:
        sensor.setThreshold('x' as char, 87.5f)

        then:
        connection.registers().get(L3g4200dMinimal.REG_INT1_THS_XH) == 0x04
        connection.registers().get(L3g4200dMinimal.REG_INT1_THS_XL) == 0xE2

        when:
        sensor.setDuration(4, true)

        then:
        connection.registers().get(L3g4200dMinimal.REG_INT1_DURATION) == 0x84

        when:
        connection.setRegister(L3g4200dMinimal.REG_CTRL_REG3, 0x00)
        sensor.setDataReadyPin(true)

        then:
        connection.registers().get(L3g4200dMinimal.REG_CTRL_REG3) == 0x08
    }
}
