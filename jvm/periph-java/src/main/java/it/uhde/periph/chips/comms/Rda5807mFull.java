package it.uhde.periph.chips.comms;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * RDA5807M — full driver. Extends {@link Rda5807mMinimal} with band/spacing
 * configuration, RDS, status, and power management.
 */
public class Rda5807mFull extends Rda5807mMinimal {

    /**
     * @param transport    I²C transport bound to address 0x10
     * @param frequencyMhz initial frequency in MHz
     * @param volume       initial volume, 0 (mute) to 15 (max)
     * @throws IOException on I²C error
     */
    public Rda5807mFull(Transport transport, double frequencyMhz, int volume) throws IOException {
        super(transport, frequencyMhz, volume);
    }

    /**
     * Construct with default parameters (100.0 MHz, volume 8).
     *
     * @param transport I²C transport bound to address 0x10
     * @throws IOException on I²C error
     */
    public Rda5807mFull(Transport transport) throws IOException {
        super(transport, 100.0, 8);
    }

    /**
     * Reconfigure tuner-level settings. Pass {@code null} to leave a field
     * unchanged. Changing band or space re-tunes to the current frequency,
     * since CHAN's meaning depends on both.
     *
     * @param band           {@link #BAND_US_EUROPE}, {@link #BAND_JAPAN}, {@link #BAND_WORLD}, or {@link #BAND_EAST_EUROPE}
     * @param space          {@link #SPACE_100K}, {@link #SPACE_200K}, {@link #SPACE_50K}, or {@link #SPACE_25K}
     * @param deEmphasis     true for 50 µs, false for 75 µs
     * @param seekThreshold  seek SNR threshold, 0-15 (default 8, ~32 dB)
     * @param seekMode       true to stop seeking at the band limit, false to wrap
     * @param clkMode        reference clock select, 0-7
     * @param afcDisable     true to disable AFC
     * @param eastEurope50m  when band is BAND_EAST_EUROPE, true selects 65-76 MHz (default), false selects the 50 MHz-based sub-band
     * @throws IOException on I²C error
     */
    public void configure(Integer band, Integer space, Boolean deEmphasis, Integer seekThreshold,
                           Boolean seekMode, Integer clkMode, Boolean afcDisable, Boolean eastEurope50m)
            throws IOException {
        boolean retune = false;
        double currentFreq = frequency();

        if (band != null && band != this.band) {
            this.band = band;
            retune = true;
        }
        if (space != null && space != this.space) {
            this.space = space;
            retune = true;
        }
        if (eastEurope50m != null && eastEurope50m != this.eastEurope50m) {
            this.eastEurope50m = eastEurope50m;
            retune = true;
        }

        regs[1] = (regs[1] & ~0x000F) | (this.band << 2) | this.space;

        if (deEmphasis != null) {
            if (deEmphasis) regs[2] |= DE;
            else regs[2] &= ~DE;
        }
        if (afcDisable != null) {
            if (afcDisable) regs[2] |= AFCD;
            else regs[2] &= ~AFCD;
        }
        if (seekThreshold != null) {
            regs[3] = (regs[3] & ~0x0F00) | ((seekThreshold & 0x0F) << 8);
        }
        if (seekMode != null) {
            if (seekMode) regs[0] |= SKMODE;
            else regs[0] &= ~SKMODE;
        }
        if (clkMode != null) {
            regs[0] = (regs[0] & ~0x0070) | ((clkMode & 0x07) << 4);
        }
        if (eastEurope50m != null) {
            if (this.eastEurope50m) regs[5] &= ~BAND_65M_50M;
            else regs[5] |= BAND_65M_50M;
        }

        if (retune) {
            setFrequency(currentFreq);
        } else {
            writeRegs();
        }
    }

    /**
     * Enable or disable bass boost.
     *
     * @param enable true to enable bass boost
     * @throws IOException on I²C error
     */
    public void setBassBoost(boolean enable) throws IOException {
        if (enable) regs[0] |= BASS;
        else regs[0] &= ~BASS;
        writeRegs();
    }

    /**
     * Force mono or allow stereo.
     *
     * @param enable true to force mono, false to allow stereo
     * @throws IOException on I²C error
     */
    public void setMono(boolean enable) throws IOException {
        if (enable) regs[0] |= MONO;
        else regs[0] &= ~MONO;
        writeRegs();
    }

    /**
     * Enable or disable soft mute.
     *
     * @param enable true to enable soft mute (chip default)
     * @throws IOException on I²C error
     */
    public void setSoftmute(boolean enable) throws IOException {
        if (enable) regs[2] |= SOFTMUTE_EN;
        else regs[2] &= ~SOFTMUTE_EN;
        writeRegs();
    }

    /**
     * Enable or disable the RDS/RBDS decoder.
     *
     * @param enable true to enable RDS/RBDS
     * @throws IOException on I²C error
     */
    public void enableRds(boolean enable) throws IOException {
        if (enable) regs[0] |= RDS_EN;
        else regs[0] &= ~RDS_EN;
        writeRegs();
    }

    /**
     * Check whether a new RDS/RBDS group is available.
     *
     * @return true if RDSR is set
     * @throws IOException on I²C error
     */
    public boolean rdsReady() throws IOException {
        return (readStatus(2)[0] & RDSR) != 0;
    }

    /**
     * Read the four raw RDS/RBDS blocks, if a new group is ready.
     *
     * <p>Does not decode group content (PI, PS, RadioText, ...) — the caller
     * interprets the raw blocks per the RDS/RBDS standard.
     *
     * @return {@code [blockA, blockB, blockC, blockD]}, or {@code null} if no new group is ready
     * @throws IOException on I²C error
     */
    public int[] readRdsGroup() throws IOException {
        int[] words = readStatus(12);
        if ((words[0] & RDSR) == 0) return null;
        return new int[]{words[2], words[3], words[4], words[5]};
    }

    /**
     * Check the stereo indicator.
     *
     * @return true if the current station is being received in stereo
     * @throws IOException on I²C error
     */
    public boolean isStereo() throws IOException {
        return (readStatus(2)[0] & ST) != 0;
    }

    /**
     * Check whether the current channel is a real station.
     *
     * @return true if FM_TRUE is set
     * @throws IOException on I²C error
     */
    public boolean isStation() throws IOException {
        return (readStatus(4)[1] & FM_TRUE) != 0;
    }

    /**
     * Check whether the tuner is ready.
     *
     * @return true if FM_READY is set
     * @throws IOException on I²C error
     */
    public boolean isReady() throws IOException {
        return (readStatus(4)[1] & FM_READY) != 0;
    }

    /**
     * Read the received signal strength indicator.
     *
     * @return raw RSSI, 0 (weakest) to 127 (strongest), logarithmic. No absolute dBµV mapping is published.
     * @throws IOException on I²C error
     */
    public int signalStrength() throws IOException {
        return (readStatus(4)[1] >> 9) & 0x7F;
    }

    /**
     * Power the chip down or up.
     *
     * <p>Powering back up clears the tuner's PLL lock, so waking from standby
     * blocks briefly for the chip to recover, then re-tunes to the last known
     * frequency (mirroring the datasheet's power-up sequencing, which the
     * chip otherwise never recovers from on its own).
     *
     * @param enable true to power down, false to power up
     * @throws IOException on I²C error
     */
    public void standby(boolean enable) throws IOException {
        if (enable) regs[0] &= ~ENABLE;
        else regs[0] |= ENABLE;
        writeRegs();
        if (!enable) {
            sleep(RESET_RECOVERY_MS);
            setFrequency(currentFreq);
            sleep(READY_SETTLE_MS);
        }
    }

    /**
     * Pulse the soft-reset bit, then re-apply the current configuration.
     *
     * <p>A soft reset restores the chip's power-on register defaults and
     * clears the tuner's PLL lock, so this blocks briefly for the chip to
     * recover, then re-tunes to the last known frequency (the chip never
     * reacquires lock on its own otherwise).
     *
     * @throws IOException on I²C error
     */
    public void softReset() throws IOException {
        regs[0] |= SOFT_RESET;
        writeRegs();
        regs[0] &= ~SOFT_RESET;
        writeRegs();
        sleep(RESET_RECOVERY_MS);
        setFrequency(currentFreq);
        sleep(READY_SETTLE_MS);
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
