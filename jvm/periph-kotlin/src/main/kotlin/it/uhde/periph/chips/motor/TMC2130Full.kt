package it.uhde.periph.chips.motor

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.OutputPin
import kotlin.math.abs

/**
 * TMC2130 full interface — extends [TMC2130Minimal] with raw register access,
 * independent hold current, microstep, chopper and StealthChop tuning,
 * StallGuard2 / CoolStep, DIAG routing, DcStep, direct coil-current mode, the
 * microstep table and every status read-back.
 *
 * @param connection SPI connection configured for Mode 3
 * @param stepPin `STEP` output
 * @param dirPin `DIR` output
 * @param rSenseOhm external sense resistor in ohm
 * @param enPin optional `ENN` output (active low)
 * @param diag0 optional `DIAG0` input
 * @param diag1 optional `DIAG1` input
 */
class TMC2130Full(
    connection: Connection,
    stepPin: OutputPin,
    dirPin: OutputPin,
    rSenseOhm: Double = 0.11,
    enPin: OutputPin? = null,
    private val diag0: InputPin? = null,
    private val diag1: InputPin? = null,
) : TMC2130Minimal(connection, stepPin, dirPin, rSenseOhm, enPin) {

    /** Decoded `DRV_STATUS`. */
    data class Status(
        val stst: Boolean, val fsactive: Boolean, val csActual: Int, val sgResult: Int, val stallguard: Boolean,
        val otpw: Boolean, val ot: Boolean, val shortGnd: Boolean, val openLoad: Boolean,
    )

    /** Decoded `GSTAT`. */
    data class GlobalStatus(val reset: Boolean, val drvErr: Boolean, val uvCp: Boolean)

    /** Decoded `IOIN`. */
    data class Ioin(
        val raw: Long, val step: Boolean, val dir: Boolean, val dcen: Boolean, val dcin: Boolean,
        val enn: Boolean, val dco: Boolean, val version: Int,
    )

    /** The two signed 9-bit `MSCURACT` values: `CUR_A` (bits 24:16) and `CUR_B` (bits 8:0). */
    data class CoilCurrents(val curA: Int, val curB: Int)

    private fun readable(r: Int) = r in intArrayOf(0x00, 0x01, 0x04, 0x12, 0x2D, 0x6A, 0x6B, 0x6C, 0x6F, 0x71, 0x73)

    private fun writable(r: Int) = r in intArrayOf(
        0x00, 0x10, 0x11, 0x13, 0x14, 0x15, 0x2D, 0x33, 0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67,
        0x68, 0x69, 0x6C, 0x6D, 0x6E, 0x70,
    )

    private fun signed(v: Long, bits: Int): Int {
        val x = (v and ((1L shl bits) - 1)).toInt()
        return if ((x and (1 shl (bits - 1))) != 0) x - (1 shl bits) else x
    }

    /**
     * Read a raw 32-bit register.
     *
     * @throws IllegalArgumentException for write-only or reserved registers
     */
    fun readRegister(reg: Int): Long {
        require(readable(reg)) { String.format("register 0x%02X is not readable", reg) }
        return readReg(reg)
    }

    /**
     * Write a raw 32-bit register; [verify] adds the mirror check.
     *
     * @throws IllegalArgumentException for read-only or reserved registers
     */
    fun writeRegister(reg: Int, value: Long, verify: Boolean = false) {
        require(writable(reg)) { String.format("register 0x%02X is not writable", reg) }
        writeReg(reg, value, verify)
    }

    /** Set the standstill hold current in A RMS. */
    fun setHoldCurrent(rmsA: Double) {
        holdCs = currentToCs(rmsA, vsenseOn).first
        writeIholdIrun()
    }

    /**
     * Set `IHOLDDELAY` (0–15) and `TPOWERDOWN` (0–255).
     *
     * @throws IllegalArgumentException if out of range
     */
    fun setCurrentDelay(ihold: Int, powerdown: Int) {
        require(ihold in 0..15 && powerdown in 0..255) { "ihold_delay 0-15 / tpowerdown 0-255" }
        iholdDelay = ihold
        writeIholdIrun()
        writeReg(REG_TPOWERDOWN, powerdown.toLong())
    }

    /**
     * Select the microstep resolution (1, 2, 4, ..., 256).
     *
     * @throws IllegalArgumentException for any other value
     */
    fun setMicrosteps(n: Int) {
        require(n in 1..256 && (n and (n - 1)) == 0) { "microsteps must be a power of two, 1-256" }
        modify(REG_CHOPCONF, 0xFL shl 24, (8 - Integer.numberOfTrailingZeros(n)).toLong() shl 24)
        stepRes = n
    }

    /** `CHOPCONF.intpol`. */
    fun setInterpolation(enabled: Boolean) = modify(REG_CHOPCONF, 1L shl 28, if (enabled) 1L shl 28 else 0L)

    /** SpreadCycle only (`true`) or StealthChop (`false`); switch at standstill. */
    fun setChopperMode(spreadcycle: Boolean) =
        modify(REG_GCONF, GCONF_EN_PWM_MODE, if (spreadcycle) 0L else GCONF_EN_PWM_MODE)

    /**
     * `TPWMTHRS` (20 bit); 0 disables.
     *
     * @throws IllegalArgumentException if out of range
     */
    fun setStealthchopThreshold(tpwmthrs: Long) {
        require(tpwmthrs in 0..0xFFFFFL) { "tpwmthrs 0-1048575" }
        writeReg(REG_TPWMTHRS, tpwmthrs)
    }

    /**
     * `THIGH` (20 bit).
     *
     * @throws IllegalArgumentException if out of range
     */
    fun setHighVelocityThreshold(thigh: Long) {
        require(thigh in 0..0xFFFFFL) { "thigh 0-1048575" }
        writeReg(REG_THIGH, thigh)
    }

    /**
     * SpreadCycle (`chm = 0`) fields: [toffV] 0–15, [tbl] 0–3, [hstrt] 0–7, raw [hend] 0–15.
     *
     * @throws IllegalArgumentException if a field is out of range
     */
    fun configureChopper(toffV: Int, tbl: Int, hstrt: Int, hend: Int) {
        require(toffV in 0..15 && tbl in 0..3 && hstrt in 0..7 && hend in 0..15) { "chopper field out of range" }
        if (toffV != 0) toff = toffV
        val mask = 0xFL or (3L shl 15) or (7L shl 4) or (0xFL shl 7) or (1L shl 14) or (1L shl 11)
        modify(REG_CHOPCONF, mask, toffV.toLong() or (tbl.toLong() shl 15) or (hstrt.toLong() shl 4) or (hend.toLong() shl 7))
    }

    /**
     * Constant-off-time (`chm = 1`) fields: [tfd] 0–15, [offset] −3…12.
     *
     * @throws IllegalArgumentException if a field is out of range
     */
    fun configureConstantOffTime(toffV: Int, tbl: Int, tfd: Int, offset: Int, disfdcc: Boolean, rndtf: Boolean) {
        require(toffV in 0..15 && tbl in 0..3 && tfd in 0..15 && offset in -3..12) { "chopper field out of range" }
        if (toffV != 0) toff = toffV
        val mask = 0xFL or (3L shl 15) or (7L shl 4) or (0xFL shl 7) or (1L shl 14) or (1L shl 13) or (1L shl 12) or (1L shl 11)
        val v = toffV.toLong() or (tbl.toLong() shl 15) or ((tfd and 7).toLong() shl 4) or ((offset + 3).toLong() shl 7) or
            (1L shl 14) or (if (rndtf) 1L shl 13 else 0L) or (if (disfdcc) 1L shl 12 else 0L) or (((tfd shr 3) and 1).toLong() shl 11)
        modify(REG_CHOPCONF, mask, v)
    }

    /** `CHOPCONF.vhighchm` / `vhighfs`. */
    fun configureHighVelocity(vhighchm: Boolean, vhighfs: Boolean) =
        modify(REG_CHOPCONF, (1L shl 19) or (1L shl 18), (if (vhighchm) 1L shl 19 else 0L) or (if (vhighfs) 1L shl 18 else 0L))

    /**
     * `CHOPCONF.SYNC` 0–15 (chopSync2).
     *
     * @throws IllegalArgumentException if out of range
     */
    fun setChopperSync(sync: Int) {
        require(sync in 0..15) { "sync 0-15" }
        modify(REG_CHOPCONF, 0xFL shl 20, sync.toLong() shl 20)
    }

    /**
     * `PWMCONF` StealthChop fields (`freewheel` is kept).
     *
     * @throws IllegalArgumentException if a field is out of range
     */
    fun configureStealthchop(autoscale: Boolean, symmetric: Boolean, pwmAmpl: Int, pwmGrad: Int, pwmFreq: Int) {
        require(pwmAmpl in 0..255 && pwmGrad in 0..255 && pwmFreq in 0..3) { "stealthchop field out of range" }
        val mask = (1L shl 19) or (1L shl 18) or (3L shl 16) or (0xFFL shl 8) or 0xFFL
        val v = (if (symmetric) 1L shl 19 else 0L) or (if (autoscale) 1L shl 18 else 0L) or (pwmFreq.toLong() shl 16) or
            (pwmGrad.toLong() shl 8) or pwmAmpl.toLong()
        modify(REG_PWMCONF, mask, v)
    }

    /**
     * `PWMCONF.freewheel` 0–3.
     *
     * @throws IllegalArgumentException if not 0–3
     */
    fun setFreewheel(mode: Int) {
        require(mode in 0..3) { "freewheel mode 0-3" }
        modify(REG_PWMCONF, 3L shl 20, mode.toLong() shl 20)
    }

    /** `CHOPCONF.vsense`; disables the automatic selection in [setCurrent]. */
    fun setVsense(highSensitivity: Boolean) {
        modify(REG_CHOPCONF, 1L shl 17, if (highSensitivity) 1L shl 17 else 0L)
        vsenseOn = highSensitivity
        autoVsense = false
    }

    /** `GCONF.internal_Rsense` ([setCurrent] then throws). */
    fun setInternalRsense(enabled: Boolean) {
        modify(REG_GCONF, GCONF_INTERNAL_RSENSE, if (enabled) GCONF_INTERNAL_RSENSE else 0L)
        rsenseInternal = enabled
    }

    /** `GCONF.I_scale_analog`. */
    fun setAnalogScaling(enabled: Boolean) =
        modify(REG_GCONF, GCONF_I_SCALE_ANALOG, if (enabled) GCONF_I_SCALE_ANALOG else 0L)

    /** `GCONF.shaft`. */
    fun setDirectionInverted(inverted: Boolean) = modify(REG_GCONF, GCONF_SHAFT, if (inverted) GCONF_SHAFT else 0L)

    /** Route events to the DIAG pins (`GCONF` bits 5–13). */
    fun configureDiag(
        diag0Error: Boolean = false, diag0Otpw: Boolean = false, diag0Stall: Boolean = false,
        diag1Stall: Boolean = false, diag1Index: Boolean = false, diag1Onstate: Boolean = false,
        diag0Pushpull: Boolean = false, diag1Pushpull: Boolean = false,
    ) {
        fun b(f: Boolean, bit: Int) = if (f) 1L shl bit else 0L
        val v = b(diag0Error, 5) or b(diag0Otpw, 6) or b(diag0Stall, 7) or b(diag1Stall, 8) or b(diag1Index, 9) or
            b(diag1Onstate, 10) or b(diag0Pushpull, 12) or b(diag1Pushpull, 13)
        modify(REG_GCONF, 0x3FE0L and (1L shl 11).inv(), v)
    }

    /** `CHOPCONF.dedge`. */
    fun setDoubleEdge(enabled: Boolean) = modify(REG_CHOPCONF, 1L shl 29, if (enabled) 1L shl 29 else 0L)

    /** `true` keeps the short-to-GND protection (`CHOPCONF.diss2g` = 0). */
    fun setShortProtection(shortGnd: Boolean) = modify(REG_CHOPCONF, 1L shl 30, if (shortGnd) 0L else 1L shl 30)

    /** `GCONF.small_hysteresis`. */
    fun setSmallHysteresis(enabled: Boolean) =
        modify(REG_GCONF, GCONF_SMALL_HYSTERESIS, if (enabled) GCONF_SMALL_HYSTERESIS else 0L)

    /** `GCONF.stop_enable`. */
    fun setEmergencyStop(enabled: Boolean) = modify(REG_GCONF, GCONF_STOP_ENABLE, if (enabled) GCONF_STOP_ENABLE else 0L)

    /**
     * `COOLCONF.sgt` −64…63 (lower is more sensitive); other fields are kept.
     *
     * @throws IllegalArgumentException if out of range
     */
    fun setStallThreshold(sgt: Int) {
        require(sgt in -64..63) { "sgt -64..63" }
        modify(REG_COOLCONF, 0x7FL shl 16, (sgt and 0x7F).toLong() shl 16)
    }

    /** `COOLCONF.sfilt`. */
    fun setStallguardFilter(enabled: Boolean) = modify(REG_COOLCONF, 1L shl 24, if (enabled) 1L shl 24 else 0L)

    /**
     * `TCOOLTHRS` (20 bit); 0 disables.
     *
     * @throws IllegalArgumentException if out of range
     */
    fun setCoolstepThreshold(tcoolthrs: Long) {
        require(tcoolthrs in 0..0xFFFFFL) { "tcoolthrs 0-1048575" }
        writeReg(REG_TCOOLTHRS, tcoolthrs)
    }

    /**
     * `COOLCONF` CoolStep fields (`sgt` / `sfilt` are kept).
     *
     * @throws IllegalArgumentException if a field is out of range
     */
    fun configureCoolstep(semin: Int, semax: Int, seup: Int, sedn: Int, seimin: Int) {
        require(semin in 0..15 && semax in 0..15 && seup in 0..3 && sedn in 0..3 && seimin in 0..1) {
            "COOLCONF field out of range"
        }
        val mask = 0xFL or (3L shl 5) or (0xFL shl 8) or (3L shl 13) or (1L shl 15)
        modify(REG_COOLCONF, mask, semin.toLong() or (seup.toLong() shl 5) or (semax.toLong() shl 8) or
            (sedn.toLong() shl 13) or (seimin.toLong() shl 15))
    }

    /** `SG_RESULT` 0–1023 (0 = highest load). */
    fun readStallguard(): Int = (readReg(REG_DRV_STATUS) and 0x3FF).toInt()

    /** `DRV_STATUS.StallGuard`. */
    fun isStalled(): Boolean = (readReg(REG_DRV_STATUS) and (1L shl 24)) != 0L

    /**
     * Level of `DIAG0` ([n] = 0) or `DIAG1` ([n] = 1).
     *
     * @throws IllegalArgumentException if [n] is not 0 or 1
     * @throws IllegalStateException if that pin was not wired
     */
    fun readDiag(n: Int): Boolean {
        require(n == 0 || n == 1) { "n must be 0 or 1" }
        val pin = checkNotNull(if (n == 0) diag0 else diag1) { "readDiag() requires a DIAG$n pin" }
        return pin.isHigh()
    }

    /** Emit STEP pulses (negative [count] reverses); [delayS] is the time between edges in seconds. */
    fun step(count: Int, delayS: Double) {
        val nanos = (delayS * 1e9).toLong()
        dirPin.set(count >= 0)
        repeat(abs(count)) {
            stepPin.set(true)
            sleepNanos(nanos)
            stepPin.set(false)
            sleepNanos(nanos)
        }
    }

    /** `GCONF.direct_mode`. */
    fun setDirectMode(enabled: Boolean) = modify(REG_GCONF, GCONF_DIRECT_MODE, if (enabled) GCONF_DIRECT_MODE else 0L)

    /**
     * Write `XDIRECT` (−255…255 each); requires direct mode.
     *
     * @throws IllegalStateException if direct mode is not enabled
     * @throws IllegalArgumentException if out of range
     */
    fun setCoilCurrents(coilA: Int, coilB: Int) {
        check((gconf and GCONF_DIRECT_MODE) != 0L) { "setCoilCurrents() requires direct mode" }
        require(coilA in -255..255 && coilB in -255..255) { "coil currents -255..255" }
        writeReg(REG_XDIRECT, (coilA.toLong() and 0x1FF) or ((coilB.toLong() and 0x1FF) shl 16))
    }

    /**
     * `VDCMIN` (0…2²³−1), `DCCTRL.DC_TIME` (0–1023) and `DC_SG` (0–255).
     *
     * @throws IllegalArgumentException if out of range
     */
    fun setDcstep(vdcmin: Long, dcTime: Int, dcSg: Int) {
        require(vdcmin in 0..0x7FFFFFL && dcTime in 0..1023 && dcSg in 0..255) { "DcStep field out of range" }
        writeReg(REG_VDCMIN, vdcmin)
        writeReg(REG_DCCTRL, dcTime.toLong() or (dcSg.toLong() shl 16))
    }

    /**
     * Program `MSLUT[0…7]`, `MSLUTSEL` and `MSLUTSTART`; call at `MSCNT = 0`.
     *
     * @throws IllegalArgumentException if [mslut] does not hold 8 words
     */
    fun writeMicrostepTable(mslut: LongArray, mslutsel: Long, mslutstart: Long) {
        require(mslut.size == 8) { "mslut needs 8 words" }
        for (i in 0 until 8) writeReg(REG_MSLUT0 + i, mslut[i])
        writeReg(REG_MSLUTSEL, mslutsel)
        writeReg(REG_MSLUTSTART, mslutstart)
    }

    /** `MSCNT` 0–1023. */
    fun microstepPosition(): Int = (readReg(REG_MSCNT) and 0x3FF).toInt()

    /** `DRV_STATUS.CS_ACTUAL` 0–31. */
    fun readCurrentScale(): Int = ((readReg(REG_DRV_STATUS) shr 16) and 0x1F).toInt()

    /** Signed `(CUR_A, CUR_B)` from `MSCURACT`. */
    fun readCoilCurrents(): CoilCurrents {
        val raw = readReg(REG_MSCURACT)
        return CoilCurrents(signed(raw shr 16, 9), signed(raw, 9))
    }

    /** `TSTEP` (20 bit). */
    fun readTstep(): Long = readReg(REG_TSTEP) and 0xFFFFF

    /** `PWM_SCALE` 0–255. */
    fun readPwmScale(): Int = (readReg(REG_PWM_SCALE) and 0xFF).toInt()

    /** `LOST_STEPS` (20 bit, wraps). */
    fun readLostSteps(): Long = readReg(REG_LOST_STEPS) and 0xFFFFF

    /** Decoded `DRV_STATUS` (one read). */
    fun readStatus(): Status {
        val s = readReg(REG_DRV_STATUS)
        return Status(
            (s and (1L shl 31)) != 0L, (s and (1L shl 15)) != 0L, ((s shr 16) and 0x1F).toInt(), (s and 0x3FF).toInt(),
            (s and (1L shl 24)) != 0L, (s and (1L shl 26)) != 0L, (s and (1L shl 25)) != 0L,
            (s and (3L shl 27)) != 0L, (s and (3L shl 29)) != 0L,
        )
    }

    /** Decoded `GSTAT`; the chip clears the flags on read. */
    fun readGlobalStatus(): GlobalStatus {
        val g = readReg(REG_GSTAT)
        return GlobalStatus((g and 1) != 0L, (g and 2) != 0L, (g and 4) != 0L)
    }

    /** Decoded `IOIN`. */
    fun readIoin(): Ioin {
        val raw = readReg(REG_IOIN)
        return Ioin(
            raw, (raw and 1L) != 0L, (raw and 2L) != 0L, (raw and 4L) != 0L, (raw and 8L) != 0L,
            (raw and 16L) != 0L, (raw and 32L) != 0L, ((raw shr 24) and 0xFF).toInt(),
        )
    }
}
