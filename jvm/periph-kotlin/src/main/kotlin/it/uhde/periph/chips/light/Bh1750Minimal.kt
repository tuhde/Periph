package it.uhde.periph.chips.light

import it.uhde.periph.connection.Connection

/**
 * BH1750FVI — ambient light sensor (ROHM), minimal driver.
 *
 * Each [read] starts one one-time H-resolution measurement (opcode 0x20, 1 lx steps), waits the
 * datasheet maximum of 180 ms, reads the 16-bit result and returns the illuminance in lux
 * (raw / 1.2). The chip powers down by itself after the measurement. I²C address 0x23 or 0x5C
 * (`ADDR` pin). The chip is command driven (no register pointer), so a plain [Connection] is used.
 *
 * ## Configuration defaults
 * - Nothing is written at construction; [init] powers the chip on
 * - One-time H-resolution mode, measurement-time register 69 (default)
 */
open class Bh1750Minimal(protected val connection: Connection) {
    companion object {
        /** H-resolution mode, 1 lx steps. */
        const val MODE_H = 0
        /** H-resolution mode 2, 0.5 lx steps. */
        const val MODE_H2 = 1
        /** L-resolution mode, 4 lx steps. */
        const val MODE_L = 2

        const val OP_POWER_DOWN = 0x00
        const val OP_POWER_ON   = 0x01
        const val OP_RESET      = 0x07
        const val OP_MT_HIGH    = 0x40
        const val OP_MT_LOW     = 0x60
        const val DEFAULT_MTREG = 69

        val OP_CONTINUOUS = intArrayOf(0x10, 0x11, 0x13)
        val OP_ONE_TIME = intArrayOf(0x20, 0x21, 0x23)
        /** Maximum measurement time in milliseconds at MTreg = 69, indexed by mode. */
        val T_MAX_MS = doubleArrayOf(180.0, 180.0, 24.0)
    }

    protected var mtreg = DEFAULT_MTREG
    protected var mode = MODE_H

    protected fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    protected fun command(code: Int) = connection.write(byteArrayOf(code.toByte()))

    /** Power the chip on (opcode 0x01). */
    fun init() {
        command(OP_POWER_ON)
    }

    protected fun waitMs(mode: Int): Long = Math.ceil(T_MAX_MS[mode] * mtreg / DEFAULT_MTREG).toLong()

    protected fun toLux(raw: Int, mode: Int): Double {
        val lux = raw / 1.2 * (DEFAULT_MTREG.toDouble() / mtreg)
        return if (mode == MODE_H2) lux / 2.0 else lux
    }

    protected fun readWord(): Int {
        val data = connection.read(2)
        return ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
    }

    protected fun measureRaw(mode: Int): Int {
        command(OP_ONE_TIME[mode])
        sleepMs(waitMs(mode))
        return readWord()
    }

    /** Measure the illuminance once; lux (one-time measurement in the current mode, H-resolution by default). */
    open fun read(): Double = toLux(measureRaw(mode), mode)
}
