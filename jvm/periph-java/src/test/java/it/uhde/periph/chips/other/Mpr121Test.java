package it.uhde.periph.chips.other;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mpr121Test {

    @Test
    void minimalConstructionWritesExpectedRegisters() throws Exception {
        MockConnection connection = new MockConnection();
        new Mpr121Minimal(connection);

        int srst = Mpr121Minimal.REG_SRST;
        int ecr  = Mpr121Minimal.REG_ECR;
        int t0   = Mpr121Minimal.REG_E0TTH;
        int r0   = Mpr121Minimal.REG_E0RTH;

        assertEquals(Mpr121Minimal.SOFT_RESET_KEY,
                connection.registers().get(srst) & 0xFF);
        assertEquals(Mpr121Minimal.ECR_DEFAULT,
                connection.registers().get(ecr) & 0xFF);
        assertEquals(Mpr121Minimal.TOUCH_DEFAULT,
                connection.registers().get(t0) & 0xFF);
        assertEquals(Mpr121Minimal.RELEASE_DEFAULT,
                connection.registers().get(r0) & 0xFF);
    }

    @Test
    void touchedDecodesBitmask() throws Exception {
        MockConnection connection = new MockConnection();
        connection.setRegister(Mpr121Minimal.REG_ELE0_7_TOUCH, 0x5A, 0x05);
        Mpr121Minimal chip = new Mpr121Minimal(connection);

        assertEquals(0x5A | ((0x05 & 0x0F) << 8), chip.touched());
    }

    @Test
    void isTouchedPerElectrode() throws Exception {
        MockConnection connection = new MockConnection();
        connection.setRegister(Mpr121Minimal.REG_ELE0_7_TOUCH, 0x28, 0x08);
        Mpr121Minimal chip = new Mpr121Minimal(connection);

        assertTrue(chip.isTouched(5));
        assertTrue(chip.isTouched(11));
        assertFalse(chip.isTouched(0));
    }

    @Test
    void isTouchedRejectsOutOfRange() throws Exception {
        MockConnection connection = new MockConnection();
        Mpr121Minimal chip = new Mpr121Minimal(connection);

        assertThrows(IllegalArgumentException.class, () -> chip.isTouched(12));
    }

    @Test
    void filteredDecodes10Bit() throws Exception {
        MockConnection connection = new MockConnection();
        connection.setRegister(0x04, 0x80, 0x02);
        Mpr121Full chip = new Mpr121Full(connection);

        assertEquals(0x280, chip.filtered(0));
    }

    @Test
    void baselineShiftsLeft2() throws Exception {
        MockConnection connection = new MockConnection();
        connection.setRegister(0x1E, 0x80);
        Mpr121Full chip = new Mpr121Full(connection);

        assertEquals(0x200, chip.baseline(0));
    }

    @Test
    void setBaselineShiftsRight2() throws Exception {
        MockConnection connection = new MockConnection();
        Mpr121Full chip = new Mpr121Full(connection);
        chip.setBaseline(0, 0x300);

        assertEquals(0xC0, connection.registers().get(0x1E) & 0xFF);
    }

    @Test
    void proximityTouchedReadsBit4() throws Exception {
        MockConnection connection = new MockConnection();
        connection.setRegister(Mpr121Minimal.REG_ELE8_PROX_TCH, 0x10);
        Mpr121Full chip = new Mpr121Full(connection);

        assertTrue(chip.proximityTouched());
        connection.setRegister(Mpr121Minimal.REG_ELE8_PROX_TCH, 0x00);
        assertFalse(chip.proximityTouched());
    }

    @Test
    void clearOvercurrentClearsBit7() throws Exception {
        MockConnection connection = new MockConnection();
        connection.setRegister(Mpr121Minimal.REG_ELE8_PROX_TCH, 0x80);
        Mpr121Full chip = new Mpr121Full(connection);
        chip.clearOvercurrent();

        assertEquals(0x00, connection.registers().get(Mpr121Minimal.REG_ELE8_PROX_TCH) & 0x80);
    }

    @Test
    void configureSamplingPacksBits() throws Exception {
        MockConnection connection = new MockConnection();
        Mpr121Full chip = new Mpr121Full(connection);
        chip.configureSampling(10, 2, 1, 2, 5);

        assertEquals(0x4A, connection.registers().get(Mpr121Minimal.REG_CDC_CONFIG) & 0xFF);
        assertEquals(0x4D, connection.registers().get(Mpr121Minimal.REG_CDT_CONFIG) & 0xFF);
    }

    @Test
    void configureDebouncePacksBits() throws Exception {
        MockConnection connection = new MockConnection();
        Mpr121Full chip = new Mpr121Full(connection);
        chip.configureDebounce(3, 5);

        assertEquals(0x53, connection.registers().get(Mpr121Minimal.REG_DEBOUNCE) & 0xFF);
    }

    @Test
    void enableDisableInterrupt() throws Exception {
        MockConnection connection = new MockConnection();
        connection.setRegister(Mpr121Minimal.REG_AUTOCONFIG1, 0x00);
        Mpr121Full chip = new Mpr121Full(connection);

        chip.enableInterrupt(Mpr121Full.SOURCE_OOR);
        assertEquals(0x04, connection.registers().get(Mpr121Minimal.REG_AUTOCONFIG1) & 0x07);

        connection.setRegister(Mpr121Minimal.REG_AUTOCONFIG1, 0x04);
        chip.disableInterrupt(Mpr121Full.SOURCE_OOR);
        assertEquals(0x00, connection.registers().get(Mpr121Minimal.REG_AUTOCONFIG1) & 0x07);
    }
}
