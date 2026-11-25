package it.uhde.periph.chips.color

import it.uhde.periph.connection.RegisterConnection

/**
 * ISL29125 — digital red, green and blue colour light sensor (Renesas /
 * Intersil) — minimal interface.
 *
 * RGB colour sensor with an IR-blocking filter, a 12/16-bit integrating ADC
 * (16 bit: 100 ms), two sensing ranges (375 lux and 10 000 lux full scale) and
 * programmable IR compensation. Results are unsigned 16-bit counts, LSB first.
 * The I²C address is fixed at 0x44.
 *
 * Construction reads DEVICE_ID (must be 0x7D), clears the brown-out flag and
 * writes CONFIG1 = 0x0D (RGB, 10 000 lux, 16 bit), CONFIG2 = 0xBF (maximum IR
 * compensation), CONFIG3 = 0x00. Reading STATUS clears its flags, so the driver
 * keeps the flags it has seen until [Isl29125Full.pollInterrupt] /
 * [Isl29125Full.status] hands them out.
 *
 * @param connection I²C register connection bound to the device (0x44)
 * @throws IllegalStateException if DEVICE_ID does not identify an ISL29125
 */
open class Isl29125Minimal(protected val connection: RegisterConnection) {

    /** The three channel counts of one conversion (unsigned 16 bit). */
    data class Counts(val red: Int, val green: Int, val blue: Int)

    companion object {
        /** Fixed 7-bit I²C address. */
        const val DEFAULT_ADDRESS = 0x44

        /** Expected DEVICE_ID value. */
        const val DEVICE_ID = 0x7D

        /** Poll step while waiting for CONVENF, in ms (tests may set it to 0). */
        @Volatile
        @JvmStatic
        var pollStepMs: Int = 10

        const val REG_DEVICE_ID = 0x00
        const val REG_CONFIG1 = 0x01
        const val REG_CONFIG2 = 0x02
        const val REG_CONFIG3 = 0x03
        const val REG_LOW_TH = 0x04
        const val REG_HIGH_TH = 0x06
        const val REG_STATUS = 0x08
        const val REG_GREEN = 0x09
        const val REG_RED = 0x0B
        const val REG_BLUE = 0x0D
        const val RESET_VALUE = 0x46
        const val CFG1_SYNC = 0x20
        const val CFG1_BITS_12 = 0x10
        const val CFG1_RNG_10K = 0x08
        const val CFG1_MODE = 0x07
        const val CFG3_CONVEN = 0x10

        /** STATUS bit 2: power-down or brown-out occurred. */
        const val STATUS_BROWNOUT = 0x04

        /** STATUS bit 1: conversion completed. */
        const val STATUS_CONVERSION = 0x02

        /** STATUS bit 0: threshold window crossed. */
        const val STATUS_THRESHOLD = 0x01

        private const val WAIT_LIMIT_MS = 400

        internal fun word(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

        internal fun sleepMs(ms: Int) {
            if (ms <= 0) return
            try {
                Thread.sleep(ms.toLong())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    protected var pending: Int = 0

    init {
        val id = readReg(REG_DEVICE_ID)
        check(id == DEVICE_ID) { "ISL29125 not found (DEVICE_ID 0x%02X)".format(id) }
        writeReg(REG_STATUS, 0x00)
        writeReg(REG_CONFIG3, 0x00)
        writeReg(REG_CONFIG2, 0xBF)
        writeReg(REG_CONFIG1, 0x0D)
    }

    protected fun readReg(reg: Int): Int = connection.read(reg, 1)[0].toInt() and 0xFF

    protected fun writeReg(reg: Int, value: Int) {
        connection.write(reg, byteArrayOf(value.toByte()))
    }

    protected fun updateReg(reg: Int, setBits: Int, clearBits: Int) {
        val value = readReg(reg)
        writeReg(reg, (value and clearBits.inv()) or setBits)
    }

    /** Read STATUS once and keep its flag bits until handed out. */
    protected fun readStatus(): Int {
        val value = readReg(REG_STATUS)
        pending = pending or (value and (STATUS_BROWNOUT or STATUS_CONVERSION or STATUS_THRESHOLD))
        return value
    }

    protected fun readBlock(): Counts {
        val raw = connection.read(REG_GREEN, 6)
        return Counts(red = word(raw, 2), green = word(raw, 0), blue = word(raw, 4))
    }

    protected fun readChannel(reg: Int): Int = word(connection.read(reg, 2), 0)

    /**
     * Read red, green and blue from one conversion. Waits for a new result (at
     * most 400 ms), then block-reads 0x09–0x0E.
     *
     * @return the three unsigned 16-bit counts
     */
    fun read(): Counts {
        val step = if (pollStepMs > 0) pollStepMs else 1
        var waited = 0
        while (waited <= WAIT_LIMIT_MS) {
            if ((readStatus() and STATUS_CONVERSION) != 0) break
            sleepMs(pollStepMs)
            waited += step
        }
        return readBlock()
    }
}
