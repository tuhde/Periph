package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * DS1881 — full interface: extends {@link DS1881Minimal} with stereo writes,
 * Option 2, zero-crossing and EEPROM control, and table helpers.
 *
 * <p>The driver caches the configuration (volatile, zero-crossing, option);
 * {@link #init()} and {@link #getConfig()} refresh it from the chip.
 */
@CompileStatic
class DS1881Full extends DS1881Minimal {

    /** Decoded configuration register. */
    static final class Config {
        /** 1 (63 positions + mute, 1 dB steps) or 2 (33 positions + mute). */
        final int option
        /** Zero-crossing detection enabled. */
        final boolean zeroCrossing
        /** Wiper writes are stored in EEPROM. */
        final boolean nonvolatile
        /** Raw configuration byte as read. */
        final int raw

        Config(int option, boolean zeroCrossing, boolean nonvolatile, int raw) {
            this.option = option
            this.zeroCrossing = zeroCrossing
            this.nonvolatile = nonvolatile
            this.raw = raw
        }
    }

    /**
     * Construct the driver; performs no bus traffic.
     *
     * @param connection configured I²C connection bound to the device (0x28–0x2F)
     */
    DS1881Full(Connection connection) {
        super(connection)
    }

    @Override
    protected int dbToPos(double db) {
        if (db >= 70.0d) return mutePosition()
        return nearestPosition(db)
    }

    /**
     * Write both wipers in one transaction (no STOP between them).
     *
     * @param position0 position for channel 0 (clamped)
     * @param position1 position for channel 1 (clamped)
     */
    void setBoth(int position0, int position1) {
        int mute = mutePosition()
        int[] p = [position0, position1] as int[]
        byte[] data = new byte[2]
        for (int ch = 0; ch < 2; ch++) {
            int v = Math.max(0, Math.min(p[ch], mute))
            data[ch] = (byte) ((ch << 6) | v)
        }
        writeCommands(data)
    }

    /**
     * Set both channels' attenuation to the same value (stereo volume).
     *
     * @param db attenuation in dB
     */
    void setAttenuationBoth(double db) {
        setAttenuationBoth(db, db)
    }

    /**
     * Set both channels' attenuation.
     *
     * @param db0 attenuation of channel 0 in dB
     * @param db1 attenuation of channel 1 in dB
     */
    void setAttenuationBoth(double db0, double db1) {
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
    void configure(int option, boolean zeroCrossing, boolean nonvolatile) {
        if (option != 1 && option != 2) throw new IllegalArgumentException('option must be 1 or 2')
        int cfg = 0
        if (!nonvolatile) cfg |= CFG_VOLATILE
        if (zeroCrossing) cfg |= CFG_ZC
        if (option == 2) cfg |= CFG_OPTION2
        if (cfg != cfgBits) {
            connection.write([(byte) (CMD_CONFIG | cfg)] as byte[])
            doWaitReady(0.05d)
        }
        cfgBits = cfg
        optionNo = option
    }

    /**
     * Read the configuration register (fresh read; refreshes the cache).
     *
     * @return the decoded configuration
     */
    Config getConfig() {
        int raw = readAllRaw()[2]
        cfgBits = raw & CFG_MASK
        optionNo = (raw & CFG_OPTION2) != 0 ? 2 : 1
        return new Config(optionNo, (raw & CFG_ZC) != 0, (raw & CFG_VOLATILE) == 0, raw)
    }

    /**
     * Switch between Option 1 and 2 (positions are not converted).
     *
     * @param option 1 or 2
     */
    void setOption(int option) {
        configure(option, (cfgBits & CFG_ZC) != 0, (cfgBits & CFG_VOLATILE) == 0)
    }

    /**
     * Enable or disable zero-crossing detection.
     *
     * @param enabled true to enable
     */
    void setZeroCrossing(boolean enabled) {
        configure(optionNo, enabled, (cfgBits & CFG_VOLATILE) == 0)
    }

    /**
     * Select where wiper writes go (V/NV bit).
     *
     * @param enabled true: subsequent wiper writes are stored in EEPROM and restored at power-up (50 000 writes)
     */
    void setNonvolatile(boolean enabled) {
        configure(optionNo, (cfgBits & CFG_ZC) != 0, enabled)
    }

    /**
     * Persist the current wiper positions to EEPROM: read both positions,
     * switch to nonvolatile mode if needed, rewrite both wiper bytes in one
     * transaction, wait by acknowledge polling and restore volatile mode if it
     * was active. The EEPROM is rated for only 50 000 writes.
     */
    void store() {
        int[] raw = readAllRaw()
        boolean wasNv = (cfgBits & CFG_VOLATILE) == 0
        boolean zc = (cfgBits & CFG_ZC) != 0
        int opt = optionNo
        if (!wasNv) configure(opt, zc, true)
        writeCommands([(byte) raw[0], (byte) (CMD_WIPER1 | raw[1])] as byte[])
        if (!wasNv) configure(opt, zc, false)
    }

    /**
     * Acknowledge-poll until the device answers (it NACKs while an EEPROM
     * write is in progress).
     *
     * @param timeoutS maximum time to wait in seconds (default part: 0.05)
     */
    void waitReady(double timeoutS) {
        doWaitReady(timeoutS)
    }

    /**
     * Read wiper 0, wiper 1 and the configuration byte in one transaction.
     *
     * @return {@code [position0, position1, configByte]}
     */
    int[] readAll() {
        return readAllRaw()
    }

    /**
     * Attenuation of a position in the current option.
     *
     * @param position wiper position
     * @return dB ({@code 80.0} for mute and above)
     */
    double positionToDb(int position) {
        return positionToDbImpl(position)
    }

    /**
     * Nearest position for an attenuation in the current option.
     *
     * @param db attenuation in dB; ≥ 70 dB maps to mute, negative to 0
     * @return wiper position
     */
    int dbToPosition(double db) {
        return dbToPos(db)
    }

    /**
     * Last non-mute position of the current option.
     *
     * @return 62 (Option 1) or 32 (Option 2)
     */
    int maxPosition() {
        return mutePosition() - 1
    }

    /**
     * Volatile, zero-crossing on, Option 1 and both wipers to mute, sent as
     * one transaction (configuration byte, then both wiper bytes); waits for
     * the configuration EEPROM write.
     */
    void reset() {
        connection.write([(byte) (CMD_CONFIG | CFG_DEFAULT), (byte) 63, (byte) (CMD_WIPER1 | 63)] as byte[])
        cfgBits = CFG_DEFAULT
        optionNo = 1
        doWaitReady(0.05d)
    }
}
