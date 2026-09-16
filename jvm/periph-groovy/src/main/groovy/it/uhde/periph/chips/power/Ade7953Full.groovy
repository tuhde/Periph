package it.uhde.periph.chips.power

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * ADE7953 full interface — extends {@link Ade7953Minimal} with Channel B,
 * reactive/apparent measurements, calibration, accumulation modes,
 * power-quality features (no-load, sag, peak, overcurrent/overvoltage),
 * zero-crossing, REVP, alternate outputs, CF pulses, interrupts,
 * checksum, write protection, reset and last-operation diagnostics.
 */
@CompileStatic
class Ade7953Full extends Ade7953Minimal {

    Ade7953Full(Connection connection, double voltageGain, double currentGain) {
        super(connection, voltageGain, currentGain)
    }

    Ade7953Full(Connection connection, double voltageGain) {
        super(connection, voltageGain)
    }

    Ade7953Full(Connection connection) {
        super(connection)
    }

    /** Override the calibration constant used by every Channel B
     *  current/power/energy method. Defaults to Channel A's currentGain
     *  if never called.
     */
    void configureChannelB(double currentGainB) {
        this.currentGainB = currentGainB
    }

    /** Read RMS current on Current Channel B (neutral). */
    double currentB() {
        return readReg24(0x21B) * currentScale(currentGainB)
    }

    /** Read instantaneous active power on Current Channel B. */
    double activePowerB() {
        return readReg24Signed(0x213) * powerScale(currentGainB)
    }

    /** Read the active-energy accumulator for Current Channel B. */
    double activeEnergyB() {
        return readReg24Signed(0x21F) * energyScale(currentGainB)
    }

    /** Read instantaneous reactive power on Current Channel A. */
    double reactivePower() {
        return readReg24Signed(0x214) * powerScale(currentGainA)
    }

    /** Read instantaneous reactive power on Current Channel B. */
    double reactivePowerB() {
        return readReg24Signed(0x215) * powerScale(currentGainB)
    }

    /** Read reactive-energy accumulator for Current Channel A. */
    double reactiveEnergy() {
        return readReg24Signed(0x220) * energyScale(currentGainA)
    }

    /** Read reactive-energy accumulator for Current Channel B. */
    double reactiveEnergyB() {
        return readReg24Signed(0x221) * energyScale(currentGainB)
    }

    /** Read instantaneous apparent power on Current Channel A. */
    double apparentPower() {
        return readReg24Signed(0x210) * powerScale(currentGainA)
    }

    /** Read instantaneous apparent power on Current Channel B. */
    double apparentPowerB() {
        return readReg24Signed(0x211) * powerScale(currentGainB)
    }

    /** Read apparent-energy accumulator for Current Channel A. */
    double apparentEnergy() {
        return readReg24Signed(0x222) * energyScale(currentGainA)
    }

    /** Read apparent-energy accumulator for Current Channel B. */
    double apparentEnergyB() {
        return readReg24Signed(0x223) * energyScale(currentGainB)
    }

    /** Read power factor for Current Channel A.
     *  @return Power factor in range [-1.0, +1.0]. */
    double powerFactor() {
        return readReg16Signed(REG_PFA) * PF_LSB
    }

    /** Read line period in seconds. */
    double linePeriod() {
        return (readReg16(REG_PERIOD) + 1) * ANGLE_LSB
    }

    /** Read line frequency in Hertz. */
    double lineFrequency() {
        return 1.0d / linePeriod()
    }

    /** Read the silicon version register. */
    int version() {
        byte[] addr = [(byte)((REG_VERSION >> 8) & 0xFF), (byte)(REG_VERSION & 0xFF)] as byte[]
        return connection.writeRead(addr, 1)[0] & 0xFF
    }

    /** Configure overvoltage threshold (volts). */
    void configureOvervoltage(double threshold) {
        double raw = (threshold * (ADC_FS_CODE * pgaV)) / (ADC_FS_VOLTS * voltageGain)
        if (raw < 0) raw = 0
        if (raw > 0xFFFFFF) raw = 0xFFFFFF
        writeReg24(REG_OVLVL, (int) raw)
    }

    /** Configure overcurrent threshold (amperes; shared by both channels). */
    void configureOvercurrent(double threshold) {
        double raw = (threshold * ADC_FS_CODE) / ADC_FS_VOLTS
        if (raw < 0) raw = 0
        if (raw > 0xFFFFFF) raw = 0xFFFFFF
        writeReg24(REG_OILVL, (int) raw)
    }

    /** Software-reset the chip, re-apply the mandatory power-up sequence. */
    void reset() {
        int cfg = readReg16(REG_CONFIG) | (1 << 7)
        writeReg16(REG_CONFIG, cfg)
        Thread.sleep(110)
        writeReg8(REG_120_UNLOCK_ADDR, REG_120_UNLOCK)
        writeReg16(REG_INTERNAL_RES, REG_120_VALUE)
        pgaA = 1
        pgaB = 1
        pgaV = 1
    }
}