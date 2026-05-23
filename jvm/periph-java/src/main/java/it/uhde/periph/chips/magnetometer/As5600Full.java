package it.uhde.periph.chips.magnetometer;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * AS5600 — full driver. Extends {@link As5600Minimal} with raw angle, AGC,
 * magnitude, configuration, position/angle range management, and OTP burn
 * commands.
 *
 * <h2>Power mode constants</h2>
 * {@link #PM_NOM}, {@link #PM_LPM1}, {@link #PM_LPM2}, {@link #PM_LPM3}
 *
 * <h2>Hysteresis constants</h2>
 * {@link #HYST_OFF}, {@link #HYST_1LSB}, {@link #HYST_2LSB}, {@link #HYST_3LSB}
 *
 * <h2>Output stage constants</h2>
 * {@link #OUTS_ANALOG}, {@link #OUTS_ANALOG_REDUCED}, {@link #OUTS_PWM}
 *
 * <h2>PWM frequency constants</h2>
 * {@link #PWMF_115HZ}, {@link #PWMF_230HZ}, {@link #PWMF_460HZ}, {@link #PWMF_920HZ}
 *
 * <h2>Slow filter constants</h2>
 * {@link #SF_16X}, {@link #SF_8X}, {@link #SF_4X}, {@link #SF_2X}
 *
 * <h2>Burn commands</h2>
 * {@link #BURN_ANGLE}, {@link #BURN_SETTING}
 */
public class As5600Full extends As5600Minimal {

    // --- Power mode constants ---
    /** Power mode: normal (6.5 mA). */
    public static final int PM_NOM  = 0;
    /** Power mode: low power 1 (3.4 mA, 5 ms poll). */
    public static final int PM_LPM1 = 1;
    /** Power mode: low power 2 (1.8 mA, 20 ms poll). */
    public static final int PM_LPM2 = 2;
    /** Power mode: low power 3 (1.5 mA, 100 ms poll). */
    public static final int PM_LPM3 = 3;

    // --- Hysteresis constants ---
    /** Hysteresis: off. */
    public static final int HYST_OFF   = 0;
    /** Hysteresis: 1 LSB. */
    public static final int HYST_1LSB  = 1;
    /** Hysteresis: 2 LSBs. */
    public static final int HYST_2LSB  = 2;
    /** Hysteresis: 3 LSBs. */
    public static final int HYST_3LSB  = 3;

    // --- Output stage constants ---
    /** Output: analog 0–VDD. */
    public static final int OUTS_ANALOG          = 0;
    /** Output: analog 10–90% VDD. */
    public static final int OUTS_ANALOG_REDUCED  = 1;
    /** Output: digital PWM. */
    public static final int OUTS_PWM             = 2;

    // --- PWM frequency constants ---
    /** PWM frequency: 115 Hz. */
    public static final int PWMF_115HZ  = 0;
    /** PWM frequency: 230 Hz. */
    public static final int PWMF_230HZ  = 1;
    /** PWM frequency: 460 Hz. */
    public static final int PWMF_460HZ  = 2;
    /** PWM frequency: 920 Hz. */
    public static final int PWMF_920HZ  = 3;

    // --- Slow filter constants ---
    /** Slow filter: 16× (2.2 ms settle). */
    public static final int SF_16X = 0;
    /** Slow filter: 8× (1.1 ms settle). */
    public static final int SF_8X  = 1;
    /** Slow filter: 4× (0.55 ms settle). */
    public static final int SF_4X  = 2;
    /** Slow filter: 2× (0.286 ms settle). */
    public static final int SF_2X  = 3;

    // --- Burn commands ---
    /** Burn command: permanently store ZPOS+MPOS to OTP. */
    public static final int BURN_ANGLE  = 0x80;
    /** Burn command: permanently store MANG+CONF to OTP. */
    public static final int BURN_SETTING = 0x40;

    /**
     * Construct the full driver and verify magnet presence.
     *
     * @param transport I²C transport bound to address 0x36
     * @throws IOException if MD=0 (magnet not detected) or on I²C error
     */
    public As5600Full(Transport transport) throws IOException {
        super(transport);
    }

    /**
     * Read the unscaled raw angle count (0–4095).
     *
     * <p>Reads the RAW_ANGLE register (0x0C-0x0D), which is unaffected by
     * ZPOS/MPOS programming. Represents the physical angle over 0–360°.
     *
     * @return raw angle count, 0–4095
     * @throws IOException on I²C error
     */
    public int rawAngle() throws IOException {
        return readReg12(REG_RAW_ANGLE_H);
    }

    /**
     * Read the unscaled raw angle in degrees.
     *
     * @return raw angle in degrees, 0.0–360.0
     * @throws IOException on I²C error
     */
    public double rawAngleDegrees() throws IOException {
        return rawAngle() * 360.0 / 4096.0;
    }

    /**
     * Read the automatic gain control value.
     *
     * <p>In 5 V mode the range is 0–255; in 3.3 V mode it is 0–127.
     * Mid-range indicates optimal airgap.
     *
     * @return AGC value (0–255 for 5 V, 0–127 for 3.3 V)
     * @throws IOException on I²C error
     */
    public int agc() throws IOException {
        return readReg8(REG_AGC);
    }

    /**
     * Read the 12-bit CORDIC magnitude value.
     *
     * @return magnitude, 0–4095
     * @throws IOException on I²C error
     */
    public int magnitude() throws IOException {
        return readReg12(REG_MAGNITUDE_H);
    }

    /**
     * Read the raw STATUS register byte.
     *
     * @return raw STATUS byte (MH bit 5, ML bit 4, MD bit 3)
     * @throws IOException on I²C error
     */
    public int statusByte() throws IOException {
        return readReg8(REG_STATUS);
    }

    /**
     * Write the CONF register (14 bits split across CONF_H and CONF_L).
     *
     * <p>Reads the current CONF_H/CONF_L values first to preserve the
     * reserved bits in CONF_H[7:6], then masks in the new field values.
     *
     * @param pm    power mode (0–3, use {@code PM_*} constants)
     * @param hyst  hysteresis (0–3, use {@code HYST_*} constants)
     * @param outs  output stage (0–2, use {@code OUTS_*} constants)
     * @param pwmf  PWM frequency (0–3, use {@code PWMF_*} constants)
     * @param sf    slow filter (0–3, use {@code SF_*} constants)
     * @param fth   fast filter threshold (0–7)
     * @param wd    watchdog enable (true=on, false=off)
     * @throws IOException on I²C error
     */
    public void configure(int pm, int hyst, int outs, int pwmf, int sf, int fth, boolean wd) throws IOException {
        int confH = readReg8(REG_CONF_H);
        int confL = readReg8(REG_CONF_L);

        // Preserve reserved bits in CONF_H[7:6], mask in new values
        confH = (confH & 0xC0) | ((fth & 0x07) << 2) | (sf & 0x03);
        if (wd) confH |= 0x20; else confH &= ~0x20;

        confL = ((pwmf & 0x03) << 6) | ((outs & 0x03) << 4) | ((hyst & 0x03) << 2) | (pm & 0x03);

        transport.write(new byte[]{
                (byte) REG_CONF_H,
                (byte) (confH & 0xFF),
                (byte) (confL & 0xFF)
        });
    }

    /**
     * Set the zero (start) position.
     *
     * <p>Writes to volatile RAM only; lost on power cycle unless followed by
     * {@link #burnAngle()}. Takes effect ≥1 ms after the write.
     *
     * @param pos zero position, 0–4095
     * @throws IOException on I²C error
     */
    public void setZeroPosition(int pos) throws IOException {
        writeReg12(REG_ZPOS_H, REG_ZPOS_L, pos);
    }

    /**
     * Set the maximum (stop) position.
     *
     * <p>Writes to volatile RAM only; lost on power cycle unless followed by
     * {@link #burnAngle()}. Takes effect ≥1 ms after the write.
     *
     * @param pos maximum position, 0–4095
     * @throws IOException on I²C error
     */
    public void setMaxPosition(int pos) throws IOException {
        writeReg12(REG_MPOS_H, REG_MPOS_L, pos);
    }

    /**
     * Set the maximum angle span.
     *
     * <p>Writes to volatile RAM only; lost on power cycle unless followed by
     * {@link #burnSetting()}. The span must correspond to ≥18° (≥204 counts).
     *
     * @param span angle span, 0–4095
     * @throws IOException on I²C error
     */
    public void setMaxAngle(int span) throws IOException {
        writeReg12(REG_MANG_H, REG_MANG_L, span);
    }

    /**
     * Read the zero (start) position.
     *
     * @return zero position, 0–4095
     * @throws IOException on I²C error
     */
    public int zeroPosition() throws IOException {
        return readReg12(REG_ZPOS_H);
    }

    /**
     * Read the maximum (stop) position.
     *
     * @return maximum position, 0–4095
     * @throws IOException on I²C error
     */
    public int maxPosition() throws IOException {
        return readReg12(REG_MPOS_H);
    }

    /**
     * Read the maximum angle span.
     *
     * @return angle span, 0–4095
     * @throws IOException on I²C error
     */
    public int maxAngle() throws IOException {
        return readReg12(REG_MANG_H);
    }

    /**
     * Read the OTP burn count for ZPOS/MPOS.
     *
     * @return number of permanent ZPOS/MPOS writes already performed (0–3)
     * @throws IOException on I²C error
     */
    public int burnCount() throws IOException {
        return readReg8(REG_ZMCO) & 0x03;
    }

    /**
     * Permanently burn ZPOS and MPOS to OTP.
     *
     * <p>Requires MD=1 (magnet present) and ZMCO&lt;3. After burning, the
     * standard OTP verification sequence (0x01, 0x11, 0x10 to 0xFF) is
     * executed to reload and verify.
     *
     * @throws IOException if MD=0, ZMCO≥3, or on I²C error
     */
    public void burnAngle() throws IOException {
        int status = readReg8(REG_STATUS);
        if ((status & STATUS_MD) == 0) {
            throw new IOException("AS5600: burn_angle requires magnet detected (MD=1)");
        }
        if (burnCount() >= 3) {
            throw new IOException("AS5600: burn_angle failed, ZMCO=3 (no remaining writes)");
        }
        writeReg8(REG_BURN, BURN_ANGLE);
        try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        // OTP verification sequence
        writeReg8(REG_BURN, 0x01);
        writeReg8(REG_BURN, 0x11);
        writeReg8(REG_BURN, 0x10);
    }

    /**
     * Permanently burn MANG and CONF to OTP.
     *
     * <p>Requires ZMCO=0 (ZPOS/MPOS never burned). Can only be executed once.
     * After burning, the standard OTP verification sequence is executed.
     *
     * @throws IOException if ZMCO≠0 or on I²C error
     */
    public void burnSetting() throws IOException {
        if (burnCount() != 0) {
            throw new IOException("AS5600: burn_setting requires ZMCO=0");
        }
        writeReg8(REG_BURN, BURN_SETTING);
        try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        // OTP verification sequence
        writeReg8(REG_BURN, 0x01);
        writeReg8(REG_BURN, 0x11);
        writeReg8(REG_BURN, 0x10);
    }
}
