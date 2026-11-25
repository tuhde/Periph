package it.uhde.periph.chips.motor

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.OutputPin
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Trinamic TMC2130 stepper driver over SPI (Mode 3) — minimal interface.
 *
 * Brings the driver up in SPI mode (internal reference, StealthChop below
 * `TPWMTHRS = 500`, SpreadCycle above it, 16 microsteps with 256-step
 * interpolation, 0.5 A RMS run current) and moves the motor with STEP/DIR
 * pulses — the chip has no internal step generator. The 40-bit datagram
 * framing and the pipelined two-datagram read are handled here; the
 * connection only needs plain `write` / `writeRead`.
 *
 * Construction talks to the chip (identity check, configuration with
 * mirror-verified writes) and throws [IOException] if it does not answer.
 *
 * @param connection SPI connection configured for Mode 3 (CPOL=1, CPHA=1)
 * @param stepPin `STEP` output
 * @param dirPin `DIR` output
 * @param rSenseOhm external sense resistor in ohm (0.11 on common boards)
 * @param enPin optional `ENN` output (active low); driven high (driver off) until [enable]
 */
open class TMC2130Minimal(
    protected val connection: Connection,
    protected val stepPin: OutputPin,
    protected val dirPin: OutputPin,
    protected val rSenseOhm: Double = 0.11,
    protected val enPin: OutputPin? = null,
) {

    companion object {
        /** `DRV_STATUS.otpw`. */
        const val FAULT_OVERTEMP_WARNING = 0x01

        /** `DRV_STATUS.ot`. */
        const val FAULT_OVERTEMP = 0x02

        /** `s2ga` / `s2gb`. */
        const val FAULT_SHORT_GND = 0x04

        /** `ola` / `olb` (informative only). */
        const val FAULT_OPEN_LOAD = 0x10

        const val REG_GCONF = 0x00
        const val REG_GSTAT = 0x01
        const val REG_IOIN = 0x04
        const val REG_IHOLD_IRUN = 0x10
        const val REG_TPOWERDOWN = 0x11
        const val REG_TSTEP = 0x12
        const val REG_TPWMTHRS = 0x13
        const val REG_TCOOLTHRS = 0x14
        const val REG_THIGH = 0x15
        const val REG_XDIRECT = 0x2D
        const val REG_VDCMIN = 0x33
        const val REG_MSLUT0 = 0x60
        const val REG_MSLUTSEL = 0x68
        const val REG_MSLUTSTART = 0x69
        const val REG_MSCNT = 0x6A
        const val REG_MSCURACT = 0x6B
        const val REG_CHOPCONF = 0x6C
        const val REG_COOLCONF = 0x6D
        const val REG_DCCTRL = 0x6E
        const val REG_DRV_STATUS = 0x6F
        const val REG_PWMCONF = 0x70
        const val REG_PWM_SCALE = 0x71
        const val REG_LOST_STEPS = 0x73

        const val GCONF_I_SCALE_ANALOG = 1L shl 0
        const val GCONF_INTERNAL_RSENSE = 1L shl 1
        const val GCONF_EN_PWM_MODE = 1L shl 2
        const val GCONF_SHAFT = 1L shl 4
        const val GCONF_SMALL_HYSTERESIS = 1L shl 14
        const val GCONF_STOP_ENABLE = 1L shl 15
        const val GCONF_DIRECT_MODE = 1L shl 16

        private const val VERSION = 0x11
        private const val CHOPCONF_DEFAULT = 0x140100C3L
        private const val PWMCONF_DEFAULT = 0x000401C8L
    }

    protected var gconf = 0L
    protected var chopconf = 0L
    protected var pwmconf = 0L
    protected var coolconf = 0L
    protected var stepRes = 16
    protected var vsenseOn = false
    protected var autoVsense = true
    protected var rsenseInternal = false
    protected var toff = 3
    protected var runCs = 0
    protected var holdCs = 0
    protected var iholdDelay = 6

    init {
        initialise()
    }

    /** Write a 32-bit register; with [verify] also check the mirror of the write. */
    protected fun writeReg(reg: Int, value: Long, verify: Boolean = false) {
        val v = value and 0xFFFFFFFFL
        connection.write(
            byteArrayOf(
                (reg or 0x80).toByte(), (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte(),
            ),
        )
        when (reg) {
            REG_GCONF -> gconf = v
            REG_CHOPCONF -> chopconf = v
            REG_PWMCONF -> pwmconf = v
            REG_COOLCONF -> coolconf = v
        }
        if (verify && secondDatagram() != v) throw IOException(String.format("TMC2130: write mirror mismatch (reg 0x%02X)", reg))
    }

    /** A harmless GCONF read request whose reply answers the previous datagram. Never GSTAT. */
    private fun secondDatagram(): Long {
        val r = connection.writeRead(byteArrayOf(REG_GCONF.toByte()), 4)
        if (r.size != 4) throw IOException("TMC2130: short read")
        return ((r[0].toLong() and 0xFF) shl 24) or ((r[1].toLong() and 0xFF) shl 16) or
            ((r[2].toLong() and 0xFF) shl 8) or (r[3].toLong() and 0xFF)
    }

    /** Pipelined two-datagram read of a 32-bit register (unsigned in a [Long]). */
    protected fun readReg(reg: Int): Long {
        connection.writeRead(byteArrayOf((reg and 0x7F).toByte()), 4)
        return secondDatagram()
    }

    /** Read-modify-write a shadowed register: `(shadow & ~mask) | (value & mask)`. */
    protected fun modify(reg: Int, mask: Long, value: Long) {
        val current = when (reg) {
            REG_GCONF -> gconf
            REG_CHOPCONF -> chopconf
            REG_COOLCONF -> coolconf
            else -> pwmconf
        }
        writeReg(reg, (current and mask.inv()) or (value and mask))
    }

    private fun csFor(rmsA: Double, vs: Boolean): Int {
        val vfs = if (vs) 0.180 else 0.325
        val cs = Math.round(rmsA * sqrt(2.0) * (rSenseOhm + 0.020) * 32.0 / vfs) - 1
        return max(0L, min(31L, cs)).toInt()
    }

    /** Convert A RMS to the 0–31 scale; returns (cs, vsense), selecting vsense automatically unless fixed. */
    protected fun currentToCs(rmsA: Double, forced: Boolean? = null): Pair<Int, Boolean> {
        check(!rsenseInternal) { "set_current is unsupported with internal Rsense" }
        if (forced != null) return Pair(csFor(rmsA, forced), forced)
        if (!autoVsense) return Pair(csFor(rmsA, vsenseOn), vsenseOn)
        val cs = csFor(rmsA, false)
        if (cs < 16) {
            val high = csFor(rmsA, true)
            if (high <= 31) return Pair(high, true)
        }
        return Pair(cs, false)
    }

    protected fun writeIholdIrun(verify: Boolean = false) {
        writeReg(
            REG_IHOLD_IRUN,
            (holdCs.toLong() and 0x1F) or ((runCs.toLong() and 0x1F) shl 8) or ((iholdDelay.toLong() and 0xF) shl 16),
            verify,
        )
    }

    private fun initialise() {
        enPin?.set(true)
        Thread.sleep(10)
        val ioin = readReg(REG_IOIN)
        if (((ioin shr 24) and 0xFF).toInt() != VERSION || (ioin and (1L shl 6)) == 0L) {
            throw IOException(String.format("TMC2130 not found (IOIN=0x%08X)", ioin))
        }
        readReg(REG_GSTAT)
        val (cs, vs) = currentToCs(0.5)
        runCs = cs
        holdCs = cs / 3
        vsenseOn = vs
        writeReg(REG_CHOPCONF, CHOPCONF_DEFAULT or (if (vs) 1L shl 17 else 0L), true)
        writeIholdIrun(true)
        writeReg(REG_TPOWERDOWN, 10, true)
        writeReg(REG_GCONF, GCONF_EN_PWM_MODE, true)
        writeReg(REG_TPWMTHRS, 500, true)
        writeReg(REG_PWMCONF, PWMCONF_DEFAULT, true)
        if (readReg(REG_GCONF) != gconf || readReg(REG_CHOPCONF) != chopconf) {
            throw IOException("TMC2130: GCONF/CHOPCONF readback mismatch")
        }
    }

    /** Switch the power stage on (`ENN` low, or `TOFF` restored when no pin is wired) and wait 100 ms. */
    fun enable() {
        if (enPin != null) enPin.set(false) else writeReg(REG_CHOPCONF, (chopconf and 0xFL.inv()) or toff.toLong())
        Thread.sleep(100)
    }

    /** Switch the power stage off; the motor freewheels. */
    fun disable() {
        if (enPin != null) {
            enPin.set(true)
            return
        }
        if ((chopconf and 0xF) != 0L) toff = (chopconf and 0xF).toInt()
        writeReg(REG_CHOPCONF, chopconf and 0xFL.inv())
    }

    /**
     * Set the run current; the hold current becomes one third of it. The sense range
     * (`CHOPCONF.vsense`) is selected automatically unless `setVsense` was called.
     *
     * @param rmsA run current in A RMS (at most 1.4)
     * @throws IllegalArgumentException if [rmsA] is above 1.4
     * @throws IllegalStateException if the internal sense resistors are selected
     */
    fun setCurrent(rmsA: Double) {
        require(rmsA <= 1.4) { "rmsA must be at most 1.4 A" }
        val (cs, vs) = currentToCs(rmsA)
        runCs = cs
        holdCs = cs / 3
        if (vs != vsenseOn) {
            vsenseOn = vs
            modify(REG_CHOPCONF, 1L shl 17, if (vs) 1L shl 17 else 0L)
        }
        writeIholdIrun()
    }

    /**
     * Move a number of full steps (blocking, software-timed STEP pulses).
     *
     * @param fullSteps full steps to move; the sign sets the direction
     * @param fullStepsPerS speed in full steps per second (> 0)
     * @throws IllegalArgumentException if [fullStepsPerS] is not positive
     */
    fun move(fullSteps: Int, fullStepsPerS: Double) {
        require(fullStepsPerS > 0) { "fullStepsPerS must be > 0" }
        val pulses = abs(fullSteps).toLong() * stepRes
        val halfNs = (1e9 / (2.0 * fullStepsPerS * stepRes)).toLong()
        dirPin.set(fullSteps >= 0)
        for (i in 0 until pulses) {
            stepPin.set(true)
            sleepNanos(halfNs)
            stepPin.set(false)
            sleepNanos(halfNs)
        }
    }

    protected fun sleepNanos(nanos: Long) {
        Thread.sleep(nanos / 1_000_000L, (nanos % 1_000_000L).toInt())
    }

    /** True when `DRV_STATUS.stst` is set. */
    fun isStandstill(): Boolean = (readReg(REG_DRV_STATUS) and (1L shl 31)) != 0L

    /** Bitmask of `FAULT_*` constants, 0 when no fault is present. */
    fun readFault(): Int {
        val s = readReg(REG_DRV_STATUS)
        var f = 0
        if ((s and (1L shl 26)) != 0L) f = f or FAULT_OVERTEMP_WARNING
        if ((s and (1L shl 25)) != 0L) f = f or FAULT_OVERTEMP
        if ((s and (3L shl 27)) != 0L) f = f or FAULT_SHORT_GND
        if ((s and (3L shl 29)) != 0L) f = f or FAULT_OPEN_LOAD
        return f
    }
}
