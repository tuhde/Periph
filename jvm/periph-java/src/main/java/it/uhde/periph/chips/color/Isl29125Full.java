package it.uhde.periph.chips.color;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * ISL29125 full interface — extends {@link Isl29125Minimal} with
 * single-channel reads, mode, range, resolution, synchronisation and
 * IR-compensation configuration, thresholds, persistence, and interrupt
 * delivery.
 *
 * <p>Setters with a restricted value throw {@link IllegalArgumentException}
 * and write nothing for an unsupported argument.
 */
public class Isl29125Full extends Isl29125Minimal {

    /** Interrupt source: selected channel outside the threshold window. */
    public static final int SOURCE_THRESHOLD = 0x01;
    /** Interrupt source: conversion complete. */
    public static final int SOURCE_CONVERSION = 0x02;

    /** Mode: power-down. */
    public static final int MODE_POWER_DOWN = 0;
    /** Mode: green only. */
    public static final int MODE_GREEN = 1;
    /** Mode: red only. */
    public static final int MODE_RED = 2;
    /** Mode: blue only. */
    public static final int MODE_BLUE = 3;
    /** Mode: standby (no conversion). */
    public static final int MODE_STANDBY = 4;
    /** Mode: green, red and blue. */
    public static final int MODE_RGB = 5;
    /** Mode: green and red. */
    public static final int MODE_GREEN_RED = 6;
    /** Mode: green and blue. */
    public static final int MODE_GREEN_BLUE = 7;

    /** Interrupt channel: none. */
    public static final int CHANNEL_NONE = 0;
    /** Interrupt channel: green. */
    public static final int CHANNEL_GREEN = 1;
    /** Interrupt channel: red. */
    public static final int CHANNEL_RED = 2;
    /** Interrupt channel: blue. */
    public static final int CHANNEL_BLUE = 3;

    private static final int[] PERSISTENCE = {1, 2, 4, 8};

    private volatile IntConsumer callback;
    private InputPin intPinUsed;
    private Thread pollThread;
    private volatile boolean polling;

    /** Fixed edge handler so offEdge() can remove the exact instance that was registered. */
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * Construct the driver and start the sensor.
     *
     * @param connection I²C register connection bound to the device (0x44)
     * @throws IOException on bus error
     */
    public Isl29125Full(RegisterConnection connection) throws IOException {
        super(connection);
    }

    /** @return red count (2-byte read) @throws IOException on bus error */
    public int readRed() throws IOException { return readChannel(REG_RED); }

    /** @return green count @throws IOException on bus error */
    public int readGreen() throws IOException { return readChannel(REG_GREEN); }

    /** @return blue count @throws IOException on bus error */
    public int readBlue() throws IOException { return readChannel(REG_BLUE); }

    /** @return the three counts, block-read without waiting @throws IOException on bus error */
    public Counts readRgb() throws IOException { return readBlock(); }

    /**
     * Report whether a conversion has completed. Reads STATUS, which clears
     * its flags; the flags are kept for {@link #pollInterrupt()} and {@link #status()}.
     *
     * @return true if CONVENF was set
     * @throws IOException on bus error
     */
    public boolean dataReady() throws IOException {
        return (readStatus() & STATUS_CONVERSION) != 0;
    }

    /**
     * Select the operating mode (conversions are continuous).
     *
     * @param mode {@link #MODE_POWER_DOWN} … {@link #MODE_GREEN_BLUE} (0–7)
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if outside 0–7
     */
    public void setMode(int mode) throws IOException {
        if (mode < MODE_POWER_DOWN || mode > MODE_GREEN_BLUE) {
            throw new IllegalArgumentException("mode must be 0-7");
        }
        updateReg(REG_CONFIG1, mode, CFG1_MODE);
    }

    /**
     * Select the sensing range.
     *
     * @param lux 375 or 10000 (full-scale illuminance)
     * @throws IOException              on bus error
     * @throws IllegalArgumentException for any other value
     */
    public void setRange(int lux) throws IOException {
        if (lux == 375) updateReg(REG_CONFIG1, 0, CFG1_RNG_10K);
        else if (lux == 10000) updateReg(REG_CONFIG1, CFG1_RNG_10K, 0);
        else throw new IllegalArgumentException("range must be 375 or 10000");
    }

    /**
     * Select the ADC resolution.
     *
     * @param bits 12 or 16
     * @throws IOException              on bus error
     * @throws IllegalArgumentException for any other value
     */
    public void setResolution(int bits) throws IOException {
        if (bits == 16) updateReg(REG_CONFIG1, 0, CFG1_BITS_12);
        else if (bits == 12) updateReg(REG_CONFIG1, CFG1_BITS_12, 0);
        else throw new IllegalArgumentException("resolution must be 12 or 16");
    }

    /**
     * Select the conversion start source.
     *
     * @param external true: the rising edge of INT starts the conversion (INT becomes an input); false: the I²C write
     * @throws IOException on bus error
     */
    public void setSync(boolean external) throws IOException {
        updateReg(REG_CONFIG1, external ? CFG1_SYNC : 0, CFG1_SYNC);
    }

    /**
     * Set the active IR compensation (CONFIG2).
     *
     * @param offset false: codes 0–63; true: codes 106–169
     * @param adjust compensation adjust, 0–63
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if {@code adjust} is outside 0–63
     */
    public void setIrCompensation(boolean offset, int adjust) throws IOException {
        if (adjust < 0 || adjust > 63) throw new IllegalArgumentException("adjust must be 0-63");
        writeReg(REG_CONFIG2, (offset ? 0x80 : 0) | adjust);
    }

    /** @return DEVICE_ID (0x7D for the ISL29125) @throws IOException on bus error */
    public int deviceId() throws IOException { return readReg(REG_DEVICE_ID); }

    /**
     * Read STATUS (clearing the flags on the chip).
     *
     * @return raw status byte with the flags seen since the last call merged in
     * @throws IOException on bus error
     */
    public int status() throws IOException {
        int value = readStatus() | pending;
        pending = 0;
        return value;
    }

    /** Reset all registers to their defaults (writes 0x46 to register 0x00). @throws IOException on bus error */
    public void softReset() throws IOException {
        writeReg(REG_DEVICE_ID, RESET_VALUE);
        sleep(pollStepMs > 0 ? 10 : 0);
        pending = 0;
    }

    /**
     * Set the 16-bit threshold window.
     *
     * @param low  lower threshold (0–65535)
     * @param high upper threshold (0–65535)
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if a value is outside 16 bit or {@code low > high}
     */
    public void setThresholds(int low, int high) throws IOException {
        if (low < 0 || high < 0 || low > 0xFFFF || high > 0xFFFF || low > high) {
            throw new IllegalArgumentException("thresholds must satisfy 0 <= low <= high <= 65535");
        }
        connection.write(REG_LOW_TH, new byte[]{(byte) low, (byte) (low >> 8)});
        connection.write(REG_HIGH_TH, new byte[]{(byte) high, (byte) (high >> 8)});
    }

    /** @return the threshold window as {@code {low, high}} @throws IOException on bus error */
    public int[] getThresholds() throws IOException {
        return new int[]{word(connection.read(REG_LOW_TH, 2), 0), word(connection.read(REG_HIGH_TH, 2), 0)};
    }

    /**
     * Select the channel the threshold interrupt monitors.
     *
     * @param channel {@link #CHANNEL_NONE}, {@link #CHANNEL_GREEN}, {@link #CHANNEL_RED} or {@link #CHANNEL_BLUE}
     * @throws IOException              on bus error
     * @throws IllegalArgumentException for an unknown channel
     */
    public void setInterruptChannel(int channel) throws IOException {
        if (channel < CHANNEL_NONE || channel > CHANNEL_BLUE) throw new IllegalArgumentException("channel must be 0-3");
        updateReg(REG_CONFIG3, channel, 0x03);
    }

    /**
     * Set the number of integration cycles before the interrupt asserts.
     *
     * @param count 1, 2, 4 or 8
     * @throws IOException              on bus error
     * @throws IllegalArgumentException for any other value
     */
    public void setPersistence(int count) throws IOException {
        for (int code = 0; code < PERSISTENCE.length; code++) {
            if (PERSISTENCE[code] == count) {
                updateReg(REG_CONFIG3, code << 2, 0x0C);
                return;
            }
        }
        throw new IllegalArgumentException("count must be 1, 2, 4 or 8");
    }

    /**
     * Enable an interrupt source. For {@link #SOURCE_THRESHOLD} select the
     * channel beforehand; green is used if none is set.
     *
     * @param source {@link #SOURCE_THRESHOLD} or {@link #SOURCE_CONVERSION}
     * @throws IOException on bus error
     */
    public void enableInterrupt(int source) throws IOException {
        if (source == SOURCE_CONVERSION) {
            updateReg(REG_CONFIG3, CFG3_CONVEN, 0);
        } else if ((readReg(REG_CONFIG3) & 0x03) == 0) {
            updateReg(REG_CONFIG3, CHANNEL_GREEN, 0);
        }
    }

    /**
     * Disable an interrupt source (threshold: INTSEL = none; conversion: CONVEN = 0).
     *
     * @param source {@link #SOURCE_THRESHOLD} or {@link #SOURCE_CONVERSION}
     * @throws IOException on bus error
     */
    public void disableInterrupt(int source) throws IOException {
        if (source == SOURCE_CONVERSION) updateReg(REG_CONFIG3, 0, CFG3_CONVEN);
        else updateReg(REG_CONFIG3, 0, 0x03);
    }

    /**
     * Read STATUS (clearing the flags) and return it; same as {@link #status()}.
     *
     * @return status byte; {@link #STATUS_THRESHOLD} / {@link #STATUS_CONVERSION} are set for events seen
     * @throws IOException on bus error
     */
    public int pollInterrupt() throws IOException { return status(); }

    /**
     * Subscribe to INT assertions using {@code connection.intPin()}.
     *
     * @param callback called with the STATUS byte after it has been read and cleared
     */
    public void onInterrupt(IntConsumer callback) {
        onInterrupt(callback, null);
    }

    /**
     * Subscribe to INT assertions (falling edge). Falls back to a 50&nbsp;ms
     * polling thread if no {@link InputPin} is available.
     *
     * @param callback called with the STATUS byte after it has been read and cleared
     * @param intPin   overrides {@code connection.intPin()}, or {@code null}
     */
    public synchronized void onInterrupt(IntConsumer callback, InputPin intPin) {
        this.callback = callback;
        startDelivery(intPin != null ? intPin : connection.intPin());
    }

    private void startDelivery(InputPin pin) {
        stopDelivery();
        intPinUsed = pin;
        if (pin != null) {
            pin.onEdge(edgeHandler, EdgeTrigger.FALLING);
        } else {
            polling = true;
            pollThread = new Thread(() -> {
                while (polling) {
                    handleEdge();
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "isl29125-poll");
            pollThread.setDaemon(true);
            pollThread.start();
        }
    }

    private void stopDelivery() {
        if (intPinUsed != null) {
            intPinUsed.offEdge(edgeHandler);
            intPinUsed = null;
        }
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    /** Unsubscribe and stop delivery. */
    public synchronized void offInterrupt() {
        stopDelivery();
        callback = null;
    }

    private void handleEdge() {
        try {
            int status = pollInterrupt();
            IntConsumer cb = callback;
            if ((status & (STATUS_THRESHOLD | STATUS_CONVERSION)) != 0 && cb != null) cb.accept(status);
        } catch (IOException ignored) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }
}
