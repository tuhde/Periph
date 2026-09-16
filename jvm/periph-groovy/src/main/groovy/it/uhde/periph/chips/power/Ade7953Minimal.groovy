@CompileStatic
package it.uhde.periph.chips.power

import it.uhde.periph.connection.Connection

/**
 * ADE7953 — single-phase multifunction metering IC (Analog Devices).
 *
 * Reads the RMS voltage, RMS current on Current Channel A, and the
 * instantaneous / accumulated active power and energy. No register
 * configuration beyond the mandatory power-up sequence and the caller's
 * sensor-scaling constants is performed.
 *
 * Fixed I²C address: 0x38.
 */
class Ade7953Minimal implements Closeable {
    // 8-bit registers
    protected static final int REG_DISNOLOAD     = 0x001
    protected static final int REG_PGA_V         = 0x007
    protected static final int REG_PGA_IA        = 0x008
    protected static final int REG_PGA_IB        = 0x009
    protected static final int REG_VERSION       = 0x702

    // 16-bit registers
    protected static final int REG_CONFIG        = 0x102
    protected static final int REG_PFA           = 0x10A
    protected static final int REG_PERIOD        = 0x10E
    protected static final int REG_INTERNAL_RES  = 0x120

    // 24-bit registers
    protected static final int REG_AWATT         = 0x212
    protected static final int REG_VRMS          = 0x21C
    protected static final int REG_AENERGYA      = 0x21E
    protected static final int REG_OVLVL         = 0x224
    protected static final int REG_OILVL         = 0x225

    protected static final int REG_120_UNLOCK    = 0xFE
    protected static final int REG_120_VALUE     = 0x30

    protected static final double ADC_FS_VOLTS   = 0.5d / Math.sqrt(2.0d)
    protected static final int    ADC_FS_CODE    = 9032007
    protected static final int    POWER_FS_CODE  = 4862401
    protected static final double T_SAMPLE       = 1.0d / 206900.0d
    protected static final double PF_LSB         = 1.0d / 32768.0d
    protected static final double ANGLE_LSB      = 1.0d / 223750.0d

    protected final Connection connection
    protected final double voltageGain
    protected double currentGainA
    protected double currentGainB
    protected int pgaA = 1
    protected int pgaB = 1
    protected int pgaV = 1

    Ade7953Minimal(Connection connection, double voltageGain, double currentGain) {
        this.connection = connection
        this.voltageGain = voltageGain
        this.currentGainA = currentGain
        this.currentGainB = currentGain
        initChip()
    }

    Ade7953Minimal(Connection connection, double voltageGain) {
        this(connection, voltageGain, currentGain)
    }

    Ade7953Minimal(Connection connection) {
        this(connection, 251.0d, 30.0d)
    }

    private void initChip() {
        Thread.sleep(110)
        writeReg8(REG_INTERNAL_RES, REG_120_UNLOCK)
        writeReg16(REG_INTERNAL_RES, REG_120_VALUE)
    }

    @Override
    void close() {}

    protected int readReg24(int reg) {
        byte[] addr = [(byte)((reg >> 8) & 0xFF), (byte)(reg & 0xFF)] as byte[]
        byte[] b = connection.writeRead(addr, 3)
        return ((b[0] & 0xFF) << 16) | ((b[1] & 0xFF) << 8) | (b[2] & 0xFF)
    }

    protected int readReg24Signed(int reg) {
        int v = readReg24(reg)
        if ((v & 0x800000) != 0) v -= 0x1000000
        return v
    }

    protected int readReg16(int reg) {
        byte[] addr = [(byte)((reg >> 8) & 0xFF), (byte)(reg & 0xFF)] as byte[]
        byte[] b = connection.writeRead(addr, 2)
        return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF)
    }

    protected int readReg16Signed(int reg) {
        int v = readReg16(reg)
        if ((v & 0x8000) != 0) v -= 0x10000
        return v
    }

    protected void writeReg8(int reg, int value) {
        byte[] buf = [(byte)((reg >> 8) & 0xFF), (byte)(reg & 0xFF), (byte)(value & 0xFF)] as byte[]
        connection.write(buf)
    }

    protected void writeReg16(int reg, int value) {
        byte[] buf = [(byte)((reg >> 8) & 0xFF), (byte)(reg & 0xFF),
                       (byte)((value >> 8) & 0xFF), (byte)(value & 0xFF)] as byte[]
        connection.write(buf)
    }

    protected void writeReg24(int reg, int value) {
        byte[] buf = [(byte)((reg >> 8) & 0xFF), (byte)(reg & 0xFF),
                       (byte)((value >> 16) & 0xFF),
                       (byte)((value >> 8) & 0xFF),
                       (byte)(value & 0xFF)] as byte[]
        connection.write(buf)
    }

    private double voltageScale() {
        return (ADC_FS_VOLTS * voltageGain) / (ADC_FS_CODE * pgaV)
    }

    private double currentScale(double gain) {
        return (ADC_FS_VOLTS * gain) / ADC_FS_CODE
    }

    private double powerScale(double gain) {
        return ((ADC_FS_VOLTS * ADC_FS_VOLTS) * voltageGain * gain) / POWER_FS_CODE
    }

    private double energyScale(double gain) {
        return ((ADC_FS_VOLTS * ADC_FS_VOLTS) * voltageGain * gain * T_SAMPLE) / 3600.0d
    }

    /** Read the RMS voltage on the voltage channel.
     *  @return Voltage in volts. */
    double voltage() {
        return readReg24(REG_VRMS) * voltageScale()
    }

    /** Read the RMS current on Current Channel A.
     *  @return Current in amperes. */
    double current() {
        return readReg24(0x21A) * currentScale(currentGainA)
    }

    /** Read instantaneous active power on Current Channel A.
     *  @return Active power in watts (signed). */
    double activePower() {
        return readReg24Signed(REG_AWATT) * powerScale(currentGainA)
    }

    /** Read the active-energy accumulator for Current Channel A.
     *  @return Active energy in watt-hours accumulated since the previous call. */
    double activeEnergy() {
        return readReg24Signed(REG_AENERGYA) * energyScale(currentGainA)
    }
}