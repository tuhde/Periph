package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.Connection

/**
 * DS1881 — full interface: extends [DS1881Minimal] with stereo writes,
 * Option 2, zero-crossing and EEPROM control, and table helpers.
 *
 * The driver caches the configuration (volatile, zero-crossing, option);
 * [init] and [getConfig] refresh it from the chip.
 *
 * @param connection configured I²C connection bound to the device (0x28–0x2F)
 */
class DS1881Full(connection: Connection) : DS1881Minimal(connection) {

    /**
     * Decoded configuration register.
     *
     * @property option 1 (63 positions + mute, 1 dB steps) or 2 (33 positions + mute)
     * @property zeroCrossing zero-crossing detection enabled
     * @property nonvolatile wiper writes are stored in EEPROM
     * @property raw raw configuration byte as read
     */
    data class Config(val option: Int, val zeroCrossing: Boolean, val nonvolatile: Boolean, val raw: Int)

    override fun dbToPos(db: Double): Int =
        if (db >= 70) mutePosition() else nearestPosition(db)

    /**
     * Write both wipers in one transaction (no STOP between them).
     *
     * @param position0 position for channel 0 (clamped)
     * @param position1 position for channel 1 (clamped)
     */
    fun setBoth(position0: Int, position1: Int) {
        val mute = mutePosition()
        val p = intArrayOf(position0, position1)
        val data = ByteArray(2) { ch -> ((ch shl 6) or maxOf(0, minOf(p[ch], mute))).toByte() }
        writeCommands(data)
    }

    /**
     * Set both channels' attenuation (stereo volume).
     *
     * @param db0 attenuation of channel 0 in dB
     * @param db1 attenuation of channel 1 in dB; defaults to [db0]
     */
    fun setAttenuationBoth(db0: Double, db1: Double = db0) {
        setBoth(dbToPos(db0), dbToPos(db1))
    }

    /**
     * Write the configuration byte if it differs from the cached value. The
     * configuration is stored in EEPROM, so a write waits for acknowledge
     * polling. The wipers are not rewritten — positions keep their numeric
     * value when the option changes.
     *
     * @param option 1 (63 positions + mute) or 2 (33 positions + mute, DS1808 compatible)
     * @param zeroCrossing enable zero-crossing detection
     * @param nonvolatile store wiper writes in EEPROM and restore them at power-up (50 000 writes)
     */
    fun configure(option: Int = 1, zeroCrossing: Boolean = true, nonvolatile: Boolean = false) {
        require(option == 1 || option == 2) { "option must be 1 or 2" }
        var cfg = 0
        if (!nonvolatile) cfg = cfg or CFG_VOLATILE
        if (zeroCrossing) cfg = cfg or CFG_ZC
        if (option == 2) cfg = cfg or CFG_OPTION2
        if (cfg != cfgBits) {
            connection.write(byteArrayOf((CMD_CONFIG or cfg).toByte()))
            doWaitReady(0.05)
        }
        cfgBits = cfg
        optionNo = option
    }

    /**
     * Read the configuration register (fresh read; refreshes the cache).
     *
     * @return the decoded configuration
     */
    fun getConfig(): Config {
        val raw = readAllRaw()[2]
        cfgBits = raw and CFG_MASK
        optionNo = if ((raw and CFG_OPTION2) != 0) 2 else 1
        return Config(optionNo, (raw and CFG_ZC) != 0, (raw and CFG_VOLATILE) == 0, raw)
    }

    /**
     * Switch between Option 1 and 2 (positions are not converted).
     *
     * @param option 1 or 2
     */
    fun setOption(option: Int) {
        configure(option, (cfgBits and CFG_ZC) != 0, (cfgBits and CFG_VOLATILE) == 0)
    }

    /**
     * Enable or disable zero-crossing detection.
     *
     * @param enabled true to enable
     */
    fun setZeroCrossing(enabled: Boolean) {
        configure(optionNo, enabled, (cfgBits and CFG_VOLATILE) == 0)
    }

    /**
     * Select where wiper writes go (V/NV bit).
     *
     * @param enabled true: subsequent wiper writes are stored in EEPROM and restored at power-up (50 000 writes)
     */
    fun setNonvolatile(enabled: Boolean) {
        configure(optionNo, (cfgBits and CFG_ZC) != 0, enabled)
    }

    /**
     * Persist the current wiper positions to EEPROM: read both positions,
     * switch to nonvolatile mode if needed, rewrite both wiper bytes in one
     * transaction, wait by acknowledge polling and restore volatile mode if it
     * was active. The EEPROM is rated for only 50 000 writes.
     */
    fun store() {
        val raw = readAllRaw()
        val wasNv = (cfgBits and CFG_VOLATILE) == 0
        val zc = (cfgBits and CFG_ZC) != 0
        val opt = optionNo
        if (!wasNv) configure(opt, zc, true)
        writeCommands(byteArrayOf(raw[0].toByte(), (CMD_WIPER1 or raw[1]).toByte()))
        if (!wasNv) configure(opt, zc, false)
    }

    /**
     * Acknowledge-poll until the device answers (it NACKs while an EEPROM
     * write is in progress).
     *
     * @param timeoutS maximum time to wait in seconds
     * @throws java.io.IOException if the device is still busy after the timeout
     */
    fun waitReady(timeoutS: Double = 0.05) {
        doWaitReady(timeoutS)
    }

    /**
     * Read wiper 0, wiper 1 and the configuration byte in one transaction.
     *
     * @return `[position0, position1, configByte]`
     */
    fun readAll(): IntArray = readAllRaw()

    /**
     * Attenuation of a position in the current option.
     *
     * @param position wiper position
     * @return dB (`80.0` for mute and above)
     */
    fun positionToDb(position: Int): Double = positionToDbImpl(position)

    /**
     * Nearest position for an attenuation in the current option.
     *
     * @param db attenuation in dB; ≥ 70 dB maps to mute, negative to 0
     * @return wiper position
     */
    fun dbToPosition(db: Double): Int = dbToPos(db)

    /**
     * Last non-mute position of the current option.
     *
     * @return 62 (Option 1) or 32 (Option 2)
     */
    fun maxPosition(): Int = mutePosition() - 1

    /**
     * Volatile, zero-crossing on, Option 1 and both wipers to mute, sent as
     * one transaction (configuration byte, then both wiper bytes); waits for
     * the configuration EEPROM write.
     */
    fun reset() {
        connection.write(byteArrayOf((CMD_CONFIG or CFG_DEFAULT).toByte(), 63, (CMD_WIPER1 or 63).toByte()))
        cfgBits = CFG_DEFAULT
        optionNo = 1
        doWaitReady(0.05)
    }
}
