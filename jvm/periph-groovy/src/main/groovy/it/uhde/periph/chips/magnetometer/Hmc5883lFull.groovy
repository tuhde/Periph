package it.uhde.periph.chips.magnetometer

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * HMC5883L — full driver. Extends {@link Hmc5883lMinimal} with configuration,
 * single-shot mode, self-test, identification, and status access.
 */
@CompileStatic
class Hmc5883lFull extends Hmc5883lMinimal {

    /**
     * Construct the full driver and initialise with default configuration.
     *
     * @param connection I²C connection bound to address 0x1E
     */
    Hmc5883lFull(Connection connection) {
        super(connection)
    }

    /**
     * Write Configuration Registers A and B.
     *
     * @param odr        Data output rate in Hz (continuous mode). Valid: 0.75, 1.5, 3, 7.5, 15, 30, 75.
     * @param averaging  Samples averaged per output. Valid: 1, 2, 4, 8.
     * @param gain       Gain index 0–7.
     */
    void configure(double odr, int averaging, int gain) {
        int ma = -1, doBits = -1

        switch (averaging) {
            case 1:  ma = 0b00; break
            case 2:  ma = 0b01; break
            case 4:  ma = 0b10; break
            case 8:  ma = 0b11; break
            default: throw new IllegalArgumentException("averaging must be 1, 2, 4, or 8")
        }

        if (Math.abs(odr - 0.75d) < 0.01d)       doBits = 0b000
        else if (Math.abs(odr - 1.5d) < 0.01d)   doBits = 0b001
        else if (Math.abs(odr - 3.0d) < 0.01d)   doBits = 0b010
        else if (Math.abs(odr - 7.5d) < 0.01d)   doBits = 0b011
        else if (Math.abs(odr - 15.0d) < 0.01d)  doBits = 0b100
        else if (Math.abs(odr - 30.0d) < 0.01d)  doBits = 0b101
        else if (Math.abs(odr - 75.0d) < 0.01d)  doBits = 0b110
        else throw new IllegalArgumentException("odr must be 0.75, 1.5, 3, 7.5, 15, 30, or 75")

        if (gain < 0 || gain > 7) {
            throw new IllegalArgumentException("gain must be 0–7")
        }

        int configA = (ma << 5) | (doBits << 2)
        writeReg8(REG_CONFIG_A, configA)

        int configB = (gain << 5)
        writeReg8(REG_CONFIG_B, configB)

        this.gain = gain
        this.gainLsbPerGauss = GAIN_LSB_PER_GAUSS[gain]
    }

    /**
     * Update the gain setting (GN bits in Config B).
     *
     * @param gain Gain index 0–7.
     */
    void setGain(int gain) {
        if (gain < 0 || gain > 7) {
            throw new IllegalArgumentException("gain must be 0–7")
        }
        writeReg8(REG_CONFIG_B, gain << 5)
        this.gain = gain
        this.gainLsbPerGauss = GAIN_LSB_PER_GAUSS[gain]
    }

    /**
     * Set the operating mode.
     *
     * @param mode "continuous", "single", or "idle".
     */
    void setMode(String mode) {
        int md
        if ("continuous".equals(mode))      md = 0b00
        else if ("single".equals(mode))     md = 0b01
        else if ("idle".equals(mode))       md = 0b10
        else throw new IllegalArgumentException("mode must be 'continuous', 'single', or 'idle'")
        writeReg8(REG_MODE, md)
    }

    /**
     * Check if new measurement data is ready.
     *
     * @return true if RDY bit is set in Status Register.
     */
    boolean dataReady() {
        (readReg8(REG_STATUS) & 0x01) != 0
    }

    /**
     * Read the raw Status Register.
     *
     * @return Raw STATUS register byte (RDY in bit 0, LOCK in bit 1).
     */
    int status() {
        readReg8(REG_STATUS)
    }

    /**
     * Take a single measurement in single-shot mode.
     *
     * <p>Writes single-measurement mode, waits 6 ms, then reads all three axes.
     *
     * @return array [x, y, z] magnetic field strength in Tesla.
     *         Returns null for any axis that overflows (raw == -4096).
     */
    Double[] singleMeasurement() {
        writeReg8(REG_MODE, 0x01)
        Thread.sleep(6)
        magneticField()
    }

    /**
     * Read the identification registers.
     *
     * @return array [id_a, id_b, id_c] — expected (0x48, 0x34, 0x33) = ASCII "H43".
     */
    int[] identify() {
        int idA = readReg8(REG_ID_A)
        int idB = readReg8(REG_ID_B)
        int idC = readReg8(REG_ID_C)
        [idA, idB, idC] as int[]
    }

    /**
     * Run self-test with positive or negative bias.
     *
     * @param positive true for positive bias (MS=01), false for negative bias (MS=10).
     * @return array [x, y, z] magnetic field deflection in Tesla during self-test.
     *         Returns null for any axis that overflows.
     */
    Double[] selfTest(boolean positive) {
        int configA = readReg8(REG_CONFIG_A)
        int ms = positive ? 0b01 : 0b10
        writeReg8(REG_CONFIG_A, (configA & 0xFC) | ms)

        writeReg8(REG_MODE, 0x01)
        Thread.sleep(6)
        Double[] result = magneticField()

        writeReg8(REG_CONFIG_A, (configA & 0xFC) | 0b00)
        return result
    }
}