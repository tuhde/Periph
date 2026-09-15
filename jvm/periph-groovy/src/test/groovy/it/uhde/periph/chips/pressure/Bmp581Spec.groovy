package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Bmp581Spec extends Specification {

    def "full API"() {
        given:
        def connection = new MockConnection()
        connection.setRegister(Bmp581Minimal.REG_CHIP_ID, 0x50)
        connection.setRegister(Bmp581Minimal.REG_STATUS, Bmp581Minimal.STATUS_NVM_RDY)
        connection.setRegister(Bmp581Minimal.REG_TEMP_XLSB, 0x00, 0x10, 0x00)
        connection.setRegister(Bmp581Minimal.REG_PRESS_XLSB, 0x04, 0x00, 0x00)

        def sensor = new Bmp581Full(connection)

        expect:
        connection.registers().get(Bmp581Minimal.REG_ODR_CONFIG) == 0x71
        connection.registers().get(Bmp581Minimal.REG_OSR_CONFIG) == 0x40

        Math.abs(sensor.temperature() - 0.0625d) < 1e-6
        Math.abs(sensor.pressure() - 0.0625d) < 1e-6

        def both = sensor.both()
        Math.abs(both[0] - 0.0625d) < 1e-6
        Math.abs(both[1] - 0.0625d) < 1e-6

        when:
        connection.setRegister(Bmp581Minimal.REG_CHIP_ID, 0x50)

        then:
        sensor.chipId() == 0x50

        when:
        connection.setRegister(Bmp581Full.REG_REV_ID, 0x32)

        then:
        sensor.revId() == 0x32

        when:
        connection.setRegister(Bmp581Minimal.REG_STATUS, 0x09)

        then:
        sensor.status() == 0x09

        when:
        connection.setRegister(Bmp581Minimal.REG_INT_STATUS, 0x11)

        then:
        sensor.interruptStatus() == 0x11

        when:
        connection.setRegister(Bmp581Minimal.REG_INT_STATUS, 0x01)

        then:
        sensor.dataReady()

        when:
        sensor.configure(0x17, Bmp581Full.OSR_16X, Bmp581Full.OSR_4X, true)

        then:
        connection.registers().get(Bmp581Minimal.REG_ODR_CONFIG) == ((0x17 << 2) | 0x01)
        connection.registers().get(Bmp581Minimal.REG_OSR_CONFIG) == (0x40 | (4 << 3) | 2)

        when:
        sensor.setMode(Bmp581Full.MODE_STANDBY)

        then:
        connection.registers().get(Bmp581Minimal.REG_ODR_CONFIG) == ((0x17 << 2) | 0x00)

        when:
        sensor.setMode(Bmp581Full.MODE_CONTINUOUS)

        then:
        connection.registers().get(Bmp581Minimal.REG_ODR_CONFIG) == ((0x17 << 2) | 0x03)

        when:
        connection.setRegister(Bmp581Full.REG_DSP_CONFIG, 0x00)
        sensor.setIirFilter(Bmp581Full.IIR_COEFF_3, Bmp581Full.IIR_BYPASS)

        then:
        connection.registers().get(Bmp581Full.REG_DSP_CONFIG) == 0x28
        connection.registers().get(Bmp581Full.REG_DSP_IIR) == ((Bmp581Full.IIR_COEFF_3 << 3) | Bmp581Full.IIR_BYPASS)

        when:
        sensor.enableDrdyInterrupt(true)

        then:
        (connection.registers().get(Bmp581Full.REG_INT_SOURCE) & Bmp581Full.INT_SOURCE_DRDY) != 0

        when:
        sensor.enableFifoInterrupt(true, false)

        then:
        (connection.registers().get(Bmp581Full.REG_INT_SOURCE) & Bmp581Full.INT_SOURCE_FIFO_THS) != 0

        when:
        sensor.enableOorInterrupt(true)

        then:
        (connection.registers().get(Bmp581Full.REG_INT_SOURCE) & Bmp581Full.INT_SOURCE_OOR_P) != 0

        when:
        sensor.configureInterrupt(1, 1, true, true)

        then:
        connection.registers().get(Bmp581Full.REG_INT_CONFIG) == 0x0F

        when:
        sensor.setOorThreshold(110000.0d, 200.0d, 2)

        then:
        def thr17 = (int) (110000.0d * 64.0d) >> 7
        connection.registers().get(Bmp581Full.REG_OOR_THR_P_LSB) == (thr17 & 0xFF)
        connection.registers().get(Bmp581Full.REG_OOR_THR_P_MSB) == ((thr17 >> 8) & 0xFF)
        def range8 = ((int) (200.0d * 64.0d) >> 7) & 0xFF
        connection.registers().get(Bmp581Full.REG_OOR_RANGE) == range8
        connection.registers().get(Bmp581Full.REG_OOR_CONFIG) == ((2 << 6) | ((thr17 >> 16) & 0x01))

        when:
        sensor.configureFifo(Bmp581Full.FIFO_BOTH, Bmp581Full.FIFO_STREAM, 8)

        then:
        connection.registers().get(Bmp581Full.REG_FIFO_SEL) == 0x03
        connection.registers().get(Bmp581Full.REG_FIFO_CONFIG) == 0x08

        when:
        connection.setRegister(Bmp581Full.REG_FIFO_COUNT, 4)

        then:
        sensor.fifoCount() == 4

        when:
        connection.setRegister(Bmp581Full.REG_OSR_EFF, 0xA0)

        then:
        def eff = sensor.effectiveOsr()
        eff[0] == 4
        eff[1] == 0
        sensor.odrIsValid()
    }
}