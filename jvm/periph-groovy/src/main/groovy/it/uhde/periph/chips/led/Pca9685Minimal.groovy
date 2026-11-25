package it.uhde.periph.chips.led

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/**
 * PCA9685 — 16-channel, 12-bit PWM LED/servo controller (minimal driver).
 *
 * <p>Dims LEDs / drives servos on 16 channels with one call each. I²C address 0x40–0x6F or
 * 0x71–0x77 (A0–A5 pins; 0x70 is the All Call address).
 *
 * <p>Defaults baked in at construction: the chip is put to sleep, PRE_SCALE is set for 1 kHz (5),
 * then it is woken with auto-increment on and ALLCALL off and the oscillator start is waited for
 * (1 ms). Outputs are totem-pole, not inverted, and change on the I²C STOP; all channels keep their
 * power-on state (off).
 */
@CompileStatic
class Pca9685Minimal {

    static final int CHANNELS = 16

    protected static final int REG_MODE1 = 0x00
    protected static final int REG_MODE2 = 0x01
    protected static final int REG_SUBADR1 = 0x02
    protected static final int REG_ALLCALLADR = 0x05
    protected static final int REG_LED0 = 0x06
    protected static final int REG_ALL_LED = 0xFA
    protected static final int REG_PRE_SCALE = 0xFE

    protected static final int RESTART = 0x80
    protected static final int EXTCLK = 0x40
    protected static final int AI = 0x20
    protected static final int SLEEP = 0x10

    protected final RegisterConnection connection

    /**
     * Construct the driver and run the initialization sequence.
     *
     * @param connection I²C register connection bound to the chip address (0x40–0x6F, 0x71–0x77)
     */
    Pca9685Minimal(RegisterConnection connection) {
        this.connection = connection
        writeReg(REG_MODE1, AI | SLEEP)
        writeReg(REG_PRE_SCALE, 5)
        writeReg(REG_MODE1, AI)
        pauseMs(1)
    }

    protected static void pauseMs(long ms) {
        try {
            Thread.sleep(ms)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
        }
    }

    protected synchronized void writeReg(int reg, int... data) {
        byte[] b = new byte[data.length]
        for (int i = 0; i < data.length; i++) b[i] = (byte) data[i]
        connection.write(reg, b)
    }

    protected synchronized byte[] readReg(int reg, int length) {
        return connection.read(reg, length)
    }

    protected static void checkChannel(int channel) {
        if (channel < 0 || channel > 15) throw new IllegalArgumentException("channel must be 0-15")
    }

    /** ON/OFF register bytes for a duty cycle; 0.0 → full OFF, 1.0 → full ON. */
    protected static int[] dutyBytes(double duty) {
        if (!(duty >= 0.0 && duty <= 1.0)) throw new IllegalArgumentException("duty must be 0.0-1.0")
        int off = (int) Math.floor(duty * 4096 + 0.5)
        if (off <= 0) return [0, 0, 0, 0x10] as int[]
        if (off >= 4096) return [0, 0x10, 0, 0] as int[]
        return [0, 0, off & 0xFF, off >> 8] as int[]
    }

    /**
     * Set the duty cycle of one channel (ON = 0, OFF = round(duty × 4096)); 0.0 writes full OFF and
     * 1.0 writes full ON, so no glitch pulses occur.
     *
     * @param channel 0–15
     * @param duty    0.0–1.0
     *      * @throws IllegalArgumentException if an argument is out of range
     */
    void setDuty(int channel, double duty) {
        checkChannel(channel)
        writeReg(REG_LED0 + 4 * channel, dutyBytes(duty))
    }
}
