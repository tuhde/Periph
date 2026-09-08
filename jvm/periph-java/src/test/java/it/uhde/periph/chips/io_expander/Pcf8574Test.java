package it.uhde.periph.chips.io_expander;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Pcf8574Test {

    // PCF8574 has no sub-registers: every transaction is a single plain
    // byte read()/write() (no register pointer), so MockConnection's
    // register map is never consulted — reads must be preloaded via
    // queueRead() in the exact order the driver will issue them.
    @Test
    void fullApi() throws Exception {
        MockConnection connection = new MockConnection();
        Pcf8574Full chip = new Pcf8574Full(connection);

        // Construction writes 0xFF (all pins to quasi-bidirectional input mode).
        assertEquals(1, connection.writes().get(0).length);
        assertEquals((byte) 0xFF, connection.writes().get(0)[0]);

        // readPort(): plain single-byte read.
        connection.queueRead(new byte[]{(byte) 0x5A});
        assertEquals(0x5A, chip.readPort());

        // writePort(): plain single-byte write; updates shadow.
        chip.writePort(0x3C);
        assertEquals((byte) 0x3C, connection.writes().get(connection.writes().size() - 1)[0]);

        // pin().read() reads the live bus level (not the shadow).
        Pcf8574Minimal.Pin pin3 = chip.pin(3);
        connection.queueRead(new byte[]{(byte) 0x08}); // bit 3 high
        assertTrue(pin3.read());

        // Pin set high/low preserves other shadow bits (read-modify-write).
        chip.writePort(0xFF);
        pin3.setLow();
        assertEquals((byte) (0xFF & ~0x08), connection.writes().get(connection.writes().size() - 1)[0]);
        Pcf8574Minimal.Pin pin5 = chip.pin(5);
        pin5.setLow();
        assertEquals((byte) (0xFF & ~0x08 & ~0x20), connection.writes().get(connection.writes().size() - 1)[0]);
        pin3.setHigh();
        assertEquals((byte) (0xFF & ~0x20), connection.writes().get(connection.writes().size() - 1)[0]);

        // Toggle.
        pin3.toggle();
        assertEquals((byte) (0xFF & ~0x20 & ~0x08), connection.writes().get(connection.writes().size() - 1)[0]);
        pin3.toggle();
        assertEquals((byte) (0xFF & ~0x20), connection.writes().get(connection.writes().size() - 1)[0]);

        // setInput()/setOutput() releases high (input) or drives low (output).
        Pcf8574Minimal.Pin pin0 = chip.pin(0);
        pin0.setOutput();
        assertEquals(0, connection.writes().get(connection.writes().size() - 1)[0] & 0x01);
        pin0.setInput();
        assertEquals(1, connection.writes().get(connection.writes().size() - 1)[0] & 0x01);

        // Full: pollInterrupt() compares to the previous read and returns
        // the changed-pin bitmask, also updating the stored previous value.
        connection.queueRead(new byte[]{(byte) 0xFF});
        chip.pollInterrupt(); // resync prev to a known value (0xFF)
        connection.queueRead(new byte[]{(byte) 0xF7}); // bit 3 now low
        assertEquals(0x08, chip.pollInterrupt());
        connection.queueRead(new byte[]{(byte) 0xF7}); // no further change
        assertEquals(0x00, chip.pollInterrupt());
    }
}
