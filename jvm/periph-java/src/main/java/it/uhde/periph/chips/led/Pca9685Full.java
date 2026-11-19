package it.uhde.periph.chips.led;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * PCA9685 — full driver: phase-shifted PWM, frequency, servo pulses, output modes, addresses.
 *
 * <p>Extends {@link Pca9685Minimal}.
 */
public class Pca9685Full extends Pca9685Minimal {

    /** Output state while OE = 1: LEDn = 0. */
    public static final int OFF_LOW = 0;
    /** Output state while OE = 1: LEDn = 1 (totem-pole) or high-Z (open-drain). */
    public static final int OFF_HIGH_OR_HIGHZ = 1;
    /** Output state while OE = 1: high-Z. */
    public static final int OFF_HIGHZ = 2;

    /**
     * Construct the driver and run the initialization sequence.
     *
     * @param connection I²C register connection bound to the chip address
     * @throws IOException on I²C error
     */
    public Pca9685Full(RegisterConnection connection) throws IOException {
        super(connection);
    }

    private int mode1() throws IOException {
        return readReg(REG_MODE1, 1)[0] & 0xFF;
    }

    private static int[] pwmBytes(int on, int off) {
        if (on < 0 || on > 4095 || off < 0 || off > 4095) throw new IllegalArgumentException("on/off must be 0-4095");
        if (on == off) throw new IllegalArgumentException("on must differ from off");
        return new int[]{on & 0xFF, on >> 8, off & 0xFF, off >> 8};
    }

    /**
     * Write the ON and OFF counts of one channel in one transaction (phase-shifted PWM).
     *
     * @param channel 0–15
     * @param on      0–4095
     * @param off     0–4095, must differ from {@code on}
     * @throws IOException on I²C error
     */
    public void setPwm(int channel, int on, int off) throws IOException {
        checkChannel(channel);
        writeReg(REG_LED0 + 4 * channel, pwmBytes(on, off));
    }

    /**
     * Read the ON and OFF counts of one channel (without the full-ON/OFF flags).
     *
     * @param channel 0–15
     * @return {@code {on, off}}, each 0–4095
     * @throws IOException on I²C error
     */
    public int[] getPwm(int channel) throws IOException {
        checkChannel(channel);
        byte[] d = readReg(REG_LED0 + 4 * channel, 4);
        return new int[]{((d[1] & 0x0F) << 8) | (d[0] & 0xFF), ((d[3] & 0x0F) << 8) | (d[2] & 0xFF)};
    }

    /**
     * Force a channel permanently high (full-ON bit set, full-OFF bit cleared).
     *
     * @param channel 0–15
     * @throws IOException on I²C error
     */
    public void setFullOn(int channel) throws IOException {
        checkChannel(channel);
        writeReg(REG_LED0 + 4 * channel, 0, 0x10, 0, 0);
    }

    /**
     * Force a channel permanently low (full-OFF bit set; it wins over full ON).
     *
     * @param channel 0–15
     * @throws IOException on I²C error
     */
    public void setFullOff(int channel) throws IOException {
        checkChannel(channel);
        writeReg(REG_LED0 + 4 * channel, 0, 0, 0, 0x10);
    }

    /**
     * Set the same duty cycle on all 16 channels (ALL_LED registers).
     *
     * @param duty 0.0–1.0
     * @throws IOException on I²C error
     */
    public void setAllDuty(double duty) throws IOException {
        writeReg(REG_ALL_LED, dutyBytes(duty));
    }

    /**
     * Set the same ON and OFF counts on all 16 channels.
     *
     * @param on  0–4095
     * @param off 0–4095, must differ from {@code on}
     * @throws IOException on I²C error
     */
    public void setAllPwm(int on, int off) throws IOException {
        writeReg(REG_ALL_LED, pwmBytes(on, off));
    }

    /**
     * Set the PWM frequency with the internal 25 MHz oscillator.
     *
     * @param hz wanted frequency in Hz (24–1526)
     * @throws IOException on I²C error
     */
    public void setFrequency(double hz) throws IOException {
        setFrequency(hz, 25_000_000.0);
    }

    /**
     * Set the PWM frequency: PRE_SCALE = round(oscHz / (4096 × hz)) − 1, clamped 3–255. Puts the chip
     * to sleep, writes PRE_SCALE, wakes it, waits 500 µs and restarts running channels.
     *
     * @param hz    wanted frequency in Hz
     * @param oscHz oscillator frequency in Hz (25 MHz internal, or the EXTCLK frequency)
     * @throws IOException on I²C error
     */
    public void setFrequency(double hz, double oscHz) throws IOException {
        if (!(hz > 0) || !(oscHz > 0)) throw new IllegalArgumentException("hz and oscHz must be positive");
        int prescale = (int) Math.max(3, Math.min(255, Math.floor(oscHz / (4096.0 * hz) + 0.5) - 1));
        int old = mode1();
        int awake = old & ~(RESTART | SLEEP) & 0xFF;
        writeReg(REG_MODE1, (old & 0x7F) | SLEEP);
        writeReg(REG_PRE_SCALE, prescale);
        writeReg(REG_MODE1, awake);
        pauseMs(1);
        if ((old & RESTART) != 0) writeReg(REG_MODE1, awake | RESTART);
    }

    /**
     * Read the PWM frequency with the internal 25 MHz oscillator.
     *
     * @return frequency in Hz
     * @throws IOException on I²C error
     */
    public double getFrequency() throws IOException {
        return getFrequency(25_000_000.0);
    }

    /**
     * Read PRE_SCALE and return the PWM frequency.
     *
     * @param oscHz oscillator frequency in Hz
     * @return {@code oscHz / (4096 × (PRE_SCALE + 1))} in Hz
     * @throws IOException on I²C error
     */
    public double getFrequency(double oscHz) throws IOException {
        return oscHz / (4096.0 * ((readReg(REG_PRE_SCALE, 1)[0] & 0xFF) + 1));
    }

    /**
     * Set a pulse width in seconds (servo/ESC mode): OFF = round(seconds × frequency × 4096). Uses the
     * frequency programmed in PRE_SCALE (25 MHz oscillator).
     *
     * @param channel 0–15
     * @param seconds pulse width, 0 up to one PWM period
     * @throws IOException on I²C error
     */
    public void setPulseWidth(int channel, double seconds) throws IOException {
        checkChannel(channel);
        if (!(seconds >= 0)) throw new IllegalArgumentException("seconds must be >= 0");
        setDuty(channel, Math.min(1.0, seconds * getFrequency()));
    }

    /**
     * Enter low-power mode (MODE1.SLEEP = 1, oscillator off; outputs stop).
     *
     * @throws IOException on I²C error
     */
    public void sleep() throws IOException {
        writeReg(REG_MODE1, (mode1() & 0x7F) | SLEEP);
    }

    /**
     * Leave low-power mode, wait 500 µs and restart channels that were running.
     *
     * @throws IOException on I²C error
     */
    public void wake() throws IOException {
        int old = mode1();
        writeReg(REG_MODE1, old & ~(RESTART | SLEEP) & 0xFF);
        pauseMs(1);
        if ((old & RESTART) != 0) writeReg(REG_MODE1, (old & ~SLEEP & 0xFF) | RESTART);
    }

    /**
     * Run the datasheet restart sequence (read MODE1, clear SLEEP, wait 500 µs, write RESTART).
     *
     * @throws IOException on I²C error
     */
    public void restart() throws IOException {
        int old = mode1();
        writeReg(REG_MODE1, old & ~(RESTART | SLEEP) & 0xFF);
        pauseMs(1);
        writeReg(REG_MODE1, (old & ~SLEEP & 0xFF) | RESTART);
    }

    /**
     * Configure MODE2: output driver, inversion and the output state while OE = 1. The update-on-ACK
     * bit is preserved.
     *
     * @param openDrain true for open-drain, false for totem-pole
     * @param invert    true to invert the output logic
     * @param offState  {@link #OFF_LOW}, {@link #OFF_HIGH_OR_HIGHZ} or {@link #OFF_HIGHZ}
     * @throws IOException on I²C error
     */
    public void setOutputMode(boolean openDrain, boolean invert, int offState) throws IOException {
        if (offState < 0 || offState > 2) throw new IllegalArgumentException("offState must be 0-2");
        int mode2 = offState | (invert ? 0x10 : 0) | (openDrain ? 0 : 0x04);
        int old = readReg(REG_MODE2, 1)[0] & 0xFF;
        writeReg(REG_MODE2, (old & 0x08) | mode2);
    }

    /**
     * Select when outputs change: on ACK (true, MODE2.OCH = 1) or on STOP (false, default).
     *
     * @param enabled true to update on ACK
     * @throws IOException on I²C error
     */
    public void setUpdateOnAck(boolean enabled) throws IOException {
        int old = readReg(REG_MODE2, 1)[0] & 0xFF;
        writeReg(REG_MODE2, enabled ? (old | 0x08) : (old & ~0x08 & 0xFF));
    }

    /**
     * Switch to the EXTCLK pin as clock. Sticky until power cycle / software reset; afterwards pass
     * the external clock frequency as {@code oscHz} to {@link #setFrequency(double, double)} /
     * {@link #getFrequency(double)}.
     *
     * @throws IOException on I²C error
     */
    public void useExternalClock() throws IOException {
        int sleeping = (mode1() & 0x7F) | SLEEP;
        writeReg(REG_MODE1, sleeping);
        writeReg(REG_MODE1, sleeping | EXTCLK);
        writeReg(REG_MODE1, (sleeping | EXTCLK) & ~SLEEP & 0xFF);
        pauseMs(1);
    }

    /**
     * Enable or disable the LED All Call address (default value 0x70).
     *
     * @param enabled true to respond to the All Call address
     * @throws IOException on I²C error
     */
    public void setAllcall(boolean enabled) throws IOException {
        setAllcall(enabled, 0x70);
    }

    /**
     * Enable or disable the LED All Call address and set its value.
     *
     * @param enabled true to respond to the All Call address
     * @param address 7-bit address
     * @throws IOException on I²C error
     */
    public void setAllcall(boolean enabled, int address) throws IOException {
        if (address < 0 || address > 0x7F) throw new IllegalArgumentException("address must be 0-127");
        writeReg(REG_ALLCALLADR, address << 1);
        writeReg(REG_MODE1, (mode1() & 0x7E) | (enabled ? 1 : 0));
    }

    /**
     * Program and enable/disable one of the three LED Sub Call addresses.
     *
     * @param index   1–3
     * @param address 7-bit address
     * @param enabled true to respond to the sub address
     * @throws IOException on I²C error
     */
    public void setSubaddress(int index, int address, boolean enabled) throws IOException {
        if (index < 1 || index > 3) throw new IllegalArgumentException("index must be 1-3");
        if (address < 0 || address > 0x7F) throw new IllegalArgumentException("address must be 0-127");
        writeReg(REG_SUBADR1 + index - 1, address << 1);
        int mask = 1 << (4 - index);
        int old = mode1() & 0x7F;
        writeReg(REG_MODE1, enabled ? (old | mask) : (old & ~mask & 0xFF));
    }

    /**
     * Return the chip registers to their power-on values by writing the documented defaults
     * (MODE1 = 0x11, MODE2 = 0x04, PRE_SCALE = 0x1E, outputs off). Does not send the general-call
     * software reset (see {@link #softwareReset(Connection)}).
     *
     * @throws IOException on I²C error
     */
    public void reset() throws IOException {
        writeReg(REG_MODE1, 0x10);
        writeReg(REG_PRE_SCALE, 0x1E);
        writeReg(REG_MODE2, 0x04);
        writeReg(REG_ALL_LED, 0, 0, 0, 0x10);
        writeReg(REG_SUBADR1, 0xE2, 0xE4, 0xE8, 0xE0);
        writeReg(REG_MODE1, 0x11);
    }

    /**
     * Send the Software Reset call (0x06) over a connection bound to the general call address 0x00.
     * Resets every PCA9685 on the bus; wait at least 500 µs before using the chips again.
     *
     * @param generalCall connection bound to I²C address 0x00
     * @throws IOException on I²C error
     */
    public static void softwareReset(Connection generalCall) throws IOException {
        generalCall.write(new byte[]{0x06});
    }

    /**
     * Read the raw mode registers.
     *
     * @return {@code {mode1, mode2}}
     * @throws IOException on I²C error
     */
    public int[] readMode() throws IOException {
        byte[] d = readReg(REG_MODE1, 2);
        return new int[]{d[0] & 0xFF, d[1] & 0xFF};
    }
}
