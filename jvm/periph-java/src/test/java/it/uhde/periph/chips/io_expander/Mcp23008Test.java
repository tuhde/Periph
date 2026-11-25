package it.uhde.periph.chips.io_expander;

import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mcp23008Test {

    private static final int REG_IODIR = 0x00, REG_IPOL = 0x01, REG_GPINTEN = 0x02, REG_DEFVAL = 0x03;
    private static final int REG_IOCON = 0x05, REG_GPPU = 0x06, REG_INTF = 0x07, REG_INTCAP = 0x08;
    private static final int REG_GPIO = 0x09, REG_OLAT = 0x0A;

    /**
     * The MCP23S08 read header is {@code [opcode, reg]}; MockConnection treats the
     * first byte as the register, so resolve reads from the second byte instead.
     */
    private static final class SpiMock extends MockConnection {
        @Override
        public byte[] writeRead(byte[] data, int n) throws IOException {
            writes().add(data.clone());
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) out[i] = (byte) (int) registers().getOrDefault((data[1] & 0xFF) + i, 0);
            return out;
        }
    }

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    @Test
    void i2cFullApi() throws Exception {
        MockConnection c = new MockConnection();
        Mcp23008Full chip = new Mcp23008Full(c, 0x20);

        assertEquals(0x00, c.registers().get(REG_OLAT));
        assertEquals(0x00, chip.shadow);
        assertEquals(0xFF, chip.direction);
        assertTrue(c.writes().stream().noneMatch(w -> (w[0] & 0xFF) == REG_IODIR), "init must not write IODIR");

        c.setRegister(REG_GPIO, 0xA5);
        assertEquals(0xA5, chip.readPort(0));
        assertEquals(0xA5, chip.readPort(1));

        chip.writePort(0, 0x3C);
        assertEquals(0x3C, c.registers().get(REG_OLAT));
        assertEquals(0x3C, chip.shadow);

        // GP7 is a normal bidirectional pin.
        chip.pin(7).setOutput();
        assertEquals(0x7F, c.registers().get(REG_IODIR));
        chip.pin(7).setInput();
        assertEquals(0xFF, c.registers().get(REG_IODIR));

        // OLAT is written before IODIR when a pin becomes an output.
        chip.writePort(0, 0x00);
        int before = c.writes().size();
        chip.pin(1).setOutput();
        assertEquals(before + 2, c.writes().size());
        assertEquals(REG_OLAT, c.writes().get(before)[0] & 0xFF);
        assertEquals(REG_IODIR, c.writes().get(before + 1)[0] & 0xFF);

        chip.writePort(0, 0x00);
        c.setRegister(REG_GPIO, 0x04);
        assertTrue(chip.pin(2).read());
        chip.pin(0).setHigh();
        assertEquals(0x01, c.registers().get(REG_OLAT));
        chip.pin(2).setHigh();
        assertEquals(0x05, c.registers().get(REG_OLAT));
        chip.pin(0).setLow();
        assertEquals(0x04, c.registers().get(REG_OLAT));
        chip.pin(0).toggle();
        assertEquals(0x05, c.registers().get(REG_OLAT));
        chip.pin(0).toggle();
        assertEquals(0x04, c.registers().get(REG_OLAT));

        // OutputPin contract.
        chip.pin(3).set(true);
        assertEquals(0x0C, c.registers().get(REG_OLAT));

        chip.configurePullup(0xF0);
        assertEquals(0xF0, c.registers().get(REG_GPPU));
        chip.pin(0).setPullUp(true);
        assertEquals(0xF1, c.registers().get(REG_GPPU));
        chip.configurePullup(0x00);
        chip.configurePolarity(0x0F);
        assertEquals(0x0F, c.registers().get(REG_IPOL));
        chip.setDefaultValue(0x11);
        assertEquals(0x11, c.registers().get(REG_DEFVAL));
        chip.enablePinInterrupt(0xF0);
        assertEquals(0xF0, c.registers().get(REG_GPINTEN));

        chip.configureIntPin(false, true);
        assertEquals(0x02, c.registers().get(REG_IOCON));
        chip.configureIntPin(true, true);
        assertEquals(0x04, c.registers().get(REG_IOCON));
        chip.configureIntPin(false, false);
        chip.setSequential(false);
        assertEquals(0x20, c.registers().get(REG_IOCON));
        chip.setSequential(true);
        assertEquals(0x00, c.registers().get(REG_IOCON));

        c.setRegister(REG_INTF, 0x10);
        c.setRegister(REG_INTCAP, 0xFF);
        assertEquals(0x10, chip.pollInterrupt());
        c.setRegister(REG_INTCAP, 0x22);
        assertEquals(0x22, chip.readCapture());

        chip.writeRegister(REG_IODIR, 0x0F);
        assertEquals(0x0F, chip.direction);
        chip.writeRegister(REG_OLAT, 0x81);
        assertEquals(0x81, chip.shadow);
        assertEquals(0x81, chip.readRegister(REG_OLAT));

        // Interrupt delivery by polling: INTF/INTCAP drive callback and pin watch.
        MockConnection wc = new MockConnection();
        Mcp23008Full wchip = new Mcp23008Full(wc, 0x20);
        AtomicInteger seenStatus = new AtomicInteger(-1);
        AtomicInteger watched = new AtomicInteger();
        wchip.pin(4).watch(p -> watched.incrementAndGet(), EdgeTrigger.FALLING);
        assertEquals(0x10, wc.registers().get(REG_GPINTEN));
        wc.setRegister(REG_INTF, 0x10);
        wc.setRegister(REG_INTCAP, 0x00);
        wchip.onInterrupt(seenStatus::set);
        Thread.sleep(60);
        wchip.offInterrupt();
        assertEquals(0x10, seenStatus.get());
        assertTrue(watched.get() >= 1, "falling watch should fire");
        wchip.pin(4).unwatch();
        assertEquals(0x00, wc.registers().get(REG_GPINTEN));

        // Error cases.
        Mcp23008Full chip2 = new Mcp23008Full(new MockConnection(), 0x20);
        assertThrows(IllegalStateException.class, () -> chip2.onInterrupt(s -> { }));
        chip2.pin(0).setOutput();
        assertThrows(IllegalStateException.class, () -> chip2.pin(0).watch(p -> { }));

        chip.reset();
        assertEquals(0xFF, c.registers().get(REG_IODIR));
        assertEquals(0x00, c.registers().get(REG_OLAT));
        assertEquals(0x00, c.registers().get(REG_GPPU));
        assertEquals(0x00, c.registers().get(REG_GPINTEN));
        assertEquals(0x00, chip.shadow);
        assertEquals(0xFF, chip.direction);

        assertEquals(0, new Mcp23008Minimal(new MockConnection(), 0x20).readPort(0));
    }

    @Test
    void spiFraming() throws Exception {
        SpiMock c = new SpiMock();
        Mcp23S08Full chip = new Mcp23S08Full(c, 2);

        // Init: IOCON <- HAEN under the address-0 opcode, then OLAT <- 0 under the real opcode.
        assertArrayEquals(b(0x40, 0x05, 0x08), c.writes().get(0));
        assertArrayEquals(b(0x44, 0x0A, 0x00), c.writes().get(1));

        chip.writePort(0, 0x5A);
        assertArrayEquals(b(0x44, 0x0A, 0x5A), c.writes().get(c.writes().size() - 1));

        c.setRegister(REG_GPIO, 0xC3);
        assertEquals(0xC3, chip.readPort(0));
        assertArrayEquals(b(0x45, 0x09), c.writes().get(c.writes().size() - 1));

        chip.configurePullup(0x0F);
        assertArrayEquals(b(0x44, 0x06, 0x0F), c.writes().get(c.writes().size() - 1));
        chip.configureIntPin(true, false);
        assertArrayEquals(b(0x44, 0x05, 0x0C), c.writes().get(c.writes().size() - 1));
        chip.reset();
        assertTrue(c.writes().stream().anyMatch(w -> Arrays.equals(w, b(0x44, 0x05, 0x08))),
            "reset must rewrite IOCON to HAEN");

        assertEquals(0, new Mcp23S08Minimal(new SpiMock(), 0).readPort(0));
    }
}
