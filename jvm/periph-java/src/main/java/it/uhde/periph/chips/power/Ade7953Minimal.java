package it.uhde.periph.chips.power;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * ADE7953 — single-phase multifunction metering IC (Analog Devices).
 *
 * <p>Reads the RMS voltage, RMS current on Current Channel A, and the
 * instantaneous / accumulated active power and energy. No register
 * configuration beyond the mandatory power-up sequence and the caller's
 * sensor-scaling constants is performed.
 *
 * <p>Fixed I²C address: 0x38.
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>PGA gains: all 1 (unity)</li>
 *   <li>HPF on, digital integrators off (shunt/CT mode)</li>
 *   <li>Energy registers reset on read (RSTREAD = 1)</li>
 *   <li>Calibration/offset registers at power-on defaults</li>
 * </ul>
 *
 * <p>The two calibration constants {@code voltageGain} and
 * {@code currentGain} are design-specific and must be supplied at
 * construction (no universal default).
 */
public class Ade7953Minimal {

    // 8-bit registers
    protected static final int REG_DISNOLOAD       = 0x001;
    protected static final int REG_PGA_V           = 0x007;
    protected static final int REG_PGA_IA          = 0x008;
    protected static final int REG_PGA_IB          = 0x009;
    protected static final int REG_VERSION         = 0x702;
    protected static final int REG_EX_REF          = 0x800;

    // 16-bit registers
    protected static final int REG_CONFIG          = 0x102;
    protected static final int REG_PFA             = 0x10A;
    protected static final int REG_PERIOD          = 0x10E;
    protected static final int REG_INTERNAL_RES    = 0x120;

    // 24-bit / 32-bit registers
    protected static final int REG_AWATT           = 0x212;
    protected static final int REG_VRMS            = 0x21C;
    protected static final int REG_AENERGYA        = 0x21E;
    protected static final int REG_OVLVL           = 0x224;
    protected static final int REG_OILVL           = 0x225;

    protected static final int REG_120_UNLOCK      = 0xFE;
    protected static final int REG_120_VALUE       = 0x30;

    protected static final double ADC_FS_VOLTS     = 0.5 / Math.sqrt(2.0);
    protected static final int    ADC_FS_CODE      = 9032007;
    protected static final int    POWER_FS_CODE    = 4862401;
    protected static final double T_SAMPLE         = 1.0 / 206900.0;
    protected static final double PF_LSB           = 1.0 / 32768.0;
    protected static final double ANGLE_LSB        = 1.0 / 223750.0;

    protected final Connection connection;
    protected final double voltageGain;
    protected double currentGainA;
    protected double currentGainB;
    protected int pgaA = 1;
    protected int pgaB = 1;
    protected int pgaV = 1;

    /**
     * Construct the ADE7953 driver.
     *
     * @param connection  I²C connection bound to the ADE7953 (fixed address 0x38).
     * @param voltageGain Real volts at the mains per volt at VP–VN.
     * @param currentGain Real amperes per volt at IAP–IAN (Current Channel A).
     * @throws IOException on I²C error
     */
    public Ade7953Minimal(Connection connection, double voltageGain, double currentGain) throws IOException {
        this.connection = connection;
        this.voltageGain = voltageGain;
        this.currentGainA = currentGain;
        this.currentGainB = currentGain;
        initChip();
    }

    private void initChip() throws IOException {
        sleep(110);
        writeReg8(REG_INTERNAL_RES, REG_120_UNLOCK);
        writeReg16(REG_INTERNAL_RES, REG_120_VALUE);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    protected int readReg24(int reg) throws IOException {
        byte[] addr = {(byte)((reg >> 8) & 0xFF), (byte)(reg & 0xFF)};
        byte[] b = connection.writeRead(addr, 3);
        return ((b[0] & 0xFF) << 16) | ((b[1] & 0xFF) << 8) | (b[2] & 0xFF);
    }

    protected int readReg24Signed(int reg) throws IOException {
        int v = readReg24(reg);
        if ((v & 0x800000) != 0) v -= 0x1000000;
        return v;
    }

    protected int readReg16(int reg) throws IOException {
        byte[] addr = {(byte)((reg >> 8) & 0xFF), (byte)(reg & 0xFF)};
        byte[] b = connection.writeRead(addr, 2);
        return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
    }

    protected int readReg16Signed(int reg) throws IOException {
        int v = readReg16(reg);
        if ((v & 0x8000) != 0) v -= 0x10000;
        return v;
    }

    protected void writeReg8(int reg, int value) throws IOException {
        byte[] buf = {(byte)((reg >> 8) & 0xFF), (byte)(reg & 0xFF), (byte)(value & 0xFF)};
        connection.write(buf);
    }

    protected void writeReg16(int reg, int value) throws IOException {
        byte[] buf = {(byte)((reg >> 8) & 0xFF), (byte)(reg & 0xFF),
                       (byte)((value >> 8) & 0xFF), (byte)(value & 0xFF)};
        connection.write(buf);
    }

    protected void writeReg24(int reg, int value) throws IOException {
        byte[] buf = {(byte)((reg >> 8) & 0xFF), (byte)(reg & 0xFF),
                       (byte)((value >> 16) & 0xFF),
                       (byte)((value >> 8) & 0xFF),
                       (byte)(value & 0xFF)};
        connection.write(buf);
    }

    private double voltageScale() {
        return (ADC_FS_VOLTS * voltageGain) / (ADC_FS_CODE * pgaV);
    }

    private double currentScale(double gain) {
        return (ADC_FS_VOLTS * gain) / ADC_FS_CODE;
    }

    private double powerScale(double gain) {
        return ((ADC_FS_VOLTS * ADC_FS_VOLTS) * voltageGain * gain) / POWER_FS_CODE;
    }

    private double energyScale(double gain) {
        return ((ADC_FS_VOLTS * ADC_FS_VOLTS) * voltageGain * gain * T_SAMPLE) / 3600.0;
    }

    /** Read the RMS voltage on the voltage channel.
     *  @return Voltage in volts. */
    public double voltage() throws IOException {
        return readReg24(REG_VRMS) * voltageScale();
    }

    /** Read the RMS current on Current Channel A.
     *  @return Current in amperes. */
    public double current() throws IOException {
        return readReg24(0x21A) * currentScale(currentGainA);
    }

    /** Read instantaneous active power on Current Channel A.
     *  @return Active power in watts (signed). */
    public double activePower() throws IOException {
        return readReg24Signed(REG_AWATT) * powerScale(currentGainA);
    }

    /** Read the active-energy accumulator for Current Channel A.
     *  @return Active energy in watt-hours accumulated since the previous call. */
    public double activeEnergy() throws IOException {
        return readReg24Signed(REG_AENERGYA) * energyScale(currentGainA);
    }
}