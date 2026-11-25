package it.uhde.periph.chips.io_expander;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PCA9539Test {

    private static final int REG_INPUT = 0x00, REG_OUTPUT = 0x02, REG_POLARITY = 0x04, REG_CONFIG = 0x06;

    /** Register bytes of the data writes (length &gt; 1) after index {@code from}; read-pointer writes are skipped. */
    private static List<Integer> dataWrites(MockConnection c, int from) {
        return c.writes().stream().skip(from).filter(w -> w.length > 1)
            .map(w -> w[0] & 0xFF).collect(Collectors.toList());
    }

    @Test
    void fullApi() throws Exception {
        MockConnection c = new MockConnection();
        PCA9539Full chip = new PCA9539Full(c, 0x74);

        // Init writes the reset state of both ports as two-byte pair writes.
        assertEquals(0xFF, c.registers().get(REG_OUTPUT));
        assertEquals(0xFF, c.registers().get(REG_OUTPUT + 1));
        assertEquals(0x00, c.registers().get(REG_POLARITY));
        assertEquals(0x00, c.registers().get(REG_POLARITY + 1));
        assertEquals(0xFF, c.registers().get(REG_CONFIG));
        assertEquals(0xFF, c.registers().get(REG_CONFIG + 1));
        assertEquals(0xFFFF, chip.shadow);
        assertEquals(0xFFFF, chip.direction);
        assertEquals(List.of(REG_OUTPUT, REG_POLARITY, REG_CONFIG), dataWrites(c, 0), "init is three pair writes");
        assertFalse(dataWrites(c, 0).contains(REG_INPUT), "init must not write INPUT");

        c.setRegister(REG_INPUT, 0xA5, 0x5A);
        assertEquals(0xA5, chip.readPort(0));
        assertEquals(0x5A, chip.readPort(1));
        assertEquals(0x5AA5, chip.readAll());
        assertThrows(IllegalArgumentException.class, () -> chip.readPort(2));

        chip.writePort(0, 0x3C);
        chip.writePort(1, 0xC3);
        assertEquals(0x3C, c.registers().get(REG_OUTPUT));
        assertEquals(0xC3, c.registers().get(REG_OUTPUT + 1));
        assertEquals(0xC33C, chip.shadow);

        int before = c.writes().size();
        chip.writeAll(0x1234);
        assertEquals(0x34, c.registers().get(REG_OUTPUT));
        assertEquals(0x12, c.registers().get(REG_OUTPUT + 1));
        assertEquals(0x1234, chip.shadow);
        assertEquals(1, dataWrites(c, before).size(), "writeAll is one two-byte transfer");

        // Direction: output clears the CONFIG bit of its port.
        chip.writeAll(0xFFFF);
        chip.pin(7).setOutput();
        assertEquals(0x7F, c.registers().get(REG_CONFIG));
        assertEquals(0xFF, c.registers().get(REG_CONFIG + 1));
        chip.pin(7).setInput();
        chip.pin(12).setOutput();
        assertEquals(0xEF, c.registers().get(REG_CONFIG + 1));
        assertEquals(0xFF, c.registers().get(REG_CONFIG));
        chip.pin(12).setInput();

        // OUTPUT is written before CONFIG when a pin becomes an output.
        before = c.writes().size();
        chip.pin(9).setOutput();
        assertEquals(List.of(REG_OUTPUT + 1, REG_CONFIG + 1), dataWrites(c, before));
        chip.pin(9).setInput();

        chip.writeAll(0x0000);
        c.setRegister(REG_INPUT, 0x04, 0x80);
        assertTrue(chip.pin(2).read());
        assertTrue(chip.pin(15).read());
        assertFalse(chip.pin(3).read());
        chip.pin(0).setHigh();
        chip.pin(2).setHigh();
        chip.pin(0).setLow();
        assertEquals(0x04, c.registers().get(REG_OUTPUT));
        chip.pin(8).setHigh();
        assertEquals(0x01, c.registers().get(REG_OUTPUT + 1));
        assertEquals(0x04, c.registers().get(REG_OUTPUT));
        chip.pin(8).toggle();
        assertEquals(0x00, c.registers().get(REG_OUTPUT + 1));

        // No pull-ups.
        assertThrows(UnsupportedOperationException.class, () -> chip.pin(3).setPullUp(true));

        before = c.writes().size();
        chip.configurePolarity(0xF00F);
        assertEquals(0x0F, c.registers().get(REG_POLARITY));
        assertEquals(0xF0, c.registers().get(REG_POLARITY + 1));
        assertEquals(1, dataWrites(c, before).size());

        before = c.writes().size();
        chip.setDirection(0xFF00);
        assertEquals(0x00, c.registers().get(REG_CONFIG));
        assertEquals(0xFF, c.registers().get(REG_CONFIG + 1));
        assertEquals(List.of(REG_OUTPUT, REG_CONFIG), dataWrites(c, before));
        assertEquals(0xFF00, chip.getDirection());

        assertEquals(0xFF, chip.readRegister(REG_CONFIG + 1));
        chip.writeRegister(REG_OUTPUT + 1, 0x81);
        assertEquals(0x81, chip.shadow >> 8);
        chip.writeRegister(REG_CONFIG, 0x0F);
        assertEquals(0xFF0F, chip.direction);
        chip.writeRegister(REG_POLARITY + 1, 0x3C);
        for (int bad : new int[]{0, 1, 8}) {
            assertThrows(IllegalArgumentException.class, () -> chip.writeRegister(bad, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> chip.readRegister(8));

        // pollInterrupt: 16-bit changed mask limited to inputs, cache updated.
        chip.setDirection(0xF0F0);
        c.setRegister(REG_INPUT, 0x00, 0x00);
        chip.pollInterrupt();
        c.setRegister(REG_INPUT, 0x35, 0x9A);
        assertEquals(0x9030, chip.pollInterrupt());
        assertEquals(0x0000, chip.pollInterrupt());

        chip.reset();
        assertEquals(0xFF, c.registers().get(REG_OUTPUT));
        assertEquals(0xFF, c.registers().get(REG_OUTPUT + 1));
        assertEquals(0x00, c.registers().get(REG_POLARITY));
        assertEquals(0xFF, c.registers().get(REG_CONFIG + 1));
        assertEquals(0xFFFF, chip.shadow);
        assertEquals(0xFFFF, chip.direction);
    }

    @Test
    void watchOutputThrows() throws Exception {
        PCA9539Full chip = new PCA9539Full(new MockConnection(), 0x74);
        chip.pin(10).setOutput();
        assertThrows(IllegalStateException.class, () -> chip.pin(10).watch(p -> { }));
    }

    @Test
    void minimalIsConstructible() throws Exception {
        assertEquals(0, new PCA9539Minimal(new MockConnection(), 0x74).readPort(0));
    }
}
