package it.uhde.periph.chips.power

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/**
 * MAX17048 / MAX17049 lithium fuel gauge (ModelGauge) — minimal driver.
 *
 * <p>Reports the cell voltage and the state of charge. The constructor makes no bus access; the
 * chip runs with its power-on defaults. The first SOC result is valid about 1 s after power-up.
 *
 * <p>I²C address: 0x36 (fixed). All registers are 16-bit big-endian words.
 */
@CompileStatic
class Max17048Minimal {

    protected static final int REG_VCELL   = 0x02
    protected static final int REG_SOC     = 0x04
    protected static final int REG_MODE    = 0x06
    protected static final int REG_VERSION = 0x08
    protected static final int REG_HIBRT   = 0x0A
    protected static final int REG_CONFIG  = 0x0C
    protected static final int REG_VALRT   = 0x14
    protected static final int REG_CRATE   = 0x16
    protected static final int REG_VRESET  = 0x18
    protected static final int REG_STATUS  = 0x1A
    protected static final int REG_CMD     = 0xFE

    protected final RegisterConnection connection
    protected final int cellCount

    /**
     * Construct the driver. Makes no bus access.
     *
     * @param connection I²C connection bound to address 0x36
     * @param cells 1 for the MAX17048, 2 for the MAX17049 (scales the voltage)
     */
    Max17048Minimal(RegisterConnection connection, int cells = 1) {
        this.connection = connection
        this.cellCount = cells == 2 ? 2 : 1
    }

    /** @return voltage in V ((VCELL >> 4) × 1.25 mV × cells) */
    double voltage() { (readWord(REG_VCELL) >> 4) * 1.25e-3d * cellCount }

    /** @return state of charge in %, clamped to 0.0–100.0 (SOC / 256) */
    double percent() { Math.max(0.0d, Math.min(100.0d, readWord(REG_SOC) / 256.0d)) }

    /** Read a 16-bit big-endian register. */
    protected int readWord(int reg) {
        byte[] b = connection.read(reg, 2)
        ((b[0] & 0xFF) << 8) | (b[1] & 0xFF)
    }

    /** Write a 16-bit big-endian register. */
    protected void writeWord(int reg, int value) {
        connection.write(reg, [(byte) (value >> 8), (byte) value] as byte[])
    }

    /** Read-modify-write a register: clear the {@code clear} bits, then set the {@code set} bits. */
    protected void modify(int reg, int clear, int set) {
        writeWord(reg, (readWord(reg) & ~clear & 0xFFFF) | set)
    }
}
