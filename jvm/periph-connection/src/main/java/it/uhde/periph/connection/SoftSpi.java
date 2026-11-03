package it.uhde.periph.connection;

import java.io.IOException;

/**
 * Bit-banged SPI master (mode 0, MSB first) over {@link OutputPin} /
 * {@link InputPin}.
 *
 * <p>The software side of {@link DisplayConnection}: used when the bus pins
 * are arbitrary GPIOs. Chip select is not part of the bus object; the
 * connection drives CS itself.
 *
 * <p>Reading needs the optional MISO input. For a display controller whose DIN
 * and DOUT share one pin, MISO is wired to that pin and MOSI is joined to it
 * through a series resistor, so the host keeps driving MOSI (dummy bytes) while
 * the chip drives the line and wins — no direction switch is needed.
 */
public final class SoftSpi {

    private final OutputPin sck;
    private final OutputPin mosi;
    private final InputPin miso; // null for a write-only bus

    /**
     * @param sck  clock output (parked low, mode 0 idle)
     * @param mosi data output to the device
     * @param miso data input from the device, or {@code null} for a write-only bus
     */
    public SoftSpi(OutputPin sck, OutputPin mosi, InputPin miso) {
        this.sck = sck;
        this.mosi = mosi;
        this.miso = miso;
        sck.set(false);
    }

    /** @return {@code true} when a MISO pin was given */
    public boolean canRead() { return miso != null; }

    /**
     * Shift {@code data} out MSB-first, SPI mode 0.
     *
     * @param data bytes to send
     */
    public void write(byte[] data) {
        for (byte b : data) {
            for (int bit = 7; bit >= 0; bit--) {
                mosi.set(((b >> bit) & 1) != 0);
                sck.set(true);
                sck.set(false);
            }
        }
    }

    /**
     * Clock in {@code n} bytes from MISO while driving {@code fill} on MOSI.
     *
     * @param n    number of bytes to read
     * @param fill byte driven on MOSI during every clocked byte
     * @return the sampled bytes, MSB-first
     * @throws IllegalStateException if no MISO pin was given
     * @throws IOException if MISO cannot be read
     */
    public byte[] read(int n, int fill) throws IOException {
        if (miso == null) throw new IllegalStateException("SoftSpi has no MISO pin");
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            int v = 0;
            for (int bit = 7; bit >= 0; bit--) {
                mosi.set(((fill >> bit) & 1) != 0);
                sck.set(true);
                v = (v << 1) | (miso.isHigh() ? 1 : 0);
                sck.set(false);
            }
            out[i] = (byte) v;
        }
        return out;
    }
}
