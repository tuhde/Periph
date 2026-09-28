package it.uhde.periph.chips.io_expander;

import it.uhde.periph.connection.MockSiPo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Tpic6b595Test {

    @Test
    void constructionClearsAndFlushesAllZero() throws Exception {
        MockSiPo connection = new MockSiPo();
        Tpic6b595Minimal chip = new Tpic6b595Minimal(connection);
        assertEquals(1, connection.clearCount());
        assertArrayEquals(new byte[]{0x00}, connection.writes().get(connection.writes().size() - 1));
        assertEquals(0, chip.shadow[0]);
    }

    @Test
    void constructionWithoutSrclrDoesNotThrow() throws Exception {
        // Regression: SiPoConnection.clear() throws IllegalStateException when
        // SRCLR is unconfigured; the constructor must swallow it.
        MockSiPo connection = new MockSiPo(false, true);
        new Tpic6b595Minimal(connection);
        assertEquals(0, connection.clearCount());
        assertArrayEquals(new byte[]{0x00}, connection.writes().get(connection.writes().size() - 1));
    }

    @Test
    void pinSetHighLowToggleRead() throws Exception {
        MockSiPo connection = new MockSiPo();
        Tpic6b595Minimal chip = new Tpic6b595Minimal(connection);

        Tpic6b595Minimal.Pin pin3 = chip.pin(3);
        pin3.setHigh();
        assertEquals(0x08, chip.shadow[0]);
        assertArrayEquals(new byte[]{0x08}, connection.writes().get(connection.writes().size() - 1));
        assertTrue(pin3.read());

        pin3.setLow();
        assertEquals(0x00, chip.shadow[0]);
        assertFalse(pin3.read());

        pin3.toggle();
        assertEquals(0x08, chip.shadow[0]);
        pin3.toggle();
        assertEquals(0x00, chip.shadow[0]);

        Tpic6b595Minimal.Pin pin5 = chip.pin(5);
        pin5.setHigh();
        assertEquals(0x20, chip.shadow[0]);
        pin3.setHigh();
        assertEquals(0x28, chip.shadow[0]); // pin5 preserved
    }

    @Test
    void writePortFillOff() throws Exception {
        MockSiPo connection = new MockSiPo();
        Tpic6b595Minimal chip = new Tpic6b595Minimal(connection);

        chip.writePort(0, 0x3C);
        assertEquals(0x3C, chip.shadow[0]);
        assertArrayEquals(new byte[]{0x3C}, connection.writes().get(connection.writes().size() - 1));

        chip.fill(true);
        assertEquals(0xFF, chip.shadow[0]);
        assertArrayEquals(new byte[]{(byte) 0xFF}, connection.writes().get(connection.writes().size() - 1));

        chip.off();
        assertEquals(0x00, chip.shadow[0]);
        assertArrayEquals(new byte[]{0x00}, connection.writes().get(connection.writes().size() - 1));
    }

    @Test
    void cascadeWireOrderReversed() throws Exception {
        MockSiPo connection = new MockSiPo();
        Tpic6b595Minimal chip = new Tpic6b595Minimal(connection, 3);

        chip.writePort(0, 0xAA);
        chip.writePort(1, 0xBB);
        chip.writePort(2, 0xCC);
        assertArrayEquals(new byte[]{(byte) 0xCC, (byte) 0xBB, (byte) 0xAA},
            connection.writes().get(connection.writes().size() - 1));

        Tpic6b595Minimal.Pin pinFar = chip.pin(16); // device 2, bit 0
        pinFar.setHigh();
        assertEquals(0xCD, chip.shadow[2]);
        assertArrayEquals(new byte[]{(byte) 0xCD, (byte) 0xBB, (byte) 0xAA},
            connection.writes().get(connection.writes().size() - 1));
    }

    @Test
    void fullClearAndSetOutputEnable() throws Exception {
        MockSiPo connection = new MockSiPo();
        Tpic6b595Full full = new Tpic6b595Full(connection);

        full.clear();
        assertEquals(2, connection.clearCount()); // +1 from construction

        full.setOutputEnable(true);
        full.setOutputEnable(false);
        assertEquals(java.util.List.of(true, false), connection.outputEnableCalls());
    }

    @Test
    void fullClearAndSetOutputEnableThrowWhenUnwired() throws Exception {
        MockSiPo connection = new MockSiPo(false, false);
        Tpic6b595Full full = new Tpic6b595Full(connection);
        assertThrows(IllegalStateException.class, full::clear);
        assertThrows(IllegalStateException.class, () -> full.setOutputEnable(true));
    }

    @Test
    void fullWriteAllZeroExtendsAndTruncates() throws Exception {
        MockSiPo connection = new MockSiPo();
        Tpic6b595Full full = new Tpic6b595Full(connection, 3);

        full.writeAll(new int[]{0x11, 0x22}); // shorter -> zero-extend
        assertEquals(0x11, full.shadow[0]);
        assertEquals(0x22, full.shadow[1]);
        assertEquals(0x00, full.shadow[2]);
        assertArrayEquals(new byte[]{0x00, 0x22, 0x11}, connection.writes().get(connection.writes().size() - 1));

        full.writeAll(new int[]{0x44, 0x55, 0x66, 0x77}); // longer -> truncate
        assertEquals(0x44, full.shadow[0]);
        assertEquals(0x55, full.shadow[1]);
        assertEquals(0x66, full.shadow[2]);
        assertArrayEquals(new byte[]{0x66, 0x55, 0x44}, connection.writes().get(connection.writes().size() - 1));
    }
}
