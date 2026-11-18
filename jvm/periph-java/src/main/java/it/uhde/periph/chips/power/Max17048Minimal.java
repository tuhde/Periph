package it.uhde.periph.chips.power;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * MAX17048 / MAX17049 lithium fuel gauge (ModelGauge) — minimal driver.
 *
 * <p>Reports the cell voltage and the state of charge. The constructor makes no bus access; the
 * chip runs with its power-on defaults. The first SOC result is valid about 1 s after power-up.
 *
 * <p>Fixed I²C address: 0x36. All registers are 16-bit big-endian words.
 */
public class Max17048Minimal {

    // --- Register addresses ---
    protected static final int REG_VCELL   = 0x02;
    protected static final int REG_SOC     = 0x04;
    protected static final int REG_MODE    = 0x06;
    protected static final int REG_VERSION = 0x08;
    protected static final int REG_HIBRT   = 0x0A;
    protected static final int REG_CONFIG  = 0x0C;
    protected static final int REG_VALRT   = 0x14;
    protected static final int REG_CRATE   = 0x16;
    protected static final int REG_VRESET  = 0x18;
    protected static final int REG_STATUS  = 0x1A;
    protected static final int REG_CMD     = 0xFE;

    protected final RegisterConnection connection;
    protected final int cells;

    /**
     * Construct the driver. Makes no bus access.
     *
     * @param connection I²C connection bound to address 0x36
     * @param cells      1 for the MAX17048, 2 for the MAX17049 (scales the voltage)
     * @throws IOException on I²C error
     */
    public Max17048Minimal(RegisterConnection connection, int cells) throws IOException {
        this.connection = connection;
        this.cells = cells == 2 ? 2 : 1;
    }

    /**
     * Construct the driver for a single cell (MAX17048).
     *
     * @param connection I²C connection bound to address 0x36
     * @throws IOException on I²C error
     */
    public Max17048Minimal(RegisterConnection connection) throws IOException {
        this(connection, 1);
    }

    /**
     * Read the cell voltage.
     *
     * @return voltage in V ((VCELL &gt;&gt; 4) × 1.25 mV × cells)
     * @throws IOException on I²C error
     */
    public double voltage() throws IOException {
        return (readWord(REG_VCELL) >> 4) * 1.25e-3 * cells;
    }

    /**
     * Read the state of charge.
     *
     * @return state of charge in %, clamped to 0.0–100.0 (SOC / 256)
     * @throws IOException on I²C error
     */
    public double percent() throws IOException {
        return Math.max(0.0, Math.min(100.0, readWord(REG_SOC) / 256.0));
    }

    /**
     * Read a 16-bit big-endian register.
     *
     * @param reg register address
     * @return unsigned 16-bit value
     * @throws IOException on I²C error
     */
    protected int readWord(int reg) throws IOException {
        byte[] b = connection.read(reg, 2);
        return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
    }

    /**
     * Write a 16-bit big-endian register.
     *
     * @param reg   register address
     * @param value 16-bit value
     * @throws IOException on I²C error
     */
    protected void writeWord(int reg, int value) throws IOException {
        connection.write(reg, new byte[]{(byte) (value >> 8), (byte) value});
    }

    /**
     * Read-modify-write a register.
     *
     * @param reg   register address
     * @param clear bits to clear
     * @param set   bits to set
     * @throws IOException on I²C error
     */
    protected void modify(int reg, int clear, int set) throws IOException {
        writeWord(reg, (readWord(reg) & ~clear & 0xFFFF) | set);
    }
}
