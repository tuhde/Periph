package it.uhde.periph.chips;

import it.uhde.periph.connection.AbstractConnection;
import it.uhde.periph.connection.OutputPin;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnPolarityTest {

    static final class RecordingPin implements OutputPin {
        final List<Boolean> levels = new ArrayList<>();
        @Override public void set(boolean high) { levels.add(high); }
        @Override public void close() {}
    }

    static final class NullConnection extends AbstractConnection {
        NullConnection(OutputPin en) { super(null, en); }
        @Override protected byte[] _read(int n) { return new byte[n]; }
        @Override protected void _write(byte[] data) {}
        @Override protected byte[] _writeRead(byte[] data, int n) { return new byte[n]; }
        @Override public void close() {}
    }

    @Test
    void activeHighIsDefault() {
        RecordingPin pin = new RecordingPin();
        NullConnection c = new NullConnection(pin);
        c.disable();
        c.enable();
        assertEquals(List.of(false, true), pin.levels);
    }

    @Test
    void activeLowInvertsLevels() {
        RecordingPin pin = new RecordingPin();
        NullConnection c = new NullConnection(pin);
        c.setEnActiveHigh(false);
        c.disable();
        assertFalse(c.isEnabled());
        c.enable();
        assertTrue(c.isEnabled());
        assertEquals(List.of(true, false), pin.levels);
    }
}
