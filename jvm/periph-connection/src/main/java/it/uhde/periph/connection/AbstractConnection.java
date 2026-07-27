package it.uhde.periph.connection;

import java.io.IOException;

/**
 * Template-method base shared by every concrete {@link Connection}: holds the
 * optional {@link InputPin} / {@link OutputPin} and the software-enable flag,
 * gating {@link #read} / {@link #write} / {@link #writeRead} through the
 * protected {@code _read} / {@code _write} / {@code _writeRead} hooks so
 * subclasses never need to check {@link #isEnabled()} themselves.
 */
public abstract class AbstractConnection implements Connection {
    private final InputPin  intPin;
    private final OutputPin enPin;
    private volatile boolean enabled = true;

    protected AbstractConnection(InputPin intPin, OutputPin enPin) {
        this.intPin = intPin;
        this.enPin  = enPin;
    }

    @Override public void enable()  { enabled = true;  if (enPin != null) enPin.set(true);  }
    @Override public void disable() { enabled = false; if (enPin != null) enPin.set(false); }
    @Override public boolean isEnabled() { return enabled; }
    @Override public InputPin  intPin() { return intPin; }
    @Override public OutputPin enPin()  { return enPin;  }

    @Override public byte[] read(int n) throws IOException {
        if (!enabled) return new byte[n];
        return _read(n);
    }

    @Override public void write(byte[] data) throws IOException {
        if (!enabled) return;
        _write(data);
    }

    @Override public byte[] writeRead(byte[] data, int n) throws IOException {
        if (!enabled) return new byte[n];
        return _writeRead(data, n);
    }

    /** Subclass hook: read bytes from the device. */
    protected abstract byte[] _read(int n) throws IOException;

    /** Subclass hook: send bytes to the device. */
    protected abstract void _write(byte[] data) throws IOException;

    /** Subclass hook: write then read without releasing the bus. */
    protected abstract byte[] _writeRead(byte[] data, int n) throws IOException;
}
