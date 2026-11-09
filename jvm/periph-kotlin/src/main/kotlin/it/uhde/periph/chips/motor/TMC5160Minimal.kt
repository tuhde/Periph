package it.uhde.periph.chips.motor

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.OutputPin
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Trinamic TMC5160 stepper controller/driver over SPI (Mode 3) or single-wire
 * UART — minimal interface.
 *
 * Brings the chip up with the internal SixPoint ramp generator in positioning
 * mode (256 microsteps, StealthChop below `TPWMTHRS = 500`, 0.5 A RMS run
 * current, 200 full steps/s, 400 full steps/s²) and moves the motor with
 * register writes — no STEP pulses. The transport follows the constructor: the
 * SPI constructor builds pipelined 40-bit datagrams, the UART constructor
 * builds CRC-protected datagrams. Positions are in full steps, velocities in
 * full steps per second, accelerations in full steps per second squared.
 *
 * Construction talks to the chip and throws [IOException] if it does not answer.
 *
 * @param connection SPI connection configured for Mode 3, or UART connection
 * @param rSenseOhm external sense resistor in ohm (0.075 on common boards)
 * @param enPin optional `DRV_ENN` output (active low); driven high (driver off) until [enable]
 */
open class TMC5160Minimal protected constructor(
    protected val connection: Connection,
    protected val rSenseOhm: Double,
    protected val enPin: OutputPin?,
    protected val uart: Boolean,
    protected var address: Int,
    protected val echo: Boolean,
    private val multiNode: Boolean,
    protected val fclkHz: Long,
) {

    /** SPI constructor: `connection` must be configured for Mode 3. */
    constructor(connection: Connection, rSenseOhm: Double = 0.075, enPin: OutputPin? = null) :
        this(connection, rSenseOhm, enPin, false, 0, false, false, 12_000_000L)

    /** UART constructor: `address` 0–253, `echo` true when TX and RX share the wire, `multiNode` writes `SENDDELAY = 2`. */
    constructor(
        connection: Connection, rSenseOhm: Double, address: Int, enPin: OutputPin?, echo: Boolean, multiNode: Boolean,
    ) : this(connection, rSenseOhm, enPin, true, address, echo, multiNode, 12_000_000L)

    companion object {
        /** `DRV_STATUS.otpw`. */
        const val FAULT_OVERTEMP_WARNING = 0x01
        /** `DRV_STATUS.ot`. */
        const val FAULT_OVERTEMP = 0x02
        /** `s2ga` / `s2gb`. */
        const val FAULT_SHORT_GND = 0x04
        /** `s2vsa` / `s2vsb`. */
        const val FAULT_SHORT_SUPPLY = 0x08
        /** `ola` / `olb` (informative only). */
        const val FAULT_OPEN_LOAD = 0x10

        internal const val REG_GCONF = 0x00
        internal const val REG_GSTAT = 0x01
        internal const val REG_IFCNT = 0x02
        internal const val REG_SLAVECONF = 0x03
        internal const val REG_IOIN = 0x04
        internal const val REG_OUTPUT = 0x04
        internal const val REG_X_COMPARE = 0x05
        internal const val REG_SHORT_CONF = 0x09
        internal const val REG_DRV_CONF = 0x0A
        internal const val REG_GLOBALSCALER = 0x0B
        internal const val REG_OFFSET_READ = 0x0C
        internal const val REG_IHOLD_IRUN = 0x10
        internal const val REG_TPOWERDOWN = 0x11
        internal const val REG_TSTEP = 0x12
        internal const val REG_TPWMTHRS = 0x13
        internal const val REG_TCOOLTHRS = 0x14
        internal const val REG_THIGH = 0x15
        internal const val REG_RAMPMODE = 0x20
        internal const val REG_XACTUAL = 0x21
        internal const val REG_VACTUAL = 0x22
        internal const val REG_VSTART = 0x23
        internal const val REG_A1 = 0x24
        internal const val REG_V1 = 0x25
        internal const val REG_AMAX = 0x26
        internal const val REG_VMAX = 0x27
        internal const val REG_DMAX = 0x28
        internal const val REG_D1 = 0x2A
        internal const val REG_VSTOP = 0x2B
        internal const val REG_TZEROWAIT = 0x2C
        internal const val REG_XTARGET = 0x2D
        internal const val REG_VDCMIN = 0x33
        internal const val REG_SW_MODE = 0x34
        internal const val REG_RAMP_STAT = 0x35
        internal const val REG_XLATCH = 0x36
        internal const val REG_ENCMODE = 0x38
        internal const val REG_X_ENC = 0x39
        internal const val REG_ENC_CONST = 0x3A
        internal const val REG_ENC_STATUS = 0x3B
        internal const val REG_ENC_LATCH = 0x3C
        internal const val REG_ENC_DEVIATION = 0x3D
        internal const val REG_MSLUT0 = 0x60
        internal const val REG_MSLUTSEL = 0x68
        internal const val REG_MSLUTSTART = 0x69
        internal const val REG_MSCNT = 0x6A
        internal const val REG_MSCURACT = 0x6B
        internal const val REG_CHOPCONF = 0x6C
        internal const val REG_COOLCONF = 0x6D
        internal const val REG_DCCTRL = 0x6E
        internal const val REG_DRV_STATUS = 0x6F
        internal const val REG_PWMCONF = 0x70
        internal const val REG_PWM_SCALE = 0x71
        internal const val REG_PWM_AUTO = 0x72

        internal const val GCONF_EN_PWM_MODE = 1L shl 2
        internal const val GCONF_SHAFT = 1L shl 4
        internal const val GCONF_SMALL_HYSTERESIS = 1L shl 14
        internal const val GCONF_STOP_ENABLE = 1L shl 15
        internal const val GCONF_DIRECT_MODE = 1L shl 16
        internal const val SW_SG_STOP = 1L shl 10
        internal const val SW_SOFTSTOP = 1L shl 11

        private const val VERSION = 0x30
        private const val V_FS = 0.325
        private const val CHOPCONF_DEFAULT = 0x000100C3L
        internal const val PWMCONF_RESET = 0xC40C001EL

        /** CRC-8 (polynomial 0x07, init 0, LSB first) over the first [len] bytes. */
        @JvmStatic
        fun crc8(data: ByteArray, len: Int): Int {
            var crc = 0
            for (i in 0 until len) {
                var b = data[i].toInt() and 0xFF
                repeat(8) {
                    crc = if (((crc shr 7) xor b) and 1 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
                    b = b shr 1
                }
            }
            return crc
        }

        internal fun bounded(v: Long, limit: Long): Long {
            require(v in 0..limit) { "value out of range" }
            return v
        }
    }

    internal val shadow = LongArray(128)
    internal val seen = BooleanArray(128)
    protected var usc = 256
    protected var toff = 3
    protected var rampModeCur = 0
    protected var speedCur = 200.0
    protected var speedReg = 0L
    protected var accelCur = 400.0
    protected var irun = 0
    protected var ihold = 0
    protected var iholdDelay = 6
    protected var sendDelayVal = 0
    private var lastDist = 0.0
    private var lastSpeed = 200.0
    private var lastAccel = 400.0
    private var writes = 0

    init {
        require(address in 0..253) { "address must be 0-253" }
        initialise()
    }

    private fun uartWrite(reg: Int, value: Long) {
        val dg = byteArrayOf(
            0x05, address.toByte(), (reg or 0x80).toByte(), (value shr 24).toByte(), (value shr 16).toByte(),
            (value shr 8).toByte(), value.toByte(), 0,
        )
        dg[7] = crc8(dg, 7).toByte()
        connection.write(dg)
        if (echo) {
            val echoed = connection.read(8)
            if (!echoed.contentEquals(dg)) throw IOException("TMC5160: write echo mismatch")
        }
    }

    private fun uartRead(reg: Int): Long {
        val req = byteArrayOf(0x05, address.toByte(), (reg and 0x7F).toByte(), 0)
        req[3] = crc8(req, 3).toByte()
        val reply: ByteArray
        if (echo) {
            val raw = connection.writeRead(req, 12)
            if (raw.size != 12) throw IOException("TMC5160: short read")
            if (!raw.copyOfRange(0, 4).contentEquals(req)) throw IOException("TMC5160: read echo mismatch")
            reply = raw.copyOfRange(4, 12)
        } else {
            reply = connection.writeRead(req, 8)
            if (reply.size != 8) throw IOException("TMC5160: short read")
        }
        if ((reply[0].toInt() and 0xFF) != 0x05 || (reply[1].toInt() and 0xFF) != 0xFF ||
            (reply[2].toInt() and 0xFF) != (reg and 0x7F)
        ) throw IOException("TMC5160: malformed reply")
        if (crc8(reply, 7) != (reply[7].toInt() and 0xFF)) throw IOException("TMC5160: reply CRC error")
        return ((reply[3].toLong() and 0xFF) shl 24) or ((reply[4].toLong() and 0xFF) shl 16) or
            ((reply[5].toLong() and 0xFF) shl 8) or (reply[6].toLong() and 0xFF)
    }

    private fun spiSecond(): Long {
        val r = connection.writeRead(byteArrayOf(REG_GCONF.toByte()), 4)
        if (r.size != 4) throw IOException("TMC5160: short read")
        return ((r[0].toLong() and 0xFF) shl 24) or ((r[1].toLong() and 0xFF) shl 16) or
            ((r[2].toLong() and 0xFF) shl 8) or (r[3].toLong() and 0xFF)
    }

    protected fun pause(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Write a 32-bit register (UART: retried once after the idle time the chip needs following an error). */
    protected fun writeReg(reg: Int, value: Long) {
        val v = value and 0xFFFFFFFFL
        if (uart) {
            try {
                uartWrite(reg, v)
            } catch (e: IOException) {
                pause(2)
                uartWrite(reg, v)
            }
        } else {
            connection.write(
                byteArrayOf((reg or 0x80).toByte(), (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()),
            )
        }
        writes++
        shadow[reg] = v
        seen[reg] = true
    }

    /** Read a 32-bit register (unsigned in a [Long]). */
    protected fun readReg(reg: Int): Long {
        if (uart) {
            return try {
                uartRead(reg)
            } catch (e: IOException) {
                pause(2)
                uartRead(reg)
            }
        }
        connection.writeRead(byteArrayOf((reg and 0x7F).toByte()), 4)
        return spiSecond()
    }

    /** Write and check the acknowledge: `IFCNT` + 1 on UART, a follow-up datagram on SPI. */
    protected fun writeVerified(reg: Int, value: Long) {
        if (uart) {
            val before = (readReg(REG_IFCNT) and 0xFF).toInt()
            writeReg(reg, value)
            val after = (readReg(REG_IFCNT) and 0xFF).toInt()
            if (after != ((before + 1) and 0xFF)) throw IOException("TMC5160: write not acknowledged")
        } else {
            writeReg(reg, value)
            if (spiSecond() != (value and 0xFFFFFFFFL)) throw IOException("TMC5160: write mirror mismatch")
        }
    }

    /** Read-modify-write a shadowed register: `(shadow & ~mask) | (value & mask)`. */
    protected fun modify(reg: Int, mask: Long, value: Long) {
        writeReg(reg, (shadow[reg] and mask.inv()) or (value and mask))
    }

    /** Seed the shadow of a write-only register with its reset value on first use. */
    protected fun firstUse(reg: Int, reset: Long) {
        if (!seen[reg]) {
            shadow[reg] = reset
            seen[reg] = true
        }
    }

    protected fun vReg(v: Double): Long = (v * usc * 16777216.0 / fclkHz).roundToLong()

    protected fun aReg(a: Double): Long = (a * usc * 2199023255552.0 / (fclkHz.toDouble() * fclkHz)).roundToLong()

    /** Maximum RMS current for the sense resistor at full `GLOBALSCALER`. */
    protected fun iMax(): Double = V_FS / (rSenseOhm * sqrt(2.0))

    private fun applyCurrent(rmsA: Double) {
        val r = rmsA / iMax()
        require(r <= 1.0 && r >= 1.0 / 64.0) { "current out of range" }
        val newIrun: Int
        val scaler: Long
        if (r >= 0.5) {
            newIrun = 31
            scaler = (256.0 * r).roundToLong()
        } else {
            newIrun = max(0L, min(31L, (64.0 * r).roundToLong() - 1)).toInt()
            scaler = 128
        }
        writeReg(REG_GLOBALSCALER, if (scaler >= 256) 0 else scaler)
        irun = newIrun
        ihold = newIrun / 3
        iholdDelay = 6
        writeIholdIrun()
    }

    protected fun writeIholdIrun() {
        writeReg(
            REG_IHOLD_IRUN,
            (ihold.toLong() and 0x1F) or ((irun.toLong() and 0x1F) shl 8) or ((iholdDelay.toLong() and 0xF) shl 16),
        )
    }

    private fun initialise() {
        enPin?.set(true)
        pause(10)
        val base = if (uart) (readReg(REG_IFCNT) and 0xFF).toInt() else 0
        val ioin = readReg(REG_IOIN)
        if (((ioin shr 24) and 0xFF).toInt() != VERSION) throw IOException("TMC5160 not found (IOIN=0x%08X)".format(ioin))
        if ((ioin and (1L shl 6)) != 0L) throw IOException("TMC5160 is strapped for STEP/DIR (SD_MODE = 1)")
        writes = 0
        if (uart && multiNode) {
            sendDelayVal = 2
            writeVerified(REG_SLAVECONF, 2L shl 8)
            writes = 0
        }
        readReg(REG_GSTAT)
        writeReg(REG_GSTAT, 0x07)
        writeReg(REG_GLOBALSCALER, 0)
        applyCurrent(min(0.5, iMax()))
        writeReg(REG_TPOWERDOWN, 10)
        writeReg(REG_CHOPCONF, CHOPCONF_DEFAULT)
        writeReg(REG_GCONF, GCONF_EN_PWM_MODE)
        writeReg(REG_TPWMTHRS, 500)
        writeReg(REG_RAMPMODE, 0)
        writeReg(REG_VSTART, 0)
        writeReg(REG_V1, 0)
        writeReg(REG_VSTOP, 10)
        writeReg(REG_TZEROWAIT, 0)
        setSpeed(200.0)
        setAcceleration(400.0)
        if (uart) {
            val after = (readReg(REG_IFCNT) and 0xFF).toInt()
            if (after != ((base + writes) and 0xFF)) throw IOException("TMC5160: IFCNT mismatch (writes not acknowledged)")
        } else if (readReg(REG_GCONF) != shadow[REG_GCONF] || readReg(REG_CHOPCONF) != shadow[REG_CHOPCONF]) {
            throw IOException("TMC5160: SPI write verification failed")
        }
        rampModeCur = 0
    }

    /** Switch the power stage on (`DRV_ENN` low, or `TOFF` restored) and wait 150 ms for StealthChop. */
    fun enable() {
        if (enPin != null) enPin.set(false) else writeReg(REG_CHOPCONF, (shadow[REG_CHOPCONF] and 0xFL.inv()) or toff.toLong())
        pause(150)
    }

    /** Switch the power stage off; the motor outputs float. */
    fun disable() {
        if (enPin != null) {
            enPin.set(true)
            return
        }
        val chop = shadow[REG_CHOPCONF]
        if ((chop and 0xF) != 0L) toff = (chop and 0xF).toInt()
        writeReg(REG_CHOPCONF, chop and 0xFL.inv())
    }

    /**
     * Set the run current in A RMS (hold becomes one third) by programming `GLOBALSCALER` and `IHOLD_IRUN`.
     *
     * @throws IllegalArgumentException above the sense-resistor maximum or below 1/64 of it
     */
    fun setCurrent(rmsA: Double) = applyCurrent(rmsA)

    /** Set `VMAX` in full steps per second (applied immediately in positioning mode). */
    fun setSpeed(fullStepsPerS: Double) {
        require(fullStepsPerS > 0) { "speed must be positive" }
        val reg = bounded(vReg(fullStepsPerS), (1L shl 23) - 512)
        speedCur = fullStepsPerS
        speedReg = reg
        if (rampModeCur == 0) writeReg(REG_VMAX, reg)
    }

    /** Set a single trapezoid: `A1 = AMAX = DMAX = D1` (with `V1 = 0`), in full steps per second squared. */
    fun setAcceleration(fullStepsPerS2: Double) {
        require(fullStepsPerS2 > 0) { "acceleration must be positive" }
        val reg = bounded(aReg(fullStepsPerS2), 0xFFFF)
        require(reg >= 1) { "acceleration too small" }
        accelCur = fullStepsPerS2
        for (r in intArrayOf(REG_A1, REG_AMAX, REG_DMAX, REG_D1)) writeReg(r, reg)
    }

    /** Start a positioning move to an absolute position in full steps (non-blocking). */
    fun moveTo(position: Double) {
        val target = (position * usc).roundToLong()
        lastDist = abs(position - position())
        lastSpeed = speedCur
        lastAccel = accelCur
        writeReg(REG_VMAX, speedReg)
        writeReg(REG_RAMPMODE, 0)
        rampModeCur = 0
        writeReg(REG_XTARGET, target)
    }

    /** Blocking relative move; [fullStepsPerS] `<= 0` keeps the configured speed. */
    fun moveSteps(fullSteps: Double, fullStepsPerS: Double = 0.0) {
        val saved = speedCur
        if (fullStepsPerS > 0) setSpeed(fullStepsPerS)
        try {
            moveTo(position() + fullSteps)
            waitUntilReached(0.0)
        } finally {
            if (fullStepsPerS > 0) setSpeed(saved)
        }
    }

    /** Velocity mode: ramp to a velocity (sign = direction); 0 ramps to standstill. */
    fun run(fullStepsPerS: Double) {
        val reg = bounded(vReg(abs(fullStepsPerS)), (1L shl 23) - 512)
        val mode = if (fullStepsPerS < 0) 2 else 1
        writeReg(REG_RAMPMODE, mode.toLong())
        rampModeCur = mode
        writeReg(REG_VMAX, reg)
    }

    /** Controlled stop: velocity mode with `VMAX = 0`. */
    fun stop() {
        writeReg(REG_RAMPMODE, 1)
        rampModeCur = 1
        writeReg(REG_VMAX, 0)
    }

    /** `XACTUAL` in full steps. */
    fun position(): Double = readReg(REG_XACTUAL).toInt() / usc.toDouble()

    /** Define the current position (no motion): writes `XACTUAL` and `XTARGET`. */
    fun setPosition(position: Double) {
        val v = (position * usc).roundToLong() and 0xFFFFFFFFL
        writeReg(REG_XACTUAL, v)
        writeReg(REG_XTARGET, v)
    }

    /** `VACTUAL` in full steps per second, signed. */
    fun velocity(): Double {
        var raw = readReg(REG_VACTUAL) and 0xFFFFFFL
        if ((raw and 0x800000L) != 0L) raw -= 0x1000000L
        return raw * (fclkHz / 2.0 / 8388608.0) / usc
    }

    /** `RAMP_STAT.position_reached`. */
    fun isPositionReached(): Boolean = (readReg(REG_RAMP_STAT) and (1L shl 9)) != 0L

    /**
     * Poll `RAMP_STAT` every 5 ms until `position_reached` (positioning) or `vzero` (velocity mode).
     *
     * @param timeoutS timeout in seconds; `<= 0` estimates it from the last move
     * @throws IOException on bus error or timeout
     */
    fun waitUntilReached(timeoutS: Double = 0.0) {
        val timeout = if (timeoutS <= 0) 2 * lastDist / lastSpeed + 2 * lastSpeed / lastAccel + 1.0 else timeoutS
        val bit = if (rampModeCur == 0) 1L shl 9 else 1L shl 10
        pause(1)
        val deadline = System.nanoTime() + (timeout * 1e9).toLong()
        while (true) {
            if ((readReg(REG_RAMP_STAT) and bit) != 0L) return
            if (System.nanoTime() > deadline) throw IOException("TMC5160: timed out waiting for the ramp generator")
            pause(5)
        }
    }

    /** True when `DRV_STATUS.stst` is set. */
    fun isStandstill(): Boolean = (readReg(REG_DRV_STATUS) shr 31) == 1L

    /** Bitmask of `FAULT_*` constants, 0 when none. */
    fun readFault(): Int {
        val s = readReg(REG_DRV_STATUS)
        var f = 0
        if ((s and (1L shl 26)) != 0L) f = f or FAULT_OVERTEMP_WARNING
        if ((s and (1L shl 25)) != 0L) f = f or FAULT_OVERTEMP
        if ((s and (3L shl 27)) != 0L) f = f or FAULT_SHORT_GND
        if ((s and (3L shl 12)) != 0L) f = f or FAULT_SHORT_SUPPLY
        if ((s and (3L shl 29)) != 0L) f = f or FAULT_OPEN_LOAD
        return f
    }

}
