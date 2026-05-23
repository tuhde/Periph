package it.uhde.periph.chips.magnetometer

import it.uhde.periph.transport.Transport

/**
 * AS5600 — full driver. Extends [As5600Minimal] with raw angle, AGC,
 * magnitude, configuration, position/angle range management, and OTP burn
 * commands.
 *
 * ## Power mode constants
 * [PM_NOM], [PM_LPM1], [PM_LPM2], [PM_LPM3]
 *
 * ## Hysteresis constants
 * [HYST_OFF], [HYST_1LSB], [HYST_2LSB], [HYST_3LSB]
 *
 * ## Output stage constants
 * [OUTS_ANALOG], [OUTS_ANALOG_REDUCED], [OUTS_PWM]
 *
 * ## PWM frequency constants
 * [PWMF_115HZ], [PWMF_230HZ], [PWMF_460HZ], [PWMF_920HZ]
 *
 * ## Slow filter constants
 * [SF_16X], [SF_8X], [SF_4X], [SF_2X]
 *
 * ## Burn commands
 * [BURN_ANGLE], [BURN_SETTING]
 */
class As5600Full(transport: Transport) : As5600Minimal(transport) {

    companion object {
        // Power mode constants
        /** Power mode: normal (6.5 mA). */
        const val PM_NOM  = 0
        /** Power mode: low power 1 (3.4 mA, 5 ms poll). */
        const val PM_LPM1 = 1
        /** Power mode: low power 2 (1.8 mA, 20 ms poll). */
        const val PM_LPM2 = 2
        /** Power mode: low power 3 (1.5 mA, 100 ms poll). */
        const val PM_LPM3 = 3

        // Hysteresis constants
        /** Hysteresis: off. */
        const val HYST_OFF   = 0
        /** Hysteresis: 1 LSB. */
        const val HYST_1LSB  = 1
        /** Hysteresis: 2 LSBs. */
        const val HYST_2LSB  = 2
        /** Hysteresis: 3 LSBs. */
        const val HYST_3LSB  = 3

        // Output stage constants
        /** Output: analog 0–VDD. */
        const val OUTS_ANALOG         = 0
        /** Output: analog 10–90% VDD. */
        const val OUTS_ANALOG_REDUCED = 1
        /** Output: digital PWM. */
        const val OUTS_PWM            = 2

        // PWM frequency constants
        /** PWM frequency: 115 Hz. */
        const val PWMF_115HZ = 0
        /** PWM frequency: 230 Hz. */
        const val PWMF_230HZ = 1
        /** PWM frequency: 460 Hz. */
        const val PWMF_460HZ = 2
        /** PWM frequency: 920 Hz. */
        const val PWMF_920HZ = 3

        // Slow filter constants
        /** Slow filter: 16× (2.2 ms settle). */
        const val SF_16X = 0
        /** Slow filter: 8× (1.1 ms settle). */
        const val SF_8X  = 1
        /** Slow filter: 4× (0.55 ms settle). */
        const val SF_4X  = 2
        /** Slow filter: 2× (0.286 ms settle). */
        const val SF_2X  = 3

        // Burn commands
        /** Burn command: permanently store ZPOS+MPOS to OTP. */
        const val BURN_ANGLE   = 0x80
        /** Burn command: permanently store MANG+CONF to OTP. */
        const val BURN_SETTING = 0x40
    }

    /**
     * Read the unscaled raw angle count (0–4095).
     *
     * Reads the RAW_ANGLE register (0x0C-0x0D), which is unaffected by
     * ZPOS/MPOS programming. Represents the physical angle over 0–360°.
     *
     * @return raw angle count, 0–4095
     */
    fun rawAngle(): Int = readReg12(REG_RAW_ANGLE_H)

    /**
     * Read the unscaled raw angle in degrees.
     *
     * @return raw angle in degrees, 0.0–360.0
     */
    fun rawAngleDegrees(): Double = rawAngle() * 360.0 / 4096.0

    /**
     * Read the automatic gain control value.
     *
     * In 5 V mode the range is 0–255; in 3.3 V mode it is 0–127.
     * Mid-range indicates optimal airgap.
     *
     * @return AGC value (0–255 for 5 V, 0–127 for 3.3 V)
     */
    fun agc(): Int = readReg8(REG_AGC)

    /**
     * Read the 12-bit CORDIC magnitude value.
     *
     * @return magnitude, 0–4095
     */
    fun magnitude(): Int = readReg12(REG_MAGNITUDE_H)

    /**
     * Read the raw STATUS register byte.
     *
     * @return raw STATUS byte (MH bit 5, ML bit 4, MD bit 3)
     */
    fun statusByte(): Int = readReg8(REG_STATUS)

    /**
     * Write the CONF register (14 bits split across CONF_H and CONF_L).
     *
     * Reads the current CONF_H/CONF_L values first to preserve the
     * reserved bits in CONF_H[7:6], then masks in the new field values.
     *
     * @param pm    power mode (0–3, use [PM_*][PM_NOM] constants)
     * @param hyst  hysteresis (0–3, use [HYST_*][HYST_OFF] constants)
     * @param outs  output stage (0–2, use [OUTS_*][OUTS_ANALOG] constants)
     * @param pwmf  PWM frequency (0–3, use [PWMF_*][PWMF_115HZ] constants)
     * @param sf    slow filter (0–3, use [SF_*][SF_16X] constants)
     * @param fth   fast filter threshold (0–7)
     * @param wd    watchdog enable (true=on, false=off)
     */
    fun configure(pm: Int, hyst: Int, outs: Int, pwmf: Int, sf: Int, fth: Int, wd: Boolean) {
        var confH = readReg8(REG_CONF_H)
        var confL = readReg8(REG_CONF_L)

        confH = (confH and 0xC0) or ((fth and 0x07) shl 2) or (sf and 0x03)
        if (wd) confH = confH or 0x20 else confH = confH and 0xDF.inv()

        confL = ((pwmf and 0x03) shl 6) or ((outs and 0x03) shl 4) or ((hyst and 0x03) shl 2) or (pm and 0x03)

        transport.write(byteArrayOf(
            REG_CONF_H.toByte(),
            (confH and 0xFF).toByte(),
            (confL and 0xFF).toByte()
        ))
    }

    /**
     * Set the zero (start) position.
     *
     * Writes to volatile RAM only; lost on power cycle unless followed by
     * [burnAngle]. Takes effect ≥1 ms after the write.
     *
     * @param pos zero position, 0–4095
     */
    fun setZeroPosition(pos: Int) { writeReg12(REG_ZPOS_H, REG_ZPOS_L, pos) }

    /**
     * Set the maximum (stop) position.
     *
     * Writes to volatile RAM only; lost on power cycle unless followed by
     * [burnAngle]. Takes effect ≥1 ms after the write.
     *
     * @param pos maximum position, 0–4095
     */
    fun setMaxPosition(pos: Int) { writeReg12(REG_MPOS_H, REG_MPOS_L, pos) }

    /**
     * Set the maximum angle span.
     *
     * Writes to volatile RAM only; lost on power cycle unless followed by
     * [burnSetting]. The span must correspond to ≥18° (≥204 counts).
     *
     * @param span angle span, 0–4095
     */
    fun setMaxAngle(span: Int) { writeReg12(REG_MANG_H, REG_MANG_L, span) }

    /**
     * Read the zero (start) position.
     *
     * @return zero position, 0–4095
     */
    fun zeroPosition(): Int = readReg12(REG_ZPOS_H)

    /**
     * Read the maximum (stop) position.
     *
     * @return maximum position, 0–4095
     */
    fun maxPosition(): Int = readReg12(REG_MPOS_H)

    /**
     * Read the maximum angle span.
     *
     * @return angle span, 0–4095
     */
    fun maxAngle(): Int = readReg12(REG_MANG_H)

    /**
     * Read the OTP burn count for ZPOS/MPOS.
     *
     * @return number of permanent ZPOS/MPOS writes already performed (0–3)
     */
    fun burnCount(): Int = readReg8(REG_ZMCO) and 0x03

    /**
     * Permanently burn ZPOS and MPOS to OTP.
     *
     * Requires MD=1 (magnet present) and ZMCO<3. After burning, the
     * standard OTP verification sequence (0x01, 0x11, 0x10 to 0xFF) is
     * executed to reload and verify.
     *
     * @throws IllegalStateException if MD=0 or ZMCO≥3
     */
    fun burnAngle() {
        val status = readReg8(REG_STATUS)
        if ((status and STATUS_MD) == 0) {
            throw IllegalStateException("AS5600: burn_angle requires magnet detected (MD=1)")
        }
        if (burnCount() >= 3) {
            throw IllegalStateException("AS5600: burn_angle failed, ZMCO=3 (no remaining writes)")
        }
        writeReg8(REG_BURN, BURN_ANGLE)
        Thread.sleep(2)
        // OTP verification sequence
        writeReg8(REG_BURN, 0x01)
        writeReg8(REG_BURN, 0x11)
        writeReg8(REG_BURN, 0x10)
    }

    /**
     * Permanently burn MANG and CONF to OTP.
     *
     * Requires ZMCO=0 (ZPOS/MPOS never burned). Can only be executed once.
     * After burning, the standard OTP verification sequence is executed.
     *
     * @throws IllegalStateException if ZMCO≠0
     */
    fun burnSetting() {
        if (burnCount() != 0) {
            throw IllegalStateException("AS5600: burn_setting requires ZMCO=0")
        }
        writeReg8(REG_BURN, BURN_SETTING)
        Thread.sleep(2)
        // OTP verification sequence
        writeReg8(REG_BURN, 0x01)
        writeReg8(REG_BURN, 0x11)
        writeReg8(REG_BURN, 0x10)
    }
}
