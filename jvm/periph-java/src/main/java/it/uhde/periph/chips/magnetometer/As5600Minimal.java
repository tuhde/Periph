package it.uhde.periph.chips.magnetometer;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * AS5600 — 12-bit programmable contactless rotary position sensor (minimal driver).
 *
 * <p>Reads the absolute angle via the ANGLE register (0x0E-0x0F). The chip has a
 * fixed I²C address of 0x36. No configuration is required for basic angle readout;
 * the factory default (CONF=0x0000, NOM mode, hysteresis off, analog output) is
 * suitable for normal use.
 *
 * <h2>Initialization</h2>
 * <p>The constructor reads the STATUS register and verifies MD=1 (magnet detected).
 * If MD=0, an {@link IOException} is thrown — output data is invalid without a magnet.
 */
public class As5600Minimal {

    // --- Register addresses ---
    protected static final int REG_ZMCO      = 0x00;
    protected static final int REG_ZPOS_H    = 0x01;
    protected static final int REG_ZPOS_L    = 0x02;
    protected static final int REG_MPOS_H    = 0x03;
    protected static final int REG_MPOS_L    = 0x04;
    protected static final int REG_MANG_H    = 0x05;
    protected static final int REG_MANG_L    = 0x06;
    protected static final int REG_CONF_H    = 0x07;
    protected static final int REG_CONF_L    = 0x08;
    protected static final int REG_STATUS    = 0x0B;
    protected static final int REG_RAW_ANGLE_H = 0x0C;
    protected static final int REG_RAW_ANGLE_L = 0x0D;
    protected static final int REG_ANGLE_H   = 0x0E;
    protected static final int REG_ANGLE_L   = 0x0F;
    protected static final int REG_AGC       = 0x1A;
    protected static final int REG_MAGNITUDE_H = 0x1B;
    protected static final int REG_MAGNITUDE_L = 0x1C;
    protected static final int REG_BURN      = 0xFF;

    // --- STATUS register bit masks ---
    protected static final int STATUS_MD = 0x08;  // bit 3: magnet detected
    protected static final int STATUS_ML = 0x10;  // bit 4: magnet too weak
    protected static final int STATUS_MH = 0x20;  // bit 5: magnet too strong

    /** Fixed I²C address. */
    protected static final int I2C_ADDR = 0x36;

    protected final Transport transport;

    /**
     * Construct the driver and verify magnet presence.
     *
     * <p>Reads the STATUS register once; if MD=0 (no magnet detected), an
     * {@link IOException} is thrown because angle data would be invalid.
     *
     * @param transport I²C transport bound to address 0x36
     * @throws IOException if MD=0 (magnet not detected) or on I²C error
     */
    public As5600Minimal(Transport transport) throws IOException {
        this.transport = transport;
        int status = readReg8(REG_STATUS);
        if ((status & STATUS_MD) == 0) {
            throw new IOException("AS5600: magnet not detected (MD=0)");
        }
    }

    /**
     * Read the absolute angle in degrees.
     *
     * <p>Reads the ANGLE register (0x0E-0x0F), which respects any OTP-programmed
     * ZPOS/MPOS range. The result is scaled to 0.0–360.0 (exclusive).
     *
     * @return angle in degrees, 0.0–360.0
     * @throws IOException on I²C error
     */
    public double angle() throws IOException {
        int raw = angleRaw();
        return raw * 360.0 / 4096.0;
    }

    /**
     * Read the raw 12-bit angle count.
     *
     * <p>Reads the ANGLE register (0x0E-0x0F), which respects any OTP-programmed
     * ZPOS/MPOS range. Returns 0–4095.
     *
     * @return raw angle count, 0–4095
     * @throws IOException on I²C error
     */
    public int angleRaw() throws IOException {
        return readReg16(REG_ANGLE_H);
    }

    /**
     * Check whether a magnet is detected.
     *
     * <p>Reads the STATUS register and returns the MD flag (bit 3).
     * Output data is valid only when MD=1.
     *
     * @return true if magnet is detected (Bz ≥ 8 mT)
     * @throws IOException on I²C error
     */
    public boolean isMagnetDetected() throws IOException {
        return (readReg8(REG_STATUS) & STATUS_MD) != 0;
    }

    /**
     * Check whether the magnet is too strong.
     *
     * <p>Reads the STATUS register and returns the MH flag (bit 5).
     * MH=1 means AGC minimum gain overflow (Bz &gt; 90 mT).
     *
     * @return true if magnet is too strong
     * @throws IOException on I²C error
     */
    public boolean isMagnetTooStrong() throws IOException {
        return (readReg8(REG_STATUS) & STATUS_MH) != 0;
    }

    /**
     * Check whether the magnet is too weak.
     *
     * <p>Reads the STATUS register and returns the ML flag (bit 4).
     * ML=1 means AGC maximum gain overflow (Bz &lt; 30 mT).
     *
     * @return true if magnet is too weak
     * @throws IOException on I²C error
     */
    public boolean isMagnetTooWeak() throws IOException {
        return (readReg8(REG_STATUS) & STATUS_ML) != 0;
    }

    // ---- low-level helpers ----

    /**
     * Write a single byte to a register.
     *
     * @param reg register address
     * @param val 8-bit value
     * @throws IOException on I²C error
     */
    protected void writeReg8(int reg, int val) throws IOException {
        transport.write(new byte[]{
                (byte) reg,
                (byte) (val & 0xFF)
        });
    }

    /**
     * Read a single byte from a register.
     *
     * @param reg register address
     * @return unsigned 8-bit value (0–255)
     * @throws IOException on I²C error
     */
    protected int readReg8(int reg) throws IOException {
        byte[] b = transport.writeRead(new byte[]{(byte) reg}, 1);
        return b[0] & 0xFF;
    }

    /**
     * Write a 12-bit value split across two registers (high byte first).
     *
     * <p>Writes the high register (bits 11:8 in bits 3:0) then the low register
     * (bits 7:0). Used for ZPOS, MPOS, MANG.
     *
     * @param regHi high-byte register address
     * @param regLo low-byte register address
     * @param val   12-bit value (0–4095)
     * @throws IOException on I²C error
     */
    protected void writeReg12(int regHi, int regLo, int val) throws IOException {
        val = val & 0xFFF;
        transport.write(new byte[]{
                (byte) regHi,
                (byte) ((val >> 8) & 0x0F),
                (byte) (val & 0xFF)
        });
    }

    /**
     * Read a 12-bit value from two consecutive registers (high byte first).
     *
     * <p>Reads two bytes starting at {@code regHi}; the high byte contains
     * bits 11:8 in its lower 4 bits.
     *
     * @param regHi high-byte register address
     * @return 12-bit value (0–4095)
     * @throws IOException on I²C error
     */
    protected int readReg12(int regHi) throws IOException {
        byte[] b = transport.writeRead(new byte[]{(byte) regHi}, 2);
        return ((b[0] & 0x0F) << 8) | (b[1] & 0xFF);
    }

    /**
     * Read a 16-bit value from two consecutive registers (big-endian).
     *
     * @param regHi high-byte register address
     * @return unsigned 16-bit value (0–65535)
     * @throws IOException on I²C error
     */
    protected int readReg16(int regHi) throws IOException {
        byte[] b = transport.writeRead(new byte[]{(byte) regHi}, 2);
        return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
    }
}
