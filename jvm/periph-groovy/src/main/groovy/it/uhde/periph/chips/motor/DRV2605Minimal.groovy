package it.uhde.periph.chips.motor

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/**
 * DRV2605 — haptic driver for ERM and LRA actuators with I²C interface (Texas
 * Instruments) — minimal interface.
 *
 * <p>Plays the built-in 123-effect TS2200 ROM library. The constructor runs the
 * initialization sequence: {@code DEV_RESET}, {@code DEVICE_ID} check, leave
 * standby, actuator selection (ERM: library A, open loop; LRA: LRA library,
 * closed loop with auto-resonance). The I²C address is fixed at 0x5A and the
 * {@code EN} pin must be high for register access.
 */
@CompileStatic
class DRV2605Minimal {

    /** Fixed 7-bit I²C address (no address pin). */
    public static final int DEFAULT_ADDRESS = 0x5A

    /** Expected {@code DEVICE_ID} of the DRV2605. */
    public static final int DEVICE_ID = 3

    /** Named ROM effect: Strong Click. */
    public static final int EFFECT_STRONG_CLICK = 1
    /** Named ROM effect: Sharp Click. */
    public static final int EFFECT_SHARP_CLICK = 4
    /** Named ROM effect: Soft Bump. */
    public static final int EFFECT_SOFT_BUMP = 7
    /** Named ROM effect: Double Click. */
    public static final int EFFECT_DOUBLE_CLICK = 10
    /** Named ROM effect: Triple Click. */
    public static final int EFFECT_TRIPLE_CLICK = 12
    /** Named ROM effect: Strong Buzz. */
    public static final int EFFECT_STRONG_BUZZ = 14
    /** Named ROM effect: 750 ms Alert. */
    public static final int EFFECT_ALERT_750MS = 15
    /** Named ROM effect: 1000 ms Alert. */
    public static final int EFFECT_ALERT_1000MS = 16
    /** Named ROM effect: Buzz 1. */
    public static final int EFFECT_BUZZ = 47
    /** Named ROM effect: Pulsing Strong 1. */
    public static final int EFFECT_PULSING_STRONG = 52
    /** Named ROM effect: Long Buzz for programmatic stopping. */
    public static final int EFFECT_LONG_BUZZ = 118

    /** {@code MODE} value: internal trigger ({@code GO} bit). */
    public static final int MODE_INTERNAL_TRIGGER = 0
    /** {@code MODE} value: external trigger, edge. */
    public static final int MODE_EXTERNAL_EDGE = 1
    /** {@code MODE} value: external trigger, level. */
    public static final int MODE_EXTERNAL_LEVEL = 2
    /** {@code MODE} value: PWM / analog input. */
    public static final int MODE_PWM_ANALOG = 3
    /** {@code MODE} value: audio-to-vibe. */
    public static final int MODE_AUDIO_TO_VIBE = 4
    /** {@code MODE} value: real-time playback. */
    public static final int MODE_RTP = 5
    /** {@code MODE} value: diagnostics. */
    public static final int MODE_DIAGNOSTICS = 6
    /** {@code MODE} value: auto-calibration. */
    public static final int MODE_AUTO_CALIBRATION = 7

    // Register map.
    protected static final int REG_STATUS        = 0x00
    protected static final int REG_MODE          = 0x01
    protected static final int REG_RTP_INPUT     = 0x02
    protected static final int REG_LIBRARY       = 0x03
    protected static final int REG_SEQ1          = 0x04
    protected static final int REG_GO            = 0x0C
    protected static final int REG_ODT           = 0x0D
    protected static final int REG_ATH_CTRL      = 0x11
    protected static final int REG_RATED_VOLTAGE = 0x16
    protected static final int REG_OD_CLAMP      = 0x17
    protected static final int REG_A_CAL_COMP    = 0x18
    protected static final int REG_FEEDBACK      = 0x1A
    protected static final int REG_CONTROL1      = 0x1B
    protected static final int REG_CONTROL2      = 0x1C
    protected static final int REG_CONTROL3      = 0x1D
    protected static final int REG_CONTROL4      = 0x1E
    protected static final int REG_VBAT          = 0x21
    protected static final int REG_LRA_PERIOD    = 0x22

    protected static final int MODE_DEV_RESET = 0x80
    protected static final int MODE_STANDBY   = 0x40

    protected final RegisterConnection connection
    protected int currentMode = MODE_INTERNAL_TRIGGER
    protected boolean inStandby = false

    /**
     * Construct the driver and run the initialization sequence.
     *
     * @param connection configured I²C connection bound to the device (0x5A)
     * @param lra        {@code false} for an ERM, {@code true} for an LRA
     * @throws IllegalStateException if {@code DEVICE_ID} is not 3
     * @throws IOException           on bus error or if the reset does not complete
     */
    public DRV2605Minimal(RegisterConnection connection, boolean lra) {
        this.connection = connection
        resetChip()
        if ((readReg(REG_STATUS) >> 5) != DEVICE_ID) {
            throw new IllegalStateException('DRV2605 not found (DEVICE_ID != 3)')
        }
        writeMode(MODE_INTERNAL_TRIGGER, false)
        if (lra) {
            writeReg(REG_FEEDBACK, 0xB6)
            writeReg(REG_LIBRARY, 0x06)
        } else {
            writeReg(REG_FEEDBACK, 0x36)
            writeReg(REG_LIBRARY, 0x01)
        }
    }

    /**
     * Construct the driver for an ERM.
     *
     * @param connection configured I²C connection bound to the device (0x5A)
     */
    public DRV2605Minimal(RegisterConnection connection) {
        this(connection, false)
    }

    protected int readReg(int reg) {
        return connection.read(reg, 1)[0] & 0xFF
    }

    protected void writeReg(int reg, int value) {
        connection.write(reg, new byte[]{(byte) value})
    }

    protected void writeRegs(int reg, byte[] data) {
        connection.write(reg, data)
    }

    /** Read-modify-write: replace the bits in {@code mask} with {@code value} (already shifted). */
    protected void updateReg(int reg, int mask, int value) {
        int old = readReg(reg)
        writeReg(reg, (old & ~mask) | (value & mask))
    }

    protected void writeMode(int newMode, boolean newStandby) {
        writeReg(REG_MODE, (newStandby ? MODE_STANDBY : 0) | newMode)
        currentMode = newMode
        inStandby = newStandby
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            throw new IOException('interrupted', e)
        }
    }

    protected void resetChip() {
        writeReg(REG_MODE, MODE_DEV_RESET)
        currentMode = MODE_INTERNAL_TRIGGER
        inStandby = true
        sleepMs(2)
        for (int i = 0; i < 100; i++) {
            if ((readReg(REG_MODE) & MODE_DEV_RESET) == 0) return
            sleepMs(1)
        }
        throw new IOException('DRV2605 reset did not complete')
    }

    protected void ensureInternal() {
        if (currentMode != MODE_INTERNAL_TRIGGER || inStandby) writeMode(MODE_INTERNAL_TRIGGER, false)
    }

    /** Round to the nearest integer and clamp to a register code 0–255. */
    protected static int clampCode(double value) {
        return Math.max(0, Math.min(255, (int) (value + 0.5)))
    }

    /**
     * Play one ROM effect and return immediately. Writes the effect to
     * sequencer slot 1 and 0 (end) to slot 2, switches to internal-trigger mode
     * if needed, then sets {@code GO}.
     *
     * @param effectId effect identifier 1–123 (see the {@code EFFECT_*} constants)
     * @throws IllegalArgumentException if {@code effectId} is outside 1–123
     */
    public void playEffect(int effectId) {
        if (effectId < 1 || effectId > 123) throw new IllegalArgumentException('effectId must be 1-123')
        ensureInternal()
        writeRegs(REG_SEQ1, new byte[]{(byte) effectId, (byte) 0})
        writeReg(REG_GO, 1)
    }

    /** Cancel playback by clearing the {@code GO} bit. */
    public void stop() {
        writeReg(REG_GO, 0)
    }

    /**
     * Whether a sequence is playing ({@code GO} bit set).
     *
     * @return {@code true} while playing
     */
    public boolean isPlaying() {
        return (readReg(REG_GO) & 0x01) != 0
    }
}
