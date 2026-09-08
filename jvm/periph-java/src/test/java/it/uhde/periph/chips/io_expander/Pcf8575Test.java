package it.uhde.periph.chips.io_expander;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Pcf8575Test {

    // PCF8575 has no sub-registers: every transaction is a plain 2-byte
    // read()/write() (Port 0 first, Port 1 second; no register pointer), so
    // MockConnection's register map is never consulted — reads must be
    // preloaded via queueRead() in the exact order the driver will issue
    // them. Pcf8575Full's constructor issues one extra 2-byte read to seed
    // `prev`, so it must be queued too.
    @Test
    void fullApi() throws Exception {
        MockConnection connection = new MockConnection();
        connection.queueRead(new byte[]{(byte) 0xFF, (byte) 0xFF}); // Full ctor seeds prev
        Pcf8575Full chip = new Pcf8575Full(connection);

        // Construction writes [0xFF, 0xFF] (all 16 pins to quasi-bidirectional input).
        byte[] first = connection.writes().get(0);
        assertEquals(2, first.length);
        assertEquals((byte) 0xFF, first[0]);
        assertEquals((byte) 0xFF, first[1]);

        // readPort(0)/(1): both derived from one 2-byte read.
        connection.queueRead(new byte[]{(byte) 0x5A, (byte) 0xA5});
        assertEquals(0x5A, chip.readPort(0));
        connection.queueRead(new byte[]{(byte) 0x5A, (byte) 0xA5});
        assertEquals(0xA5, chip.readPort(1));

        // writePort(): writes both shadow bytes, preserving the untouched port.
        chip.writePort(0, 0x3C);
        byte[] last = connection.writes().get(connection.writes().size() - 1);
        assertEquals((byte) 0x3C, last[0]);
        assertEquals((byte) 0xFF, last[1]);
        chip.writePort(1, 0x0F);
        last = connection.writes().get(connection.writes().size() - 1);
        assertEquals((byte) 0x3C, last[0]);
        assertEquals((byte) 0x0F, last[1]);

        // pin() read on Port 0 and Port 1.
        Pcf8575Minimal.Pin pin3 = chip.pin(3);   // Port 0, bit 3
        connection.queueRead(new byte[]{(byte) 0x08, (byte) 0x00});
        assertTrue(pin3.read());

        Pcf8575Minimal.Pin pin11 = chip.pin(11); // Port 1, bit 3
        connection.queueRead(new byte[]{(byte) 0x00, (byte) 0x08});
        assertTrue(pin11.read());

        // Pin set high/low preserves other shadow bits within the same port.
        chip.writePort(0, 0xFF);
        chip.writePort(1, 0xFF);
        pin3.setLow();
        last = connection.writes().get(connection.writes().size() - 1);
        assertEquals((byte) (0xFF & ~0x08), last[0]);
        assertEquals((byte) 0xFF, last[1]);
        Pcf8575Minimal.Pin pin5 = chip.pin(5);
        pin5.setLow();
        last = connection.writes().get(connection.writes().size() - 1);
        assertEquals((byte) (0xFF & ~0x08 & ~0x20), last[0]);
        assertEquals((byte) 0xFF, last[1]);
        pin11.setLow();
        last = connection.writes().get(connection.writes().size() - 1);
        assertEquals((byte) (0xFF & ~0x08 & ~0x20), last[0]);
        assertEquals((byte) (0xFF & ~0x08), last[1]);

        // Toggle.
        pin3.toggle();
        last = connection.writes().get(connection.writes().size() - 1);
        assertEquals(0x08, last[0] & 0x08);
        pin3.toggle();
        last = connection.writes().get(connection.writes().size() - 1);
        assertEquals(0, last[0] & 0x08);

        // setInput()/setOutput() releases high (input) or drives low (output).
        Pcf8575Minimal.Pin pin0 = chip.pin(0);
        pin0.setOutput();
        last = connection.writes().get(connection.writes().size() - 1);
        assertEquals(0, last[0] & 0x01);
        pin0.setInput();
        last = connection.writes().get(connection.writes().size() - 1);
        assertEquals(1, last[0] & 0x01);

        // Full: pollInterrupt() compares to the previous 2-byte read and
        // returns the 16-bit changed-pin bitmask (bits 0-7 = Port 0, bits
        // 8-15 = Port 1).
        connection.queueRead(new byte[]{(byte) 0xFF, (byte) 0xFF});
        chip.pollInterrupt(); // resync prev to a known value
        connection.queueRead(new byte[]{(byte) 0xF7, (byte) 0xFE}); // Port0 bit3 low, Port1 bit0 low
        assertEquals(0x08 | (0x01 << 8), chip.pollInterrupt());
        connection.queueRead(new byte[]{(byte) 0xF7, (byte) 0xFE}); // no further change
        assertEquals(0x00, chip.pollInterrupt());
    }
}
