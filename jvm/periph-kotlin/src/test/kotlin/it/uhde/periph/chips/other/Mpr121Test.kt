package it.uhde.periph.chips.other

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Mpr121Test {

    @Test
    fun minimalConstructionWritesExpectedRegisters() {
        val connection = MockConnection()
        Mpr121Minimal(connection)

        val srst = Mpr121Minimal.REG_SRST
        val ecr  = Mpr121Minimal.REG_ECR
        val t0   = Mpr121Minimal.REG_E0TTH
        val r0   = Mpr121Minimal.REG_E0RTH

        assertEquals(Mpr121Minimal.SOFT_RESET_KEY, connection.registers()[srst]!! and 0xFF)
        assertEquals(Mpr121Minimal.ECR_DEFAULT, connection.registers()[ecr]!! and 0xFF)
        assertEquals(Mpr121Minimal.TOUCH_DEFAULT, connection.registers()[t0]!! and 0xFF)
        assertEquals(Mpr121Minimal.RELEASE_DEFAULT, connection.registers()[r0]!! and 0xFF)
    }

    @Test
    fun touchedDecodesBitmask() {
        val connection = MockConnection()
        connection.setRegister(Mpr121Minimal.REG_ELE0_7_TOUCH, 0x5A, 0x05)
        val chip = Mpr121Minimal(connection)
        assertEquals(0x5A or ((0x05 and 0x0F) shl 8), chip.touched())
    }

    @Test
    fun isTouchedPerElectrode() {
        val connection = MockConnection()
        connection.setRegister(Mpr121Minimal.REG_ELE0_7_TOUCH, 0x28, 0x08)
        val chip = Mpr121Minimal(connection)
        assertTrue(chip.isTouched(5))
        assertTrue(chip.isTouched(11))
        assertFalse(chip.isTouched(0))
    }

    @Test
    fun isTouchedRejectsOutOfRange() {
        val connection = MockConnection()
        val chip = Mpr121Minimal(connection)
        assertThrows(IllegalArgumentException::class.java) { chip.isTouched(12) }
    }

    @Test
    fun filteredDecodes10Bit() {
        val connection = MockConnection()
        connection.setRegister(0x04, 0x80, 0x02)
        val chip = Mpr121Full(connection)
        assertEquals(0x280, chip.filtered(0))
    }

    @Test
    fun baselineShiftsLeft2() {
        val connection = MockConnection()
        connection.setRegister(0x1E, 0x80)
        val chip = Mpr121Full(connection)
        assertEquals(0x200, chip.baseline(0))
    }

    @Test
    fun setBaselineShiftsRight2() {
        val connection = MockConnection()
        val chip = Mpr121Full(connection)
        chip.setBaseline(0, 0x300)
        assertEquals(0xC0, connection.registers()[0x1E]!! and 0xFF)
    }

    @Test
    fun proximityTouchedReadsBit4() {
        val connection = MockConnection()
        connection.setRegister(Mpr121Minimal.REG_ELE8_PROX_TCH, 0x10)
        val chip = Mpr121Full(connection)
        assertTrue(chip.proximityTouched())
        connection.setRegister(Mpr121Minimal.REG_ELE8_PROX_TCH, 0x00)
        assertFalse(chip.proximityTouched())
    }

    @Test
    fun clearOvercurrentClearsBit7() {
        val connection = MockConnection()
        connection.setRegister(Mpr121Minimal.REG_ELE8_PROX_TCH, 0x80)
        val chip = Mpr121Full(connection)
        chip.clearOvercurrent()
        assertEquals(0x00, connection.registers()[Mpr121Minimal.REG_ELE8_PROX_TCH]!! and 0x80)
    }

    @Test
    fun configureSamplingPacksBits() {
        val connection = MockConnection()
        val chip = Mpr121Full(connection)
        chip.configureSampling(10, 2, 1, 2, 5)
        assertEquals(0x4A, connection.registers()[Mpr121Minimal.REG_CDC_CONFIG]!! and 0xFF)
        assertEquals(0x4D, connection.registers()[Mpr121Minimal.REG_CDT_CONFIG]!! and 0xFF)
    }

    @Test
    fun configureDebouncePacksBits() {
        val connection = MockConnection()
        val chip = Mpr121Full(connection)
        chip.configureDebounce(3, 5)
        assertEquals(0x53, connection.registers()[Mpr121Minimal.REG_DEBOUNCE]!! and 0xFF)
    }

    @Test
    fun enableDisableInterrupt() {
        val connection = MockConnection()
        connection.setRegister(Mpr121Minimal.REG_AUTOCONFIG1, 0x00)
        val chip = Mpr121Full(connection)
        chip.enableInterrupt(Mpr121Full.SOURCE_OOR)
        assertEquals(0x04, connection.registers()[Mpr121Minimal.REG_AUTOCONFIG1]!! and 0x07)
        connection.setRegister(Mpr121Minimal.REG_AUTOCONFIG1, 0x04)
        chip.disableInterrupt(Mpr121Full.SOURCE_OOR)
        assertEquals(0x00, connection.registers()[Mpr121Minimal.REG_AUTOCONFIG1]!! and 0x07)
    }
}
