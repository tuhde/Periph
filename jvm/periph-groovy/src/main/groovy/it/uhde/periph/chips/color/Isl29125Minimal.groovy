package it.uhde.periph.chips.color

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/**
 * ISL29125 — digital red, green and blue colour light sensor (Renesas /
 * Intersil) — minimal interface.
 *
 * <p>RGB colour sensor with an IR-blocking filter, a 12/16-bit integrating ADC
 * (16 bit: 100 ms), two sensing ranges (375 lux and 10 000 lux full scale) and
 * programmable IR compensation. Results are unsigned 16-bit counts, LSB first.
 * The I²C address is fixed at 0x44.
 *
 * <p>Construction reads DEVICE_ID (must be 0x7D), clears the brown-out flag and
 * writes CONFIG1 = 0x0D (RGB, 10 000 lux, 16 bit), CONFIG2 = 0xBF (maximum IR
 * compensation), CONFIG3 = 0x00. Reading STATUS clears its flags, so the
 * driver keeps the flags it has seen until
 * {@link Isl29125Full#pollInterrupt()} / {@link Isl29125Full#status()} hands
 * them out.
 */
@CompileStatic
class Isl29125Minimal {

    /** Fixed 7-bit I²C address. */
    public static final int DEFAULT_ADDRESS = 0x44
    /** Expected DEVICE_ID value. */
    public static final int DEVICE_ID = 0x7D

    /** Poll step while waiting for CONVENF, in ms (tests may set it to 0). */
    public static volatile int pollStepMs = 10

    protected static final int REG_DEVICE_ID = 0x00
    protected static final int REG_CONFIG1 = 0x01
    protected static final int REG_CONFIG2 = 0x02
    protected static final int REG_CONFIG3 = 0x03
    protected static final int REG_LOW_TH = 0x04
    protected static final int REG_HIGH_TH = 0x06
    protected static final int REG_STATUS = 0x08
    protected static final int REG_GREEN = 0x09
    protected static final int REG_RED = 0x0B
    protected static final int REG_BLUE = 0x0D

    protected static final int RESET_VALUE = 0x46
    protected static final int CFG1_SYNC = 0x20
    protected static final int CFG1_BITS_12 = 0x10
    protected static final int CFG1_RNG_10K = 0x08
    protected static final int CFG1_MODE = 0x07
    protected static final int CFG3_CONVEN = 0x10

    /** STATUS bit 2: power-down or brown-out occurred. */
    public static final int STATUS_BROWNOUT = 0x04
    /** STATUS bit 1: conversion completed. */
    public static final int STATUS_CONVERSION = 0x02
    /** STATUS bit 0: threshold window crossed. */
    public static final int STATUS_THRESHOLD = 0x01

    private static final int WAIT_LIMIT_MS = 400

    /** The three channel counts of one conversion (unsigned 16 bit). */
    static final class Counts {
        final int red
        final int green
        final int blue

        Counts(int red, int green, int blue) {
            this.red = red
            this.green = green
            this.blue = blue
        }
    }

    protected final RegisterConnection connection
    protected int pending

    /**
     * Construct the driver and start the sensor.
     *
     * @param connection I²C register connection bound to the device (0x44)
     * @throws IllegalStateException if DEVICE_ID does not identify an ISL29125
     */
    Isl29125Minimal(RegisterConnection connection) {
        this.connection = connection
        int id = readReg(REG_DEVICE_ID)
        if (id != DEVICE_ID) {
            throw new IllegalStateException(String.format('ISL29125 not found (DEVICE_ID 0x%02X)', id))
        }
        writeReg(REG_STATUS, 0x00)
        writeReg(REG_CONFIG3, 0x00)
        writeReg(REG_CONFIG2, 0xBF)
        writeReg(REG_CONFIG1, 0x0D)
    }

    protected static void sleepMs(int ms) {
        if (ms <= 0) return
        try {
            Thread.sleep((long) ms)
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
        }
    }

    protected int readReg(int reg) {
        return connection.read(reg, 1)[0] & 0xFF
    }

    protected void writeReg(int reg, int value) {
        connection.write(reg, [(byte) value] as byte[])
    }

    protected void updateReg(int reg, int setBits, int clearBits) {
        int value = readReg(reg)
        writeReg(reg, (value & ~clearBits) | setBits)
    }

    /** Read STATUS once and keep its flag bits until handed out. */
    protected int readStatus() {
        int value = readReg(REG_STATUS)
        this.@pending = this.@pending | (value & (STATUS_BROWNOUT | STATUS_CONVERSION | STATUS_THRESHOLD))
        return value
    }

    protected static int word(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
    }

    protected Counts readBlock() {
        byte[] raw = connection.read(REG_GREEN, 6)
        return new Counts(word(raw, 2), word(raw, 0), word(raw, 4))
    }

    protected int readChannel(int reg) {
        return word(connection.read(reg, 2), 0)
    }

    /**
     * Read red, green and blue from one conversion. Waits for a new result (at
     * most 400 ms), then block-reads 0x09–0x0E.
     *
     * @return the three unsigned 16-bit counts
     */
    Counts read() {
        int step = pollStepMs > 0 ? pollStepMs : 1
        for (int waited = 0; waited <= WAIT_LIMIT_MS; waited += step) {
            if ((readStatus() & STATUS_CONVERSION) != 0) break
            sleepMs(pollStepMs)
        }
        return readBlock()
    }
}
