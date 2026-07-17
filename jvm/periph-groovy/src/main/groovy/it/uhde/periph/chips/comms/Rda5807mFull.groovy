package it.uhde.periph.chips.comms

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport

/**
 * RDA5807M — full driver. Extends {@link Rda5807mMinimal} with band/spacing
 * configuration, RDS, status, and power management.
 */
@CompileStatic
class Rda5807mFull extends Rda5807mMinimal {

    /**
     * @param transport    I²C transport bound to address 0x10
     * @param frequencyMhz initial frequency in MHz (default 100.0)
     * @param volume       initial volume, 0 (mute) to 15 (max) (default 8)
     */
    Rda5807mFull(Transport transport, double frequencyMhz = 100.0d, int volume = 8) {
        super(transport, frequencyMhz, volume)
    }

    /**
     * Reconfigure tuner-level settings. {@code null} leaves a field unchanged.
     * Changing band or space re-tunes to the current frequency, since CHAN's
     * meaning depends on both.
     *
     * @param band           BAND_US_EUROPE, BAND_JAPAN, BAND_WORLD, or BAND_EAST_EUROPE
     * @param space          SPACE_100K, SPACE_200K, SPACE_50K, or SPACE_25K
     * @param deEmphasis     true for 50 µs, false for 75 µs
     * @param seekThreshold  seek SNR threshold, 0-15 (default 8, ~32 dB)
     * @param seekMode       true to stop seeking at the band limit, false to wrap
     * @param clkMode        reference clock select, 0-7
     * @param afcDisable     true to disable AFC
     * @param eastEurope50m  when band is BAND_EAST_EUROPE, true selects 65-76 MHz (default), false selects the 50 MHz-based sub-band
     */
    void configure(Integer band = null, Integer space = null, Boolean deEmphasis = null,
                   Integer seekThreshold = null, Boolean seekMode = null, Integer clkMode = null,
                   Boolean afcDisable = null, Boolean eastEurope50m = null) {
        boolean retune = false
        double currentFreq = frequency()

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

        regs[1] = (regs[1] & ~0x000F) | (this.band << 2) | this.space

        if (deEmphasis != null) {
            if (deEmphasis) regs[2] |= DE
            else regs[2] &= ~DE
        }
        if (afcDisable != null) {
            if (afcDisable) regs[2] |= AFCD
            else regs[2] &= ~AFCD
        }
        if (seekThreshold != null) {
            regs[3] = (regs[3] & ~0x0F00) | ((seekThreshold & 0x0F) << 8)
        }
        if (seekMode != null) {
            if (seekMode) regs[0] |= SKMODE
            else regs[0] &= ~SKMODE
        }
        if (clkMode != null) {
            regs[0] = (regs[0] & ~0x0070) | ((clkMode & 0x07) << 4)
        }
        if (eastEurope50m != null) {
            if (this.eastEurope50m) regs[5] &= ~BAND_65M_50M
            else regs[5] |= BAND_65M_50M
        }

        if (retune) {
            setFrequency(currentFreq)
        } else {
            writeRegs()
        }
    }

    /** Enable or disable bass boost. */
    void setBassBoost(boolean enable) {
        if (enable) regs[0] |= BASS
        else regs[0] &= ~BASS
        writeRegs()
    }

    /** Force mono or allow stereo. */
    void setMono(boolean enable) {
        if (enable) regs[0] |= MONO
        else regs[0] &= ~MONO
        writeRegs()
    }

    /** Enable or disable soft mute (chip default: enabled). */
    void setSoftmute(boolean enable) {
        if (enable) regs[2] |= SOFTMUTE_EN
        else regs[2] &= ~SOFTMUTE_EN
        writeRegs()
    }

    /** Enable or disable the RDS/RBDS decoder. */
    void enableRds(boolean enable) {
        if (enable) regs[0] |= RDS_EN
        else regs[0] &= ~RDS_EN
        writeRegs()
    }

    /** @return true if a new RDS/RBDS group is available (RDSR flag) */
    boolean rdsReady() {
        (readStatus(2)[0] & RDSR) != 0
    }

    /**
     * Read the four raw RDS/RBDS blocks, if a new group is ready. Does not
     * decode group content — the caller interprets the raw blocks.
     *
     * @return {@code [blockA, blockB, blockC, blockD]}, or {@code null} if no new group is ready
     */
    int[] readRdsGroup() {
        int[] words = readStatus(12)
        if ((words[0] & RDSR) == 0) return null
        [words[2], words[3], words[4], words[5]] as int[]
    }

    /** @return true if the current station is being received in stereo */
    boolean isStereo() {
        (readStatus(2)[0] & ST) != 0
    }

    /** @return true if the current channel is a real station (FM_TRUE flag) */
    boolean isStation() {
        (readStatus(4)[1] & FM_TRUE) != 0
    }

    /** @return true if the tuner is ready (FM_READY flag) */
    boolean isReady() {
        (readStatus(4)[1] & FM_READY) != 0
    }

    /** @return raw RSSI, 0 (weakest) to 127 (strongest), logarithmic. No absolute dBµV mapping is published. */
    int signalStrength() {
        (readStatus(4)[1] >> 9) & 0x7F
    }

    /**
     * Power the chip down (true) or up (false).
     *
     * <p>Powering back up clears the tuner's PLL lock, so waking from standby
     * blocks briefly for the chip to recover, then re-tunes to the last known
     * frequency (mirroring the datasheet's power-up sequencing, which the
     * chip otherwise never recovers from on its own).
     */
    void standby(boolean enable) {
        if (enable) regs[0] &= ~ENABLE
        else regs[0] |= ENABLE
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
     * <p>A soft reset restores the chip's power-on register defaults and
     * clears the tuner's PLL lock, so this blocks briefly for the chip to
     * recover, then re-tunes to the last known frequency (the chip never
     * reacquires lock on its own otherwise).
     */
    void softReset() {
        regs[0] |= SOFT_RESET
        writeRegs()
        regs[0] &= ~SOFT_RESET
        writeRegs()
        Thread.sleep(RESET_RECOVERY_MS)
        setFrequency(currentFreq)
        Thread.sleep(READY_SETTLE_MS)
    }
}
