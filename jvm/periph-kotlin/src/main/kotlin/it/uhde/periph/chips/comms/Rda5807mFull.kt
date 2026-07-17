package it.uhde.periph.chips.comms

import it.uhde.periph.transport.Transport

/**
 * RDA5807M — full driver. Extends [Rda5807mMinimal] with band/spacing
 * configuration, RDS, status, and power management.
 *
 * @param transport    I²C transport bound to address 0x10
 * @param frequencyMhz initial frequency in MHz (default 100.0)
 * @param volume       initial volume, 0 (mute) to 15 (max) (default 8)
 */
class Rda5807mFull @JvmOverloads constructor(
    transport: Transport,
    frequencyMhz: Double = 100.0,
    volume: Int = 8
) : Rda5807mMinimal(transport, frequencyMhz, volume) {

    companion object {
        // Kotlin does not expose a superclass companion object's members through
        // the subclass name, so these are re-declared here for callers using
        // Rda5807mFull.BAND_*/SPACE_* (Java/Groovy inherit the static fields directly).
        const val BAND_US_EUROPE = Rda5807mMinimal.BAND_US_EUROPE
        const val BAND_JAPAN = Rda5807mMinimal.BAND_JAPAN
        const val BAND_WORLD = Rda5807mMinimal.BAND_WORLD
        const val BAND_EAST_EUROPE = Rda5807mMinimal.BAND_EAST_EUROPE

        const val SPACE_100K = Rda5807mMinimal.SPACE_100K
        const val SPACE_200K = Rda5807mMinimal.SPACE_200K
        const val SPACE_50K = Rda5807mMinimal.SPACE_50K
        const val SPACE_25K = Rda5807mMinimal.SPACE_25K
    }

    /**
     * Reconfigure tuner-level settings. `null` leaves a field unchanged.
     * Changing [band] or [space] re-tunes to the current frequency, since
     * CHAN's meaning depends on both.
     *
     * @param band           [BAND_US_EUROPE], [BAND_JAPAN], [BAND_WORLD], or [BAND_EAST_EUROPE]
     * @param space          [SPACE_100K], [SPACE_200K], [SPACE_50K], or [SPACE_25K]
     * @param deEmphasis     true for 50 µs, false for 75 µs
     * @param seekThreshold  seek SNR threshold, 0-15 (default 8, ~32 dB)
     * @param seekMode       true to stop seeking at the band limit, false to wrap
     * @param clkMode        reference clock select, 0-7
     * @param afcDisable     true to disable AFC
     * @param eastEurope50m  when band is BAND_EAST_EUROPE, true selects 65-76 MHz (default), false selects the 50 MHz-based sub-band
     */
    fun configure(
        band: Int? = null,
        space: Int? = null,
        deEmphasis: Boolean? = null,
        seekThreshold: Int? = null,
        seekMode: Boolean? = null,
        clkMode: Int? = null,
        afcDisable: Boolean? = null,
        eastEurope50m: Boolean? = null
    ) {
        var retune = false
        val currentFreq = frequency()

        if (band != null && band != this.band) {
            this.band = band
            retune = true
        }
        if (space != null && space != this.space) {
            this.space = space
            retune = true
        }
        if (eastEurope50m != null && eastEurope50m != this.eastEurope50m) {
            this.eastEurope50m = eastEurope50m
            retune = true
        }

        regs[1] = (regs[1] and 0x000F.inv()) or (this.band shl 2) or this.space

        if (deEmphasis != null) {
            regs[2] = if (deEmphasis) regs[2] or DE else regs[2] and DE.inv()
        }
        if (afcDisable != null) {
            regs[2] = if (afcDisable) regs[2] or AFCD else regs[2] and AFCD.inv()
        }
        if (seekThreshold != null) {
            regs[3] = (regs[3] and 0x0F00.inv()) or ((seekThreshold and 0x0F) shl 8)
        }
        if (seekMode != null) {
            regs[0] = if (seekMode) regs[0] or SKMODE else regs[0] and SKMODE.inv()
        }
        if (clkMode != null) {
            regs[0] = (regs[0] and 0x0070.inv()) or ((clkMode and 0x07) shl 4)
        }
        if (eastEurope50m != null) {
            regs[5] = if (this.eastEurope50m) regs[5] and BAND_65M_50M.inv() else regs[5] or BAND_65M_50M
        }

        if (retune) {
            setFrequency(currentFreq)
        } else {
            writeRegs()
        }
    }

    /** Enable or disable bass boost. */
    fun setBassBoost(enable: Boolean) {
        regs[0] = if (enable) regs[0] or BASS else regs[0] and BASS.inv()
        writeRegs()
    }

    /** Force mono or allow stereo. */
    fun setMono(enable: Boolean) {
        regs[0] = if (enable) regs[0] or MONO else regs[0] and MONO.inv()
        writeRegs()
    }

    /** Enable or disable soft mute (chip default: enabled). */
    fun setSoftmute(enable: Boolean) {
        regs[2] = if (enable) regs[2] or SOFTMUTE_EN else regs[2] and SOFTMUTE_EN.inv()
        writeRegs()
    }

    /** Enable or disable the RDS/RBDS decoder. */
    fun enableRds(enable: Boolean) {
        regs[0] = if (enable) regs[0] or RDS_EN else regs[0] and RDS_EN.inv()
        writeRegs()
    }

    /** @return true if a new RDS/RBDS group is available (RDSR flag) */
    fun rdsReady(): Boolean = (readStatus(2)[0] and RDSR) != 0

    /**
     * Read the four raw RDS/RBDS blocks, if a new group is ready. Does not
     * decode group content — the caller interprets the raw blocks.
     *
     * @return `(blockA, blockB, blockC, blockD)`, or `null` if no new group is ready
     */
    fun readRdsGroup(): IntArray? {
        val words = readStatus(12)
        if (words[0] and RDSR == 0) return null
        return intArrayOf(words[2], words[3], words[4], words[5])
    }

    /** @return true if the current station is being received in stereo */
    fun isStereo(): Boolean = (readStatus(2)[0] and ST) != 0

    /** @return true if the current channel is a real station (FM_TRUE flag) */
    fun isStation(): Boolean = (readStatus(4)[1] and FM_TRUE) != 0

    /** @return true if the tuner is ready (FM_READY flag) */
    fun isReady(): Boolean = (readStatus(4)[1] and FM_READY) != 0

    /** @return raw RSSI, 0 (weakest) to 127 (strongest), logarithmic. No absolute dBµV mapping is published. */
    fun signalStrength(): Int = (readStatus(4)[1] shr 9) and 0x7F

    /**
     * Power the chip down (true) or up (false).
     *
     * Powering back up clears the tuner's PLL lock, so waking from standby
     * blocks briefly for the chip to recover, then re-tunes to the last
     * known frequency (mirroring the datasheet's power-up sequencing, which
     * the chip otherwise never recovers from on its own).
     */
    fun standby(enable: Boolean) {
        regs[0] = if (enable) regs[0] and ENABLE.inv() else regs[0] or ENABLE
        writeRegs()
        if (!enable) {
            Thread.sleep(RESET_RECOVERY_MS)
            setFrequency(currentFreq)
            Thread.sleep(READY_SETTLE_MS)
        }
    }

    /**
     * Pulse the soft-reset bit, then re-apply the current configuration.
     *
     * A soft reset restores the chip's power-on register defaults and
     * clears the tuner's PLL lock, so this blocks briefly for the chip to
     * recover, then re-tunes to the last known frequency (the chip never
     * reacquires lock on its own otherwise).
     */
    fun softReset() {
        regs[0] = regs[0] or SOFT_RESET
        writeRegs()
        regs[0] = regs[0] and SOFT_RESET.inv()
        writeRegs()
        Thread.sleep(RESET_RECOVERY_MS)
        setFrequency(currentFreq)
        Thread.sleep(READY_SETTLE_MS)
    }
}
