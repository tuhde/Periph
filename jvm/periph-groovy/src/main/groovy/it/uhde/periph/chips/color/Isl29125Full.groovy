package it.uhde.periph.chips.color

import groovy.transform.CompileStatic
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection

import java.util.function.IntConsumer

/**
 * ISL29125 full interface — extends {@link Isl29125Minimal} with
 * single-channel reads, mode, range, resolution, synchronisation and
 * IR-compensation configuration, thresholds, persistence, and interrupt
 * delivery.
 *
 * <p>Setters with a restricted value throw {@link IllegalArgumentException}
 * and write nothing for an unsupported argument.
 */
@CompileStatic
class Isl29125Full extends Isl29125Minimal {

    /** Interrupt source: selected channel outside the threshold window. */
    public static final int SOURCE_THRESHOLD = 0x01
    /** Interrupt source: conversion complete. */
    public static final int SOURCE_CONVERSION = 0x02

    /** Mode: power-down. */
    public static final int MODE_POWER_DOWN = 0
    /** Mode: green only. */
    public static final int MODE_GREEN = 1
    /** Mode: red only. */
    public static final int MODE_RED = 2
    /** Mode: blue only. */
    public static final int MODE_BLUE = 3
    /** Mode: standby (no conversion). */
    public static final int MODE_STANDBY = 4
    /** Mode: green, red and blue. */
    public static final int MODE_RGB = 5
    /** Mode: green and red. */
    public static final int MODE_GREEN_RED = 6
    /** Mode: green and blue. */
    public static final int MODE_GREEN_BLUE = 7

    /** Interrupt channel: none. */
    public static final int CHANNEL_NONE = 0
    /** Interrupt channel: green. */
    public static final int CHANNEL_GREEN = 1
    /** Interrupt channel: red. */
    public static final int CHANNEL_RED = 2
    /** Interrupt channel: blue. */
    public static final int CHANNEL_BLUE = 3

    private static final int[] PERSISTENCE = [1, 2, 4, 8] as int[]

    protected volatile IntConsumer callback
    private InputPin intPinUsed
    private Thread pollThread
    private volatile boolean polling

    /** Fixed edge handler so offEdge() can remove the exact instance that was registered. */
    protected final EdgeHandler edgeHandler = { -> handleEdge() } as EdgeHandler

    /**
     * Construct the driver and start the sensor.
     *
     * @param connection I²C register connection bound to the device (0x44)
     */
    Isl29125Full(RegisterConnection connection) {
        super(connection)
    }

    /** @return red count (2-byte read) */
    int readRed() { return readChannel(REG_RED) }

    /** @return green count */
    int readGreen() { return readChannel(REG_GREEN) }

    /** @return blue count */
    int readBlue() { return readChannel(REG_BLUE) }

    /**
     * Block-read all three channels without waiting.
     *
     * @return the three counts
     */
    Counts readRgb() { return readBlock() }

    /**
     * Report whether a conversion has completed. Reads STATUS, which clears
     * its flags; the flags are kept for {@link #pollInterrupt()} and {@link #status()}.
     *
     * @return true if CONVENF was set
     */
    boolean dataReady() {
        return (readStatus() & STATUS_CONVERSION) != 0
    }

    /**
     * Select the operating mode (conversions are continuous).
     *
     * @param mode {@link #MODE_POWER_DOWN} … {@link #MODE_GREEN_BLUE} (0–7)
     * @throws IllegalArgumentException if outside 0–7
     */
    void setMode(int mode) {
        if (mode < MODE_POWER_DOWN || mode > MODE_GREEN_BLUE) throw new IllegalArgumentException('mode must be 0-7')
        updateReg(REG_CONFIG1, mode, CFG1_MODE)
    }

    /**
     * Select the sensing range.
     *
     * @param lux 375 or 10000 (full-scale illuminance)
     * @throws IllegalArgumentException for any other value
     */
    void setRange(int lux) {
        if (lux == 375) updateReg(REG_CONFIG1, 0, CFG1_RNG_10K)
        else if (lux == 10000) updateReg(REG_CONFIG1, CFG1_RNG_10K, 0)
        else throw new IllegalArgumentException('range must be 375 or 10000')
    }

    /**
     * Select the ADC resolution.
     *
     * @param bits 12 or 16
     * @throws IllegalArgumentException for any other value
     */
    void setResolution(int bits) {
        if (bits == 16) updateReg(REG_CONFIG1, 0, CFG1_BITS_12)
        else if (bits == 12) updateReg(REG_CONFIG1, CFG1_BITS_12, 0)
        else throw new IllegalArgumentException('resolution must be 12 or 16')
    }

    /**
     * Select the conversion start source.
     *
     * @param external true: the rising edge of INT starts the conversion (INT becomes an input); false: the I²C write
     */
    void setSync(boolean external) {
        updateReg(REG_CONFIG1, external ? CFG1_SYNC : 0, CFG1_SYNC)
    }

    /**
     * Set the active IR compensation (CONFIG2).
     *
     * @param offset false: codes 0–63; true: codes 106–169
     * @param adjust compensation adjust, 0–63
     * @throws IllegalArgumentException if {@code adjust} is outside 0–63
     */
    void setIrCompensation(boolean offset, int adjust) {
        if (adjust < 0 || adjust > 63) throw new IllegalArgumentException('adjust must be 0-63')
        writeReg(REG_CONFIG2, (offset ? 0x80 : 0) | adjust)
    }

    /** @return DEVICE_ID (0x7D for the ISL29125) */
    int deviceId() { return readReg(REG_DEVICE_ID) }

    /**
     * Read STATUS (clearing the flags on the chip).
     *
     * @return raw status byte with the flags seen since the last call merged in
     */
    int status() {
        int value = readStatus() | this.@pending
        this.@pending = 0
        return value
    }

    /** Reset all registers to their defaults (writes 0x46 to register 0x00). */
    void softReset() {
        writeReg(REG_DEVICE_ID, RESET_VALUE)
        sleepMs(pollStepMs > 0 ? 10 : 0)
        this.@pending = 0
    }

    /**
     * Set the 16-bit threshold window.
     *
     * @param low  lower threshold (0–65535)
     * @param high upper threshold (0–65535)
     * @throws IllegalArgumentException if a value is outside 16 bit or {@code low > high}
     */
    void setThresholds(int low, int high) {
        if (low < 0 || high < 0 || low > 0xFFFF || high > 0xFFFF || low > high) {
            throw new IllegalArgumentException('thresholds must satisfy 0 <= low <= high <= 65535')
        }
        connection.write(REG_LOW_TH, [(byte) low, (byte) (low >> 8)] as byte[])
        connection.write(REG_HIGH_TH, [(byte) high, (byte) (high >> 8)] as byte[])
    }

    /** @return the threshold window as {@code [low, high]} */
    int[] getThresholds() {
        return [word(connection.read(REG_LOW_TH, 2), 0), word(connection.read(REG_HIGH_TH, 2), 0)] as int[]
    }

    /**
     * Select the channel the threshold interrupt monitors.
     *
     * @param channel {@link #CHANNEL_NONE}, {@link #CHANNEL_GREEN}, {@link #CHANNEL_RED} or {@link #CHANNEL_BLUE}
     * @throws IllegalArgumentException for an unknown channel
     */
    void setInterruptChannel(int channel) {
        if (channel < CHANNEL_NONE || channel > CHANNEL_BLUE) throw new IllegalArgumentException('channel must be 0-3')
        updateReg(REG_CONFIG3, channel, 0x03)
    }

    /**
     * Set the number of integration cycles before the interrupt asserts.
     *
     * @param count 1, 2, 4 or 8
     * @throws IllegalArgumentException for any other value
     */
    void setPersistence(int count) {
        for (int code = 0; code < PERSISTENCE.length; code++) {
            if (PERSISTENCE[code] == count) {
                updateReg(REG_CONFIG3, code << 2, 0x0C)
                return
            }
        }
        throw new IllegalArgumentException('count must be 1, 2, 4 or 8')
    }

    /**
     * Enable an interrupt source. For {@link #SOURCE_THRESHOLD} select the
     * channel beforehand; green is used if none is set.
     *
     * @param source {@link #SOURCE_THRESHOLD} or {@link #SOURCE_CONVERSION}
     */
    void enableInterrupt(int source = SOURCE_THRESHOLD) {
        if (source == SOURCE_CONVERSION) {
            updateReg(REG_CONFIG3, CFG3_CONVEN, 0)
        } else if ((readReg(REG_CONFIG3) & 0x03) == 0) {
            updateReg(REG_CONFIG3, CHANNEL_GREEN, 0)
        }
    }

    /**
     * Disable an interrupt source (threshold: INTSEL = none; conversion: CONVEN = 0).
     *
     * @param source {@link #SOURCE_THRESHOLD} or {@link #SOURCE_CONVERSION}
     */
    void disableInterrupt(int source = SOURCE_THRESHOLD) {
        if (source == SOURCE_CONVERSION) updateReg(REG_CONFIG3, 0, CFG3_CONVEN)
        else updateReg(REG_CONFIG3, 0, 0x03)
    }

    /**
     * Read STATUS (clearing the flags) and return it; same as {@link #status()}.
     *
     * @return status byte; {@link #STATUS_THRESHOLD} / {@link #STATUS_CONVERSION} are set for events seen
     */
    int pollInterrupt() { return status() }

    /**
     * Subscribe to INT assertions (falling edge). Falls back to a 50&nbsp;ms
     * polling thread if no {@link InputPin} is available.
     *
     * @param callback called with the STATUS byte after it has been read and cleared
     * @param intPin   overrides {@code connection.intPin()}, or {@code null}
     */
    synchronized void onInterrupt(IntConsumer callback, InputPin intPin = null) {
        this.callback = callback
        startDelivery(intPin != null ? intPin : connection.intPin())
    }

    private void startDelivery(InputPin pin) {
        stopDelivery()
        intPinUsed = pin
        if (pin != null) {
            pin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = new Thread({
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(50L)
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            } as Runnable, 'isl29125-poll')
            pollThread.setDaemon(true)
            pollThread.start()
        }
    }

    private void stopDelivery() {
        if (intPinUsed != null) {
            intPinUsed.offEdge(edgeHandler)
            intPinUsed = null
        }
        polling = false
        if (pollThread != null) {
            pollThread.interrupt()
            pollThread = null
        }
    }

    /** Unsubscribe and stop delivery. */
    synchronized void offInterrupt() {
        stopDelivery()
        callback = null
    }

    protected void handleEdge() {
        try {
            int status = pollInterrupt()
            IntConsumer cb = callback
            if ((status & (STATUS_THRESHOLD | STATUS_CONVERSION)) != 0 && cb != null) cb.accept(status)
        } catch (IOException ignored) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }
}
