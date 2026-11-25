package it.uhde.periph.chips.io_expander;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PCA9536Test {

    private static final int REG_INPUT = 0x00, REG_OUTPUT = 0x01, REG_POLARITY = 0x02, REG_CONFIG = 0x03;

    /** Register bytes of the data writes (length &gt; 1) after index {@code from}; read-pointer writes are skipped. */
    private static List<Integer> dataWrites(MockConnection c, int from) {
        return c.writes().stream().skip(from).filter(w -> w.length > 1)
            .map(w -> w[0] & 0xFF).collect(Collectors.toList());
    }

    @Test
    void fullApi() throws Exception {
        MockConnection c = new MockConnection();
        PCA9536Full chip = new PCA9536Full(c, 0x41);

        assertEquals(0xFF, c.registers().get(REG_OUTPUT));
        assertEquals(0x00, c.registers().get(REG_POLARITY));
        assertEquals(0xFF, c.registers().get(REG_CONFIG));
        assertEquals(0xFF, chip.shadow);
        assertEquals(0xFF, chip.direction);
        assertFalse(dataWrites(c, 0).contains(REG_INPUT), "init must not write INPUT");

        c.setRegister(REG_INPUT, 0xF5);
        assertEquals(0x05, chip.readPort(0));
        assertEquals(0x05, chip.readPort(1));

        chip.writePort(0, 0x0C);
        assertEquals(0xFC, c.registers().get(REG_OUTPUT));
        assertEquals(0xFC, chip.shadow);

        // P3 is a normal bidirectional pin.
        chip.pin(3).setOutput();
        assertEquals(0xF7, c.registers().get(REG_CONFIG));
        chip.pin(3).setInput();
        assertEquals(0xFF, c.registers().get(REG_CONFIG));

        // OUTPUT is written before CONFIG when a pin becomes an output.
        chip.writePort(0, 0x00);
        int before = c.writes().size();
        chip.pin(1).setOutput();
        assertEquals(List.of(REG_OUTPUT, REG_CONFIG), dataWrites(c, before));

        chip.writePort(0, 0x00);
        c.setRegister(REG_INPUT, 0x04);
        assertTrue(chip.pin(2).read());
        chip.pin(0).setHigh();
        assertEquals(0xF1, c.registers().get(REG_OUTPUT));
        chip.pin(2).setHigh();
        assertEquals(0xF5, c.registers().get(REG_OUTPUT));
        chip.pin(0).setLow();
        assertEquals(0xF4, c.registers().get(REG_OUTPUT));
        chip.pin(0).toggle();
        assertEquals(0xF5, c.registers().get(REG_OUTPUT));

        // The weak pull-up is fixed.
        assertThrows(UnsupportedOperationException.class, () -> chip.pin(3).setPullUp(true));

        chip.configurePolarity(0x0F);
        assertEquals(0x0F, c.registers().get(REG_POLARITY));
        chip.configurePolarity(0x00);

        before = c.writes().size();
        chip.setDirection(0x0C);
        assertEquals(0xFC, c.registers().get(REG_CONFIG));
        assertEquals(List.of(REG_OUTPUT, REG_CONFIG), dataWrites(c, before));
        assertEquals(0x0C, chip.getDirection());

        assertEquals(0xFC, chip.readRegister(REG_CONFIG));
        chip.writeRegister(REG_OUTPUT, 0xF1);
        assertEquals(0xF1, chip.shadow);
        chip.writeRegister(REG_CONFIG, 0x0F);
        assertEquals(0x0F, chip.direction);
        assertThrows(IllegalArgumentException.class, () -> chip.writeRegister(REG_INPUT, 0));
        assertThrows(IllegalArgumentException.class, () -> chip.readRegister(4));

        // pollChanges: changed mask limited to inputs, cache updated.
        chip.setDirection(0x0C);
        c.setRegister(REG_INPUT, 0x00);
        chip.pollChanges();
        c.setRegister(REG_INPUT, 0x0D);
        assertEquals(0x0C, chip.pollChanges());
        assertEquals(0x00, chip.pollChanges());

        // A polarity change refreshes the cache and is not reported.
        c.setRegister(REG_INPUT, 0x00);
        chip.configurePolarity(0x0C);
        assertEquals(0x00, chip.pollChanges());
        chip.configurePolarity(0x00);
        chip.pollChanges();

        chip.reset();
        assertEquals(0xFF, c.registers().get(REG_OUTPUT));
        assertEquals(0x00, c.registers().get(REG_POLARITY));
        assertEquals(0xFF, c.registers().get(REG_CONFIG));
        assertEquals(0xFF, chip.shadow);
        assertEquals(0xFF, chip.direction);
    }

    @Test
    void watchChangesAndPinWatch() throws Exception {
        MockConnection c = new MockConnection();
        PCA9536Full chip = new PCA9536Full(c, 0x41);
        java.util.concurrent.LinkedBlockingQueue<Integer> changes = new java.util.concurrent.LinkedBlockingQueue<>();
        chip.watchChanges(changes::add, 0.002);
        c.setRegister(REG_INPUT, 0x04);
        assertEquals(0x04, changes.poll(2, java.util.concurrent.TimeUnit.SECONDS));
        chip.unwatchChanges();

        java.util.concurrent.LinkedBlockingQueue<Integer> hits = new java.util.concurrent.LinkedBlockingQueue<>();
        chip.pin(3).watch(p -> hits.add(3), it.uhde.periph.connection.EdgeTrigger.RISING);
        c.setRegister(REG_INPUT, 0x0C);
        assertEquals(3, hits.poll(2, java.util.concurrent.TimeUnit.SECONDS));
        chip.unwatchChanges();
    }

    @Test
    void watchOutputThrows() throws Exception {
        PCA9536Full chip = new PCA9536Full(new MockConnection(), 0x41);
        chip.pin(0).setOutput();
        assertThrows(IllegalStateException.class, () -> chip.pin(0).watch(p -> { }));
    }

    @Test
    void minimalIsConstructible() throws Exception {
        assertEquals(0, new PCA9536Minimal(new MockConnection(), 0x41).readPort());
    }
}
