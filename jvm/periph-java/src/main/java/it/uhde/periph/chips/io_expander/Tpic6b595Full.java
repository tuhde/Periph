package it.uhde.periph.chips.io_expander;

import it.uhde.periph.connection.SiPoConnection;

import java.io.IOException;

/**
 * TPIC6B595 full driver — extends {@link Tpic6b595Minimal} with hardware features.
 *
 * <p>Adds {@link #clear()}, {@link #setOutputEnable(boolean)}, and
 * {@link #writeAll(int[])} for the shift-register-clear and output-enable
 * hardware lines, plus a bulk multi-device write. The {@link Pin} API
 * surface stays exactly Minimal's — every TPIC6B595 pin is a fixed,
 * capability-less output (no pull, no drive mode, no interrupt).
 */
public class Tpic6b595Full extends Tpic6b595Minimal {

    /**
     * Construct and initialise the TPIC6B595Full.
     *
     * @param connection Configured SiPo connection.
     * @param numDevices Number of cascaded TPIC6B595s on the wire; default 1.
     */
    public Tpic6b595Full(SiPoConnection connection, int numDevices) throws IOException {
        super(connection, numDevices);
    }

    /** Convenience overload with {@code numDevices = 1}. */
    public Tpic6b595Full(SiPoConnection connection) throws IOException {
        super(connection, 1);
    }

    /** Return a Full {@link Pin} proxy for global pin {@code n}. */
    @Override
    public Pin pin(int n) {
        return new Pin(this, n);
    }

    /**
     * Pulse SRCLR to clear the shift register only.
     *
     * <p>The storage register (and therefore the DRAIN outputs) keeps its
     * last-latched value until the next RCK pulse. To actually blank the
     * outputs, call {@code off()} or {@code fill(false)} afterwards.
     *
     * @throws IllegalStateException if SRCLR was not wired on the SiPo connection.
     * @throws IOException on GPIO error.
     */
    public void clear() throws IOException {
        connection.clear();
    }

    /**
     * Drive G LOW ({@code enabled = true}) or HIGH ({@code enabled = false}).
     *
     * @throws IllegalStateException if G was not wired on the SiPo connection.
     * @throws IOException on GPIO error.
     */
    public void setOutputEnable(boolean enabled) throws IOException {
        connection.setOutputEnable(enabled);
    }

    /**
     * Write every cascaded device's byte in one call.
     *
     * <p>Updates the whole shadow register and performs exactly one transmit
     * + latch. {@code values} is length-truncated or zero-extended to
     * {@code numDevices} as needed.
     *
     * @param values Byte array (one entry per cascaded device). {@code values[0]}
     *               is the byte for cascaded device 0 (nearest the controller).
     */
    public void writeAll(int[] values) throws IOException {
        for (int i = 0; i < numDevices; i++) {
            int v = (i < values.length) ? values[i] : 0;
            shadow[i] = v & 0xFF;
        }
        flush();
    }

    /**
     * GPIO proxy for a single TPIC6B595 pin — Full interface.
     *
     * <p>Extends {@link Tpic6b595Minimal.Pin} with no additional API surface
     * (the chip's pins are fixed output).
     */
    public static class Pin extends Tpic6b595Minimal.Pin {
        protected Pin(Tpic6b595Full chip, int n) {
            super(chip, n);
        }
    }
}
