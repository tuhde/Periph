package it.uhde.periph.chips.power;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Ade7953Test {

    @Test
    void fullApi() {
        var connection = new MockConnection();
        connection.setRegister(0x21C, 0x89, 0xD1, 0x47);   // VRMS = 9032007
        var chip = new Ade7953Full(connection, 100.0, 10.0);
        // --- voltage ---
        assertEquals(35.35533905932738, chip.voltage(), 1e-3);
        // --- current Channel A ---
        connection.setRegister(0x21A, 0x89, 0xD1, 0x47);   // IRMSA = 9032007
        assertEquals(3.53553390593, chip.current(), 1e-3);
        // --- activePower ---
        connection.setRegister(0x212, 0x4A, 0x31, 0xC1);   // AWATT = 4862401
        assertEquals(125.0, chip.activePower(), 1e-3);
        // --- reset writes SWRST ---
        int writesBefore = connection.writes().size();
        chip.reset();
        boolean foundSwrst = false;
        for (int i = writesBefore + 1; i < connection.writes().size(); i++) {
            var w = connection.writes().get(i);
            if (w.size() >= 4 && (w.get(3) & 0x80) != 0) {
                foundSwrst = true;
                break;
            }
        }
        assertTrue(foundSwrst, "reset writes SWRST");
    }
}