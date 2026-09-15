package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Bmp581Test {

    @Test
    fun fullApi() {
        val connection = MockConnection()
        connection.setRegister(Bmp581Minimal.REG_CHIP_ID, 0x50)
        connection.setRegister(Bmp581Minimal.REG_STATUS, Bmp581Minimal.STATUS_NVM_RDY)
        connection.setRegister(Bmp581Minimal.REG_TEMP_XLSB, 0x00, 0x10, 0x00)
        connection.setRegister(Bmp581Minimal.REG_PRESS_XLSB, 0x04, 0x00, 0x00)

        val sensor = Bmp581Full(connection)

        assertEquals(0x71, connection.registers()[Bmp581Minimal.REG_ODR_CONFIG])
        assertEquals(0x40, connection.registers()[Bmp581Minimal.REG_OSR_CONFIG])

        assertEquals(0.0625, sensor.temperature(), 1e-6)
        assertEquals(0.0625, sensor.pressure(), 1e-6)
        val both = sensor.both()
        assertEquals(0.0625, both.first, 1e-6)
        assertEquals(0.0625, both.second, 1e-6)

        connection.setRegister(Bmp581Minimal.REG_CHIP_ID, 0x50)
        assertEquals(0x50, sensor.chipId())

        connection.setRegister(Bmp581Full.REG_REV_ID, 0x32)
        assertEquals(0x32, sensor.revId())

        connection.setRegister(Bmp581Minimal.REG_STATUS, 0x09)
        assertEquals(0x09, sensor.status())

        connection.setRegister(Bmp581Minimal.REG_INT_STATUS, 0x11)
        assertEquals(0x11, sensor.interruptStatus())

        connection.setRegister(Bmp581Minimal.REG_INT_STATUS, 0x01)
        assertTrue(sensor.dataReady())

        sensor.configure(0x17, Bmp581Full.OSR_16X, Bmp581Full.OSR_4X, true)
        assertEquals((0x17 shl 2) or 0x01, connection.registers()[Bmp581Minimal.REG_ODR_CONFIG])
        assertEquals(0x40 or (4 shl 3) or 2, connection.registers()[Bmp581Minimal.REG_OSR_CONFIG])

        sensor.setMode(Bmp581Full.MODE_STANDBY)
        assertEquals((0x17 shl 2) or 0x00, connection.registers()[Bmp581Minimal.REG_ODR_CONFIG])

        sensor.setMode(Bmp581Full.MODE_CONTINUOUS)
        assertEquals((0x17 shl 2) or 0x03, connection.registers()[Bmp581Minimal.REG_ODR_CONFIG])

        connection.setRegister(Bmp581Full.REG_DSP_CONFIG, 0x00)
        sensor.setIirFilter(Bmp581Full.IIR_COEFF_3, Bmp581Full.IIR_BYPASS)
        assertEquals(0x28, connection.registers()[Bmp581Full.REG_DSP_CONFIG])
        assertEquals((Bmp581Full.IIR_COEFF_3 shl 3) or Bmp581Full.IIR_BYPASS,
            connection.registers()[Bmp581Full.REG_DSP_IIR])

        sensor.enableDrdyInterrupt(true)
        assertEquals(Bmp581Full.INT_SOURCE_DRDY, connection.registers()[Bmp581Full.REG_INT_SOURCE])

        sensor.enableFifoInterrupt(true, false)
        assertEquals(Bmp581Full.INT_SOURCE_DRDY or Bmp581Full.INT_SOURCE_FIFO_THS,
            connection.registers()[Bmp581Full.REG_INT_SOURCE])

        sensor.enableOorInterrupt(true)
        assertEquals(Bmp581Full.INT_SOURCE_DRDY or Bmp581Full.INT_SOURCE_FIFO_THS or Bmp581Full.INT_SOURCE_OOR_P,
            connection.registers()[Bmp581Full.REG_INT_SOURCE])

        sensor.configureInterrupt(1, 1, true, true)
        assertEquals(0x0F, connection.registers()[Bmp581Full.REG_INT_CONFIG])

        sensor.setOorThreshold(110000.0, 200.0, 2)
        val thr17 = (110000.0 * 64.0).toInt() shr 7
        assertEquals(thr17 and 0xFF, connection.registers()[Bmp581Full.REG_OOR_THR_P_LSB])
        assertEquals((thr17 shr 8) and 0xFF, connection.registers()[Bmp581Full.REG_OOR_THR_P_MSB])
        val range8 = ((200.0 * 64.0).toInt() shr 7) and 0xFF
        assertEquals(range8, connection.registers()[Bmp581Full.REG_OOR_RANGE])
        assertEquals((2 shl 6) or ((thr17 shr 16) and 0x01),
            connection.registers()[Bmp581Full.REG_OOR_CONFIG])

        sensor.configureFifo(Bmp581Full.FIFO_BOTH, Bmp581Full.FIFO_STREAM, 8)
        assertEquals(0x03, connection.registers()[Bmp581Full.REG_FIFO_SEL])
        assertEquals(0x08, connection.registers()[Bmp581Full.REG_FIFO_CONFIG])

        connection.setRegister(Bmp581Full.REG_FIFO_COUNT, 4)
        assertEquals(4, sensor.fifoCount())

        connection.setRegister(Bmp581Full.REG_OSR_EFF, 0xA0)
        val eff = sensor.effectiveOsr()
        assertEquals(4, eff.first)
        assertEquals(0, eff.second)
        assertTrue(sensor.odrIsValid())
    }
}