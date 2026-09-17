package it.uhde.periph.chips.magnetometer

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * HMC5883L — 3-axis magnetometer (minimal driver).
 *
 * <p>Reads magnetic field on all three axes in continuous mode with sensible
 * defaults baked in. The chip has a fixed I²C address of 0x1E.
 *
 * <h2>Default behaviour (baked into Minimal):</h2>
 * <ul>
 *   <li>Averaging: 8 samples (MA=11)</li>
 *   <li>ODR: 15 Hz (DO=100)</li>
 *   <li>Gain: ±1.3 Ga (GN=001), 1090 LSb/Gauss</li>
 *   <li>Mode: continuous measurement</li>
 * </ul>
 */
@CompileStatic
class Hmc5883lMinimal {

    // --- Register addresses ---
    protected static final int REG_CONFIG_A    = 0x00
    protected static final int REG_CONFIG_B    = 0x01
    protected static final int REG_MODE        = 0x02
    protected static final int REG_DATA_X_MSB  = 0x03
    protected static final int REG_STATUS      = 0x09
    protected static final int REG_ID_A        = 0x0A
    protected static final int REG_ID_B        = 0x0B
    protected static final int REG_ID_C        = 0x0C

    // --- Fixed I²C address ---
    protected static final int I2C_ADDR = 0x1E

    // --- Gain table: LSb per Gauss ---
    protected static final int[] GAIN_LSB_PER_GAUSS = [
        1370, // GN=0: ±0.88 Ga
        1090, // GN=1: ±1.3 Ga (default)
        820,  // GN=2: ±1.9 Ga
        660,  // GN=3: ±2.5 Ga
        440,  // GN=4: ±4.0 Ga
        390,  // GN=5: ±4.7 Ga
        330,  // GN=6: ±5.6 Ga
        230   // GN=7: ±8.1 Ga
    ]

    protected final Connection connection
    protected int gain = 1
    protected int gainLsbPerGauss = GAIN_LSB_PER_GAUSS[1]

    /**
     * Construct the driver and initialise with default configuration.
     *
     * <p>Writes Config A, Config B, and Mode registers, then waits 6 ms for
     * the first measurement to become available.
     *
     * @param connection I²C connection bound to address 0x1E
     */
    Hmc5883lMinimal(Connection connection) {
        this.connection = connection
        initMinimal()
    }

    protected void initMinimal() {
        writeReg8(REG_CONFIG_A, 0x70)  // 8 avg, 15 Hz, normal
        writeReg8(REG_CONFIG_B, 0x20)  // gain=1 (±1.3 Ga)
        writeReg8(REG_MODE, 0x00)      // continuous mode
        Thread.sleep(6)
    }

    /**
     * Read magnetic field on all three axes.
     *
     * <p>Performs a 6-byte burst read from register 0x03 (X MSB). The output
     * register byte order is X, Z, Y (not X, Y, Z).
     *
     * @return array [x, y, z] magnetic field strength in Tesla.
     *         Returns null for any axis that overflows (raw == -4096).
     */
    Double[] magneticField() {
        byte[] raw = connection.writeRead([(byte) REG_DATA_X_MSB] as byte[], 6)
        int rawX = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF)
        int rawZ = ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF)
        int rawY = ((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF)
        // Convert to signed 16-bit
        rawX = rawX as short
        rawZ = rawZ as short
        rawY = rawY as short

        Double[] result = new Double[3]
        result[0] = rawToTesla(rawX)
        result[1] = rawToTesla(rawY)
        result[2] = rawToTesla(rawZ)
        return result
    }

    protected Double rawToTesla(int raw) {
        if (raw == -4096) return null
        (raw / (double) gainLsbPerGauss) * 1e-4  // Gauss → Tesla
    }

    // ---- low-level helpers ----

    protected void writeReg8(int reg, int val) {
        connection.write([(byte) reg, (byte) (val & 0xFF)] as byte[])
    }

    protected int readReg8(int reg) {
        byte[] b = connection.writeRead([(byte) reg] as byte[], 1)
        b[0] & 0xFF
    }

    protected int readReg16(int regHi) {
        byte[] b = connection.writeRead([(byte) regHi] as byte[], 2)
        ((b[0] & 0xFF) << 8) | (b[1] & 0xFF)
    }

    protected int readReg16Signed(int regHi) {
        byte[] b = connection.writeRead([(byte) regHi] as byte[], 2)
        ((b[0] & 0xFF) << 8 | (b[1] & 0xFF)) as short
    }
}