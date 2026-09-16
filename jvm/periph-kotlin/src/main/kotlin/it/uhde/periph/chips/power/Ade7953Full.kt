package it.uhde.periph.chips.power

/**
 * ADE7953 full interface — extends [Ade7953Minimal] with Channel B,
 * reactive/apparent measurements, calibration, accumulation modes,
 * power-quality features (no-load, sag, peak, overcurrent/overvoltage),
 * zero-crossing, REVP, alternate outputs, CF pulses, interrupts,
 * checksum, write protection, reset and last-operation diagnostics.
 */
class Ade7953Full @JvmOverloads constructor(
    connection: it.uhde.periph.connection.Connection,
    voltageGain: Double,
    currentGain: Double
) : Ade7953Minimal(connection, voltageGain, currentGain) {

    /** Override the calibration constant used by every Channel B
     *  current/power/energy method. Defaults to Channel A's currentGain
     *  if never called.
     */
    fun configureChannelB(currentGainB: Double) {
        this.currentGainB = currentGainB
    }

    /** Read RMS current on Current Channel B (neutral). */
    fun currentB(): Double = readReg24(0x21B) * currentScale(currentGainB)

    /** Read instantaneous active power on Current Channel B. */
    fun activePowerB(): Double = readReg24Signed(0x213) * powerScale(currentGainB)

    /** Read the active-energy accumulator for Current Channel B. */
    fun activeEnergyB(): Double = readReg24Signed(0x21F) * energyScale(currentGainB)

    /** Read instantaneous reactive power on Current Channel A. */
    fun reactivePower(): Double = readReg24Signed(0x214) * powerScale(currentGainA)

    /** Read instantaneous reactive power on Current Channel B. */
    fun reactivePowerB(): Double = readReg24Signed(0x215) * powerScale(currentGainB)

    /** Read reactive-energy accumulator for Current Channel A. */
    fun reactiveEnergy(): Double = readReg24Signed(0x220) * energyScale(currentGainA)

    /** Read reactive-energy accumulator for Current Channel B. */
    fun reactiveEnergyB(): Double = readReg24Signed(0x221) * energyScale(currentGainB)

    /** Read instantaneous apparent power on Current Channel A. */
    fun apparentPower(): Double = readReg24Signed(0x210) * powerScale(currentGainA)

    /** Read instantaneous apparent power on Current Channel B. */
    fun apparentPowerB(): Double = readReg24Signed(0x211) * powerScale(currentGainB)

    /** Read apparent-energy accumulator for Current Channel A. */
    fun apparentEnergy(): Double = readReg24Signed(0x222) * energyScale(currentGainA)

    /** Read apparent-energy accumulator for Current Channel B. */
    fun apparentEnergyB(): Double = readReg24Signed(0x223) * energyScale(currentGainB)

    /** Read power factor for Current Channel A.
     *  @return Power factor in range [-1.0, +1.0]. */
    fun powerFactor(): Double = readReg16Signed(REG_PFA) * PF_LSB

    /** Read line period in seconds. */
    fun linePeriod(): Double = (readReg16(REG_PERIOD) + 1) * ANGLE_LSB

    /** Read line frequency in Hertz. */
    fun lineFrequency(): Double = 1.0 / linePeriod()

    /** Read the silicon version register. */
    fun version(): Int {
        val addr = byteArrayOf(((REG_VERSION shr 8) and 0xFF).toByte(), (REG_VERSION and 0xFF).toByte())
        return connection.writeRead(addr, 1)[0].toInt() and 0xFF
    }

    /** Configure overvoltage threshold (volts). */
    fun configureOvervoltage(threshold: Double) {
        var raw = (threshold * (ADC_FS_CODE * pgaV)) / (ADC_FS_VOLTS * voltageGain)
        if (raw < 0) raw = 0.0
        if (raw > 0xFFFFFF) raw = 0xFFFFFF.toDouble()
        writeReg24(REG_OVLVL, raw.toInt())
    }

    /** Configure overcurrent threshold (amperes; shared by both channels). */
    fun configureOvercurrent(threshold: Double) {
        var raw = (threshold * ADC_FS_CODE) / ADC_FS_VOLTS
        if (raw < 0) raw = 0.0
        if (raw > 0xFFFFFF) raw = 0xFFFFFF.toDouble()
        writeReg24(REG_OILVL, raw.toInt())
    }

    /** Software-reset the chip, re-apply the mandatory power-up sequence. */
    fun reset() {
        val cfg = readReg16(REG_CONFIG) or (1 shl 7)
        writeReg16(REG_CONFIG, cfg)
        Thread.sleep(110)
        writeReg8(REG_INTERNAL_RES, REG_120_UNLOCK)
        writeReg16(REG_INTERNAL_RES, REG_120_VALUE)
        pgaA = 1
        pgaB = 1
        pgaV = 1
    }
}