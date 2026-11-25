package it.uhde.periph.chips.motor

import it.uhde.periph.connection.AvailableConnection
import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.OutputPin
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Trinamic TMC2209 stepper driver over single-wire UART —
 * minimal interface.
 *
 * Brings the driver up in UART mode (`pdn_disable`, microstep resolution from
 * `CHOPCONF.MRES`), StealthChop, 16 microsteps with 256-step interpolation and
 * 0.5 A RMS run current, then lets the internal step generator (`VACTUAL`)
 * spin the motor — no STEP pulses required. Datagram framing (sync byte, CRC8,
 * host echo) is handled here; the connection only needs plain `write` /
 * `read` / `writeRead`.
 *
 * Construction talks to the chip (identity check, configuration, `IFCNT`
 * acknowledgement) and throws [IOException] if it does not answer.
 *
 * @param connection UART connection to the chip's `PDN_UART` pin
 * @param rSenseOhm external sense resistor in ohm (0.11 on common boards)
 * @param address UART node address 0–3, as set by the `MS1_AD0` / `MS2_AD1` pins
 * @param enPin optional `ENN` output (active low); driven high (driver off) until [enable]
 * @param echo true when TX and RX share `PDN_UART`
 */
open class TMC2209Minimal protected constructor(
    protected val connection: Connection,
    protected val rSenseOhm: Double,
    protected val address: Int,
    protected val enPin: OutputPin?,
    protected val echo: Boolean,
    protected val fclkHz: Long,
    private val multiNode: Boolean,
) {

    constructor(
        connection: Connection,
        rSenseOhm: Double = 0.11,
        address: Int = 0,
        enPin: OutputPin? = null,
        echo: Boolean = true,
    ) : this(connection, rSenseOhm, address, enPin, echo, 12_000_000L, false)

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

        const val REG_GCONF = 0x00
        const val REG_GSTAT = 0x01
        const val REG_IFCNT = 0x02
        const val REG_NODECONF = 0x03
        const val REG_OTP_READ = 0x05
        const val REG_IOIN = 0x06
        const val REG_IHOLD_IRUN = 0x10
        const val REG_TPOWERDOWN = 0x11
        const val REG_TSTEP = 0x12
        const val REG_TPWMTHRS = 0x13
        const val REG_TCOOLTHRS = 0x14
        const val REG_VACTUAL = 0x22
        const val REG_SGTHRS = 0x40
        const val REG_SG_RESULT = 0x41
        const val REG_COOLCONF = 0x42
        const val REG_MSCNT = 0x6A
        const val REG_MSCURACT = 0x6B
        const val REG_CHOPCONF = 0x6C
        const val REG_DRV_STATUS = 0x6F
        const val REG_PWMCONF = 0x70
        const val REG_PWM_SCALE = 0x71
        const val REG_PWM_AUTO = 0x72

        const val GCONF_I_SCALE_ANALOG = 1L shl 0
        const val GCONF_INTERNAL_RSENSE = 1L shl 1
        const val GCONF_EN_SPREADCYCLE = 1L shl 2
        const val GCONF_SHAFT = 1L shl 3
        const val GCONF_INDEX_OTPW = 1L shl 4
        const val GCONF_INDEX_STEP = 1L shl 5
        const val GCONF_PDN_DISABLE = 1L shl 6
        const val GCONF_MSTEP_REG_SELECT = 1L shl 7
        const val GCONF_MULTISTEP_FILT = 1L shl 8

        private const val VERSION = 0x21
        private const val PWMCONF_DEFAULT = 0xC10D0024L

        /** CRC-8 (polynomial 0x07, init 0, LSB first) over the first [len] bytes. */
        fun crc8(data: ByteArray, len: Int): Int {
            var crc = 0
            for (i in 0 until len) {
                var b = data[i].toInt() and 0xFF
                repeat(8) {
                    crc = if ((((crc shr 7) xor b) and 1) != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
                    b = b shr 1
                }
            }
            return crc
        }
    }

    protected var gconf = 0L
    protected var chopconf = 0L
    protected var pwmconf = 0L
    protected var stepRes = 16
    protected var vsenseOn = false
    protected var rsenseInternal = false
    protected var toff = 3
    protected var runCs = 0
    protected var holdCs = 0
    protected var iholdDelay = 6

    init {
        require(address in 0..3) { "address must be 0-3" }
        initialise()
    }

    private fun recover() {
        val a = connection as? AvailableConnection
        if (a != null) {
            while (a.available() > 0) connection.read(min(a.available(), 64))
        }
        Thread.sleep(2)
    }

    private fun writeDatagram(reg: Int, value: Long) {
        val dg = byteArrayOf(
            0x05, address.toByte(), (reg or 0x80).toByte(), (value shr 24).toByte(), (value shr 16).toByte(),
            (value shr 8).toByte(), value.toByte(), 0,
        )
        dg[7] = crc8(dg, 7).toByte()
        connection.write(dg)
        if (echo) {
            val echoed = connection.read(8)
            if (!echoed.contentEquals(dg)) throw IOException("TMC2209: write echo mismatch")
        }
    }

    private fun readDatagram(reg: Int): Long {
        val req = byteArrayOf(0x05, address.toByte(), (reg and 0x7F).toByte(), 0)
        req[3] = crc8(req, 3).toByte()
        val reply: ByteArray
        if (echo) {
            val raw = connection.writeRead(req, 12)
            if (raw.size != 12) throw IOException("TMC2209: short read")
            if (!raw.copyOfRange(0, 4).contentEquals(req)) throw IOException("TMC2209: read echo mismatch")
            reply = raw.copyOfRange(4, 12)
        } else {
            reply = connection.writeRead(req, 8)
            if (reply.size != 8) throw IOException("TMC2209: short read")
        }
        if ((reply[0].toInt() and 0xFF) != 0x05 || (reply[1].toInt() and 0xFF) != 0xFF ||
            (reply[2].toInt() and 0xFF) != (reg and 0x7F)
        ) throw IOException("TMC2209: malformed reply")
        if (crc8(reply, 7) != (reply[7].toInt() and 0xFF)) throw IOException("TMC2209: reply CRC error")
        return ((reply[3].toLong() and 0xFF) shl 24) or ((reply[4].toLong() and 0xFF) shl 16) or
            ((reply[5].toLong() and 0xFF) shl 8) or (reply[6].toLong() and 0xFF)
    }

    /** Write a 32-bit register, retrying once after the idle time the chip needs following an error. */
    protected fun writeReg(reg: Int, value: Long) {
        val v = value and 0xFFFFFFFFL
        try {
            writeDatagram(reg, v)
        } catch (e: IOException) {
            recover()
            writeDatagram(reg, v)
        }
        when (reg) {
            REG_GCONF -> gconf = v
            REG_CHOPCONF -> chopconf = v
            REG_PWMCONF -> pwmconf = v
        }
    }

    /** Read a 32-bit register (unsigned in a [Long]), retrying once after an error. */
    protected fun readReg(reg: Int): Long =
        try {
            readDatagram(reg)
        } catch (e: IOException) {
            recover()
            readDatagram(reg)
        }

    /** Read-modify-write a shadowed register: `(shadow & ~mask) | (value & mask)`. */
    protected fun modify(reg: Int, mask: Long, value: Long) {
        val current = when (reg) {
            REG_GCONF -> gconf
            REG_CHOPCONF -> chopconf
            else -> pwmconf
        }
        writeReg(reg, (current and mask.inv()) or (value and mask))
    }

    /** Convert A RMS to the 0–31 current scale for the current sense range. */
    protected fun currentToCs(rmsA: Double): Int {
        check(!rsenseInternal) { "set_current is unsupported with internal Rsense" }
        val vfs = if (vsenseOn) 0.180 else 0.325
        val cs = Math.round(rmsA * sqrt(2.0) * (rSenseOhm + 0.020) * 32.0 / vfs) - 1
        return max(0L, min(31L, cs)).toInt()
    }

    protected fun writeIholdIrun() {
        writeReg(
            REG_IHOLD_IRUN,
            (holdCs.toLong() and 0x1F) or ((runCs.toLong() and 0x1F) shl 8) or ((iholdDelay.toLong() and 0xF) shl 16),
        )
    }

    private fun initialise() {
        enPin?.set(true)
        Thread.sleep(10)
        val baseline = (readReg(REG_IFCNT) and 0xFF).toInt()
        val ioin = readReg(REG_IOIN)
        val version = ((ioin shr 24) and 0xFF).toInt()
        if (version != VERSION) {
            throw IOException(String.format("TMC2209 not found (IOIN=0x%08X)", ioin))
        }
        var writes = 6
        if (multiNode) {
            writeReg(REG_NODECONF, 2L shl 8)
            writes = 7
        }
        writeReg(REG_GCONF, GCONF_I_SCALE_ANALOG or GCONF_PDN_DISABLE or GCONF_MSTEP_REG_SELECT or GCONF_MULTISTEP_FILT)
        writeReg(REG_GSTAT, 0x07)
        runCs = currentToCs(0.5)
        holdCs = currentToCs(0.5 / 3.0)
        writeIholdIrun()
        writeReg(REG_TPOWERDOWN, 20)
        var chop = readReg(REG_CHOPCONF)
        chop = chop and (0xFL or (3L shl 15) or (7L shl 4) or (0xFL shl 7) or (0xFL shl 24) or (1L shl 17)).inv()
        chop = chop or 3L or (2L shl 15) or (5L shl 4) or (4L shl 24) or (1L shl 28)
        writeReg(REG_CHOPCONF, chop)
        writeReg(REG_PWMCONF, PWMCONF_DEFAULT)
        val ifcnt = (readReg(REG_IFCNT) and 0xFF).toInt()
        if (ifcnt != ((baseline + writes) and 0xFF)) throw IOException("TMC2209: IFCNT mismatch (writes not acknowledged)")
    }

    /** Switch the power stage on (`ENN` low, or `TOFF` restored when no pin is wired). */
    fun enable() {
        if (enPin != null) enPin.set(false) else writeReg(REG_CHOPCONF, (chopconf and 0xFL.inv()) or toff.toLong())
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
     * Set the run current; the hold current becomes one third of it.
     *
     * @param rmsA run current in A RMS (at most 1.4)
     * @throws IllegalStateException if the internal sense resistors are selected
     */
    fun setCurrent(rmsA: Double) {
        runCs = currentToCs(rmsA)
        holdCs = currentToCs(rmsA / 3.0)
        writeIholdIrun()
    }

    /**
     * Move with the internal step generator (`VACTUAL`).
     *
     * @param stepsPerS full steps per second at the configured microstepping; sign = direction, 0 stops
     */
    fun run(stepsPerS: Double) {
        var v = Math.round(stepsPerS * stepRes * 16777216.0 / fclkHz)
        v = max(-8388607L, min(8388607L, v))
        writeReg(REG_VACTUAL, v and 0xFFFFFFL)
    }

    /** Stop the internal step generator (`VACTUAL` = 0). */
    fun stop() {
        writeReg(REG_VACTUAL, 0)
    }

    /** `true` when `DRV_STATUS.stst` is set. */
    fun isStandstill(): Boolean = (readReg(REG_DRV_STATUS) and (1L shl 31)) != 0L

    /** Bitmask of `FAULT_*` constants, 0 when no fault is present. */
    fun readFault(): Int {
        val s = readReg(REG_DRV_STATUS)
        var f = 0
        if ((s and 0x01) != 0L) f = f or FAULT_OVERTEMP_WARNING
        if ((s and 0x02) != 0L) f = f or FAULT_OVERTEMP
        if ((s and 0x0C) != 0L) f = f or FAULT_SHORT_GND
        if ((s and 0x30) != 0L) f = f or FAULT_SHORT_SUPPLY
        if ((s and 0xC0) != 0L) f = f or FAULT_OPEN_LOAD
        return f
    }
}
