package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.connection.OutputPin
import it.uhde.periph.connection.RegisterConnection

/**
 * CS43L22 — low-power stereo DAC with headphone and Class-D speaker amplifiers (Cirrus Logic) —
 * minimal interface.
 *
 * <p>Configured entirely through the I²C control port (7-bit address 0x4A with AD0 = DGND, 0x4B
 * with AD0 = VL; 100 kHz maximum). Audio data travels over a separate I²S / PCM port that this
 * driver does not touch: the application supplies MCLK and the stream, calls {@link #start()}
 * once the clocks run and {@link #stop()} before they go away. Registers have a one-byte address
 * and are written one at a time.
 *
 * <p>Defaults: I²S slave, 24-bit, clock speed auto-detected from the MCLK / LRCK ratio, headphone
 * amplifiers always on, speaker amplifiers off, headphone gain 0.6047, master volume 0 dB,
 * digital soft ramp on. Construction performs no bus traffic; {@link #init()} resets the chip
 * (when a RESET pin is given), checks its ID, loads these defaults and runs the required
 * initialization block. The chip stays powered down until {@link #start()}.
 */
@CompileStatic
class CS43L22Minimal {

    /** 7-bit I²C address with AD0 = DGND (0x4B with AD0 = VL). */
    public static final int DEFAULT_ADDRESS = 0x4A

    protected static final int REG_ID = 0x01
    protected static final int REG_POWER_CTL_1 = 0x02
    protected static final int REG_POWER_CTL_2 = 0x04
    protected static final int REG_CLOCKING_CTL = 0x05
    protected static final int REG_INTERFACE_CTL_1 = 0x06
    protected static final int REG_INTERFACE_CTL_2 = 0x07
    protected static final int REG_PASSTHRU_A_SEL = 0x08
    protected static final int REG_PASSTHRU_B_SEL = 0x09
    protected static final int REG_ANALOG_ZC_SR = 0x0A
    protected static final int REG_PLAYBACK_CTL_1 = 0x0D
    protected static final int REG_MISC_CTL = 0x0E
    protected static final int REG_PLAYBACK_CTL_2 = 0x0F
    protected static final int REG_PASSTHRU_A_VOL = 0x14
    protected static final int REG_PASSTHRU_B_VOL = 0x15
    protected static final int REG_PCM_A_VOL = 0x1A
    protected static final int REG_PCM_B_VOL = 0x1B
    protected static final int REG_BEEP_FREQ_ON = 0x1C
    protected static final int REG_BEEP_VOL_OFF = 0x1D
    protected static final int REG_BEEP_TONE_CFG = 0x1E
    protected static final int REG_TONE_CTL = 0x1F
    protected static final int REG_MASTER_A_VOL = 0x20
    protected static final int REG_MASTER_B_VOL = 0x21
    protected static final int REG_HP_A_VOL = 0x22
    protected static final int REG_HP_B_VOL = 0x23
    protected static final int REG_SPK_A_VOL = 0x24
    protected static final int REG_SPK_B_VOL = 0x25
    protected static final int REG_PCM_MIXER_SWAP = 0x26
    protected static final int REG_LIMIT_CTL_1 = 0x27
    protected static final int REG_LIMIT_CTL_2 = 0x28
    protected static final int REG_LIMIT_ATTACK = 0x29
    protected static final int REG_STATUS = 0x2E
    protected static final int REG_BATTERY_COMP = 0x2F
    protected static final int REG_VP_LEVEL = 0x30
    protected static final int REG_SPEAKER_STATUS = 0x31
    protected static final int REG_CHARGE_PUMP_FREQ = 0x34
    protected static final int REG_INIT_00 = 0x00
    protected static final int REG_INIT_32 = 0x32
    protected static final int REG_INIT_47 = 0x47

    protected final RegisterConnection connection
    protected final OutputPin resPin

    /**
     * Construct the driver; performs no bus traffic.
     *
     * @param connection I²C register connection bound to 0x4A / 0x4B (one-byte register address)
     * @param resPin optional output pin wired to the active-low RESET pin, or {@code null}
     */
    CS43L22Minimal(RegisterConnection connection, OutputPin resPin = null) {
        this.connection = connection
        this.resPin = resPin
    }

    protected void w(int reg, int value) {
        connection.write(reg, [(byte) value] as byte[])
    }

    protected int r(int reg) {
        return connection.read(reg, 1)[0] & 0xFF
    }

    protected void update(int reg, int mask, int value) {
        w(reg, (r(reg) & ~mask & 0xFF) | (value & mask))
    }

    protected void setBit(int reg, int bit, boolean on) {
        update(reg, 1 << bit, on ? 1 << bit : 0)
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms)
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
        }
    }

    protected static int roundHalfUp(double x) {
        return (int) Math.floor(x + 0.5d)
    }

    protected static int halfDbCode(double db, double lo, double hi) {
        return roundHalfUp(Math.max(lo, Math.min(hi, db)) * 2d) & 0xFF
    }

    protected static int limitedVolumeCode(double db) {
        if (db < -96.0d) {
            return 0x01
        }
        return roundHalfUp(Math.max(-96.0d, Math.min(0.0d, db)) * 2d) & 0xFF
    }

    /**
     * Reset (when a RESET pin is given), verify the chip ID, load the defaults and run the
     * required initialization. The chip stays powered down.
     *
     * @throws IOException on I²C error, or if the ID register does not read 0x1C in bits 7:3
     */
    void init() {
        if (resPin != null) {
            resPin.set(false)
            sleepMs(2)
            resPin.set(true)
            sleepMs(2)
        }
        if ((r(REG_ID) & 0xF8) != 0xE0) {
            throw new IOException('CS43L22 not found: chip ID mismatch')
        }
        w(REG_POWER_CTL_1, 0x01)
        w(REG_POWER_CTL_2, 0xAF)
        w(REG_CLOCKING_CTL, 0xA0)
        w(REG_INTERFACE_CTL_1, 0x04)
        w(REG_PLAYBACK_CTL_1, 0x60)
        w(REG_MASTER_A_VOL, 0x00)
        w(REG_MASTER_B_VOL, 0x00)
        // Required initialization settings (datasheet §4.11).
        w(REG_INIT_00, 0x99)
        w(REG_INIT_47, 0x80)
        int v = r(REG_INIT_32)
        w(REG_INIT_32, v | 0x80)
        w(REG_INIT_32, v & 0x7F)
        w(REG_INIT_00, 0x00)
    }

    /** Power the chip up ({@code POWER_CTL_1} = 0x9E). Call after MCLK, SCLK and LRCK are running. */
    void start() {
        w(REG_POWER_CTL_1, 0x9E)
    }

    /**
     * Power-down sequence: mute, disable soft ramp / zero-cross, {@code POWER_CTL_1} = 0x9F, wait
     * 100 µs. MCLK may be removed after it returns.
     */
    void stop() {
        update(REG_PLAYBACK_CTL_2, 0xF0, 0xF0)
        update(REG_MISC_CTL, 0x03, 0x00)
        update(REG_ANALOG_ZC_SR, 0x0F, 0x00)
        w(REG_POWER_CTL_1, 0x9F)
        sleepMs(1)
    }

    /**
     * Set the master volume of both channels.
     *
     * @param db −102 … +12 dB, rounded to the nearest 0.5 dB step (clamped)
     */
    void setVolume(double db) {
        int code = halfDbCode(db, -102.0d, 12.0d)
        w(REG_MASTER_A_VOL, code)
        w(REG_MASTER_B_VOL, code)
    }

    /** Master volume of channel A in dB. */
    double getVolume() {
        return ((byte) r(REG_MASTER_A_VOL)) / 2.0d
    }

    /**
     * Mute or unmute both headphone channels.
     *
     * @param on {@code true} to mute
     */
    void mute(boolean on = true) {
        update(REG_PLAYBACK_CTL_2, 0xC0, on ? 0xC0 : 0)
    }
}
