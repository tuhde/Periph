package it.uhde.periph.chips.motor

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.OutputPin
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * TMC5160 full interface — extends [TMC5160Minimal] with raw register access,
 * the full SixPoint ramp, reference switches, chopper / StealthChop tuning,
 * StallGuard2 / CoolStep, DcStep, the ABN encoder and every status read-back.
 * Methods that only exist on the UART transport throw [IllegalStateException]
 * on SPI.
 */
class TMC5160Full private constructor(
    connection: Connection,
    rSenseOhm: Double,
    enPin: OutputPin?,
    uart: Boolean,
    address: Int,
    echo: Boolean,
    multiNode: Boolean,
    private val diag0: InputPin?,
    private val diag1: InputPin?,
) : TMC5160Minimal(connection, rSenseOhm, enPin, uart, address, echo, multiNode, 12_000_000L) {

    /** SPI constructor (Mode 3); `diag0` / `diag1` are optional `DIAG0` / `DIAG1` inputs. */
    constructor(
        connection: Connection, rSenseOhm: Double = 0.075, enPin: OutputPin? = null,
        diag0: InputPin? = null, diag1: InputPin? = null,
    ) : this(connection, rSenseOhm, enPin, false, 0, false, false, diag0, diag1)

    /** UART constructor (the DIAG pins carry the UART and are unavailable). */
    constructor(
        connection: Connection, rSenseOhm: Double, address: Int, enPin: OutputPin?, echo: Boolean, multiNode: Boolean,
    ) : this(connection, rSenseOhm, enPin, true, address, echo, multiNode, null, null)

    /** Decoded `DRV_STATUS`. */
    data class Status(
        val stst: Boolean, val stealth: Boolean, val fsactive: Boolean, val csActual: Int, val sgResult: Int,
        val stallguard: Boolean, val otpw: Boolean, val ot: Boolean, val shortGnd: Boolean, val shortSupply: Boolean,
        val openLoad: Boolean,
    )

    /** Decoded `RAMP_STAT`. */
    data class RampStatus(
        val statusStopL: Boolean, val statusStopR: Boolean, val statusLatchL: Boolean, val statusLatchR: Boolean,
        val eventStopL: Boolean, val eventStopR: Boolean, val eventStopSg: Boolean, val eventPosReached: Boolean,
        val velocityReached: Boolean, val positionReached: Boolean, val vzero: Boolean,
        val tZerowaitActive: Boolean, val secondMove: Boolean, val statusSg: Boolean,
    )

    /** Decoded `GSTAT`. */
    data class GlobalStatus(val reset: Boolean, val drvErr: Boolean, val uvCp: Boolean)

    /** Decoded `IOIN`. */
    data class Ioin(
        val raw: Long, val refl: Boolean, val refr: Boolean, val encb: Boolean, val enca: Boolean,
        val drvEnn: Boolean, val encn: Boolean, val sdMode: Boolean, val swcompIn: Boolean, val version: Int,
    )

    private val readableRegs = intArrayOf(
        0x00, 0x01, 0x02, 0x04, 0x07, 0x0C, 0x12, 0x20, 0x21, 0x22, 0x2D, 0x34, 0x35, 0x36, 0x38, 0x39, 0x3B, 0x3C,
        0x6A, 0x6B, 0x6C, 0x6F, 0x71, 0x72, 0x73,
    )
    private val writableRegs = intArrayOf(
        0x00, 0x01, 0x03, 0x04, 0x05, 0x09, 0x0A, 0x0B, 0x10, 0x11, 0x13, 0x14, 0x15, 0x20, 0x21, 0x23, 0x24, 0x25,
        0x26, 0x27, 0x28, 0x2A, 0x2B, 0x2C, 0x2D, 0x33, 0x34, 0x35, 0x38, 0x39, 0x3A, 0x3B, 0x3D, 0x60, 0x61, 0x62,
        0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6C, 0x6D, 0x6E, 0x70,
    )

    private fun flag(on: Boolean, mask: Long): Long = if (on) mask else 0L

    private fun bit(v: Long, i: Int): Boolean = (v and (1L shl i)) != 0L

    private fun signExtend(value: Long, bits: Int): Int {
        val v = value and ((1L shl bits) - 1)
        return (if ((v and (1L shl (bits - 1))) != 0L) v - (1L shl bits) else v).toInt()
    }

    private fun threshold(reg: Int, v: Long) {
        require(v in 0..0xFFFFFL) { "value out of range" }
        writeReg(reg, v)
    }

    private fun requireUart() = check(uart) { "this operation needs the UART bus" }

    /** Raw 32-bit read of a readable register. */
    fun readRegister(reg: Int): Long {
        require(reg in readableRegs) { "register not readable" }
        return readReg(reg)
    }

    /** Raw 32-bit write; [verify] adds the SPI mirror / UART `IFCNT` check. */
    fun writeRegister(reg: Int, value: Long, verify: Boolean = false) {
        require(reg in writableRegs) { "register not writable" }
        if (verify) writeVerified(reg, value) else writeReg(reg, value)
    }

    /** Independent hold current in A RMS (not above the run current). */
    fun setHoldCurrent(rmsA: Double) {
        val gs = if (shadow[REG_GLOBALSCALER] == 0L) 256.0 else shadow[REG_GLOBALSCALER].toDouble()
        val full = gs / 256.0 * iMax()
        val h = (rmsA / full * 32.0).roundToLong() - 1
        require(rmsA > 0 && h <= irun) { "hold current out of range" }
        ihold = maxOf(0L, h).toInt()
        writeIholdIrun()
    }

    /** Raw `GLOBALSCALER`: 0 or 32–256 (256 is written as 0). */
    fun setGlobalScaler(value: Int) {
        require(value == 0 || value in 32..256) { "value out of range" }
        writeReg(REG_GLOBALSCALER, if (value == 256) 0L else value.toLong())
    }

    /** Raw `IHOLD_IRUN` fields: `irun` 0–31, `ihold` 0–31, `iholdDelay` 0–15. */
    fun setCurrentScale(irunValue: Int, iholdValue: Int, iholdDelayValue: Int) {
        require(irunValue in 0..31 && iholdValue in 0..31 && iholdDelayValue in 0..15) { "value out of range" }
        irun = irunValue
        ihold = iholdValue
        iholdDelay = iholdDelayValue
        writeIholdIrun()
    }

    /** `TPOWERDOWN` (values below 2 break StealthChop auto-tuning). */
    fun setPowerDownDelay(tpowerdown: Int) {
        require(tpowerdown in 0..255) { "value out of range" }
        writeReg(REG_TPOWERDOWN, tpowerdown.toLong())
    }

    /** Program the full SixPoint ramp in physical units (full steps/s and full steps/s²). */
    fun configureRamp(
        vstart: Double, a1: Double, v1: Double, amax: Double, vmax: Double, dmax: Double, d1: Double, vstop: Double,
    ) {
        val raw = longArrayOf(vReg(vstart), aReg(a1), vReg(v1), aReg(amax), vReg(vmax), aReg(dmax), aReg(d1), vReg(vstop))
        for (v in raw) require(v in 0..0xFFFFFFFFL) { "value out of range" }
        setRampRaw(raw[0], raw[1], raw[2], raw[3], raw[4], raw[5], raw[6], raw[7])
        speedCur = vmax
        accelCur = amax
    }

    /** Program the full SixPoint ramp in raw register units. */
    fun setRampRaw(vstart: Long, a1: Long, v1: Long, amax: Long, vmax: Long, dmax: Long, d1: Long, vstop: Long) {
        require(
            vstart in 0 until (1L shl 18) && a1 in 0..0xFFFF && v1 in 0 until (1L shl 20) && amax in 0..0xFFFF &&
                vmax in 0..(1L shl 23) - 512 && dmax in 0..0xFFFF && d1 in 1..0xFFFF && vstop in 1 until (1L shl 18) &&
                vstop >= vstart,
        ) { "ramp value out of range" }
        writeReg(REG_VSTART, vstart)
        writeReg(REG_A1, a1)
        writeReg(REG_V1, v1)
        writeReg(REG_AMAX, amax)
        writeReg(REG_VMAX, vmax)
        writeReg(REG_DMAX, dmax)
        writeReg(REG_D1, d1)
        writeReg(REG_VSTOP, vstop)
        speedReg = vmax
    }

    /** `TZEROWAIT` in seconds (0 to about 2.8 s at 12 MHz). */
    fun setZeroWait(seconds: Double) {
        writeReg(REG_TZEROWAIT, bounded((seconds * fclkHz / 512.0).roundToLong(), 0xFFFF))
    }

    /** `RAMPMODE` 0–3 (3 = hold). */
    fun setRampMode(mode: Int) {
        require(mode in 0..3) { "mode must be 0-3" }
        writeReg(REG_RAMPMODE, mode.toLong())
        rampModeCur = mode
    }

    /** Hold mode: keep the present velocity until a stop event. */
    fun hold() = setRampMode(3)

    /** Write `XTARGET` in microsteps (starts positioning in mode 0). */
    fun setTargetRaw(xtarget: Int) = writeReg(REG_XTARGET, xtarget.toLong() and 0xFFFFFFFFL)

    /** Decoded `RAMP_STAT` (one read). */
    fun readRampStatus(): RampStatus {
        val s = readReg(REG_RAMP_STAT)
        return RampStatus(
            bit(s, 0), bit(s, 1), bit(s, 2), bit(s, 3), bit(s, 4), bit(s, 5), bit(s, 6), bit(s, 7), bit(s, 8),
            bit(s, 9), bit(s, 10), bit(s, 11), bit(s, 12), bit(s, 13),
        )
    }

    /** Write 1 to the clear-on-write bits of `RAMP_STAT`. */
    fun clearRampEvents() = writeReg(REG_RAMP_STAT, (1L shl 2) or (1L shl 3) or (1L shl 6) or (1L shl 7) or (1L shl 12))

    /** `X_COMPARE` in full steps (`DIAG1` goes high while `XACTUAL` equals it). */
    fun setPositionCompare(position: Double) = writeReg(REG_X_COMPARE, (position * usc).roundToLong() and 0xFFFFFFFFL)

    /** `SW_MODE` bits 0–4 and 11 (latch and `sg_stop` bits are kept). */
    fun configureSwitches(
        stopLeft: Boolean, stopRight: Boolean, leftActiveLow: Boolean, rightActiveLow: Boolean, swap: Boolean,
        softStop: Boolean,
    ) {
        require(!(softStop && (shadow[REG_SW_MODE] and SW_SG_STOP) != 0L)) { "soft stop conflicts with sg_stop" }
        val v = flag(stopLeft, 1) or flag(stopRight, 2) or flag(leftActiveLow, 4) or flag(rightActiveLow, 8) or
            flag(swap, 16) or flag(softStop, SW_SOFTSTOP)
        modify(REG_SW_MODE, 0x1FL or SW_SOFTSTOP, v)
    }

    /** `SW_MODE` latch bits 5–9. */
    fun configureLatch(
        leftActive: Boolean, leftInactive: Boolean, rightActive: Boolean, rightInactive: Boolean, latchEncoder: Boolean,
    ) {
        val v = flag(leftActive, 1L shl 5) or flag(leftInactive, 1L shl 6) or flag(rightActive, 1L shl 7) or
            flag(rightInactive, 1L shl 8) or flag(latchEncoder, 1L shl 9)
        modify(REG_SW_MODE, 0x1FL shl 5, v)
    }

    /** `XLATCH` in full steps. */
    fun readLatchedPosition(): Double = readReg(REG_XLATCH).toInt() / usc.toDouble()

    /** `(status_stop_l, status_stop_r)`. */
    fun readSwitches(): Pair<Boolean, Boolean> {
        val s = readReg(REG_RAMP_STAT)
        return Pair(bit(s, 0), bit(s, 1))
    }

    /** Microstep resolution 1, 2, 4, …, 256 (the ramp registers are not rewritten). */
    fun setMicrosteps(n: Int) {
        require(n in 1..256 && (n and (n - 1)) == 0) { "microsteps must be a power of two 1-256" }
        modify(REG_CHOPCONF, 0xFL shl 24, (8 - Integer.numberOfTrailingZeros(n)).toLong() shl 24)
        usc = n
    }

    /** `CHOPCONF.intpol`. */
    fun setInterpolation(enabled: Boolean) = modify(REG_CHOPCONF, 1L shl 28, flag(enabled, 1L shl 28))

    /** SpreadCycle only (`true`) or StealthChop (`false`); switch at standstill. */
    fun setChopperMode(spreadcycle: Boolean) = modify(REG_GCONF, GCONF_EN_PWM_MODE, flag(!spreadcycle, GCONF_EN_PWM_MODE))

    /** `TPWMTHRS` (20 bit); 0 disables. */
    fun setStealthchopThreshold(v: Long) = threshold(REG_TPWMTHRS, v)

    /** `THIGH` (20 bit). */
    fun setHighVelocityThreshold(v: Long) = threshold(REG_THIGH, v)

    /** `TCOOLTHRS` (20 bit); 0 disables. */
    fun setCoolstepThreshold(v: Long) = threshold(REG_TCOOLTHRS, v)

    /** SpreadCycle (`chm = 0`) fields: toff 0–15, tbl 0–3, hstrt 0–7, raw hend 0–15, tpfd 0–15. */
    fun configureChopper(toffValue: Int, tbl: Int, hstrt: Int, hend: Int, tpfd: Int) {
        require(toffValue in 0..15 && tbl in 0..3 && hstrt in 0..7 && hend in 0..15 && tpfd in 0..15) { "value out of range" }
        if (toffValue != 0) toff = toffValue
        val mask = 0xFL or (3L shl 15) or (7L shl 4) or (0xFL shl 7) or (0xFL shl 20) or (1L shl 14) or (1L shl 11)
        val v = toffValue.toLong() or (tbl.toLong() shl 15) or (hstrt.toLong() shl 4) or (hend.toLong() shl 7) or (tpfd.toLong() shl 20)
        modify(REG_CHOPCONF, mask, v)
    }

    /** Constant-off-time (`chm = 1`) fields: tfd 0–15, offset −3…12. */
    fun configureConstantOffTime(toffValue: Int, tbl: Int, tfd: Int, offset: Int, disfdcc: Boolean, tpfd: Int) {
        require(toffValue in 0..15 && tbl in 0..3 && tfd in 0..15 && offset in -3..12 && tpfd in 0..15) { "value out of range" }
        if (toffValue != 0) toff = toffValue
        val mask = 0xFL or (3L shl 15) or (7L shl 4) or (0xFL shl 7) or (0xFL shl 20) or (1L shl 14) or (1L shl 12) or (1L shl 11)
        val v = toffValue.toLong() or (tbl.toLong() shl 15) or ((tfd and 7).toLong() shl 4) or ((offset + 3).toLong() shl 7) or
            (tpfd.toLong() shl 20) or (1L shl 14) or flag(disfdcc, 1L shl 12) or (((tfd shr 3) and 1).toLong() shl 11)
        modify(REG_CHOPCONF, mask, v)
    }

    /** `CHOPCONF.vhighchm` / `vhighfs`. */
    fun configureHighVelocity(vhighchm: Boolean, vhighfs: Boolean) =
        modify(REG_CHOPCONF, (1L shl 19) or (1L shl 18), flag(vhighchm, 1L shl 19) or flag(vhighfs, 1L shl 18))

    /** `PWMCONF` fields (freewheel kept): pwmFreq 0–3, pwmReg 1–15, pwmLim 0–15. */
    fun configureStealthchop(
        autoscale: Boolean, autograd: Boolean, pwmOfs: Int, pwmGrad: Int, pwmFreq: Int, pwmReg: Int, pwmLim: Int,
    ) {
        require(pwmFreq in 0..3 && pwmReg in 1..15 && pwmLim in 0..15 && pwmOfs in 0..255 && pwmGrad in 0..255) { "value out of range" }
        firstUse(REG_PWMCONF, PWMCONF_RESET)
        val mask = (0xFL shl 28) or (0xFL shl 24) or (1L shl 19) or (1L shl 18) or (3L shl 16) or (0xFFL shl 8) or 0xFFL
        val v = (pwmLim.toLong() shl 28) or (pwmReg.toLong() shl 24) or flag(autograd, 1L shl 19) or flag(autoscale, 1L shl 18) or
            (pwmFreq.toLong() shl 16) or (pwmGrad.toLong() shl 8) or pwmOfs.toLong()
        modify(REG_PWMCONF, mask, v)
    }

    /** `PWMCONF.freewheel` 0–3. */
    fun setFreewheel(mode: Int) {
        require(mode in 0..3) { "mode must be 0-3" }
        firstUse(REG_PWMCONF, PWMCONF_RESET)
        modify(REG_PWMCONF, 3L shl 20, mode.toLong() shl 20)
    }

    /** `CHOPCONF.diss2g` / `diss2vs`: `true` keeps the protection. */
    fun setShortProtection(shortGnd: Boolean, shortSupply: Boolean) =
        modify(REG_CHOPCONF, (1L shl 30) or (1L shl 31), flag(!shortGnd, 1L shl 30) or flag(!shortSupply, 1L shl 31))

    /** `CHOPCONF.dedge` (external STEP/DIR only). */
    fun setDoubleEdge(enabled: Boolean) = modify(REG_CHOPCONF, 1L shl 29, flag(enabled, 1L shl 29))

    private fun gconfBit(mask: Long, on: Boolean) = modify(REG_GCONF, mask, flag(on, mask))

    /** `GCONF.shaft`. */
    fun setDirectionInverted(inverted: Boolean) = gconfBit(GCONF_SHAFT, inverted)

    /** `GCONF.faststandstill`. */
    fun setFastStandstill(enabled: Boolean) = gconfBit(1L shl 1, enabled)

    /** `GCONF.recalibrate`. */
    fun setRecalibrate(enabled: Boolean) = gconfBit(1L, enabled)

    /** `GCONF.small_hysteresis`. */
    fun setSmallHysteresis(enabled: Boolean) = gconfBit(GCONF_SMALL_HYSTERESIS, enabled)

    /** `GCONF.stop_enable`. */
    fun setEmergencyStop(enabled: Boolean) = gconfBit(GCONF_STOP_ENABLE, enabled)

    /** `GCONF` bits 7, 8, 12, 13 (`SD_MODE = 0` meanings). */
    fun configureDiag(diag0StepOutput: Boolean, diag1DirOutput: Boolean, diag0PushPull: Boolean, diag1PushPull: Boolean) {
        val v = flag(diag0StepOutput, 1L shl 7) or flag(diag1DirOutput, 1L shl 8) or flag(diag0PushPull, 1L shl 12) or
            flag(diag1PushPull, 1L shl 13)
        modify(REG_GCONF, (1L shl 7) or (1L shl 8) or (1L shl 12) or (1L shl 13), v)
    }

    /** `COOLCONF.sgt` −64…63 (lower = more sensitive); other fields are kept. */
    fun setStallThreshold(sgt: Int) {
        require(sgt in -64..63) { "sgt must be -64..63" }
        modify(REG_COOLCONF, 0x7FL shl 16, (sgt.toLong() and 0x7F) shl 16)
    }

    /** `COOLCONF.sfilt`. */
    fun setStallguardFilter(enabled: Boolean) = modify(REG_COOLCONF, 1L shl 24, flag(enabled, 1L shl 24))

    /** CoolStep fields of `COOLCONF` (`sgt` / `sfilt` kept). */
    fun configureCoolstep(semin: Int, semax: Int, seup: Int, sedn: Int, seimin: Int) {
        require(semin in 0..15 && semax in 0..15 && seup in 0..3 && sedn in 0..3 && seimin in 0..1) { "value out of range" }
        val mask = 0xFL or (3L shl 5) or (0xFL shl 8) or (3L shl 13) or (1L shl 15)
        val v = semin.toLong() or (seup.toLong() shl 5) or (semax.toLong() shl 8) or (sedn.toLong() shl 13) or (seimin.toLong() shl 15)
        modify(REG_COOLCONF, mask, v)
    }

    /** `SW_MODE.sg_stop`: stop the motor on a StallGuard2 event (needs `TCOOLTHRS`; not with soft stop). */
    fun enableStallStop(enabled: Boolean) {
        require(!(enabled && (shadow[REG_SW_MODE] and SW_SOFTSTOP) != 0L)) { "sg_stop conflicts with soft stop" }
        modify(REG_SW_MODE, SW_SG_STOP, flag(enabled, SW_SG_STOP))
    }

    /** `SG_RESULT` 0–1023. */
    fun readStallguard(): Int = (readReg(REG_DRV_STATUS) and 0x3FF).toInt()

    /** `DRV_STATUS.StallGuard`. */
    fun isStalled(): Boolean = bit(readReg(REG_DRV_STATUS), 24)

    /**
     * Level of `DIAG0` (`n = 0`) or `DIAG1` (`n = 1`).
     *
     * @throws IllegalStateException if the pin is not wired (or on UART)
     */
    fun readDiag(n: Int): Boolean {
        require(n == 0 || n == 1) { "n must be 0 or 1" }
        val p = if (n == 0) diag0 else diag1
        checkNotNull(p) { "DIAG$n pin not configured" }
        return p.isHigh()
    }

    /** `VDCMIN` (0…2²³−1), `DCCTRL.DC_TIME` (0–1023), `DC_SG` (0–255). */
    fun setDcstep(vdcmin: Long, dcTime: Int, dcSg: Int) {
        require(vdcmin in 0..0x7FFFFFL && dcTime in 0..1023 && dcSg in 0..255) { "value out of range" }
        writeReg(REG_VDCMIN, vdcmin)
        writeReg(REG_DCCTRL, dcTime.toLong() or (dcSg.toLong() shl 16))
    }

    /** `GCONF.direct_mode` (the ramp generator is bypassed while set; leaving it rewrites `XTARGET`). */
    fun setDirectMode(enabled: Boolean) {
        gconfBit(GCONF_DIRECT_MODE, enabled)
        if (!enabled) writeReg(REG_XTARGET, shadow[REG_XTARGET])
    }

    /** `XDIRECT` coil currents −255…255 each; requires direct mode. */
    fun setCoilCurrents(coilA: Int, coilB: Int) {
        check((shadow[REG_GCONF] and GCONF_DIRECT_MODE) != 0L) { "direct mode required" }
        require(coilA in -255..255 && coilB in -255..255) { "value out of range" }
        writeReg(REG_XTARGET, (coilA.toLong() and 0x1FF) or ((coilB.toLong() and 0x1FF) shl 16))
    }

    /** Program `MSLUT[0..7]`, `MSLUTSEL`, `MSLUTSTART`; call at `MSCNT = 0`. */
    fun writeMicrostepTable(mslut: LongArray, mslutsel: Long, mslutstart: Long) {
        require(mslut.size == 8) { "mslut needs 8 words" }
        for (i in 0 until 8) writeReg(REG_MSLUT0 + i, mslut[i])
        writeReg(REG_MSLUTSEL, mslutsel)
        writeReg(REG_MSLUTSTART, mslutstart)
    }

    /** `DRV_CONF` — board-specific, wrong values can destroy the MOSFETs. */
    fun configureGateDriver(bbmtime: Int, bbmclks: Int, otselect: Int, drvstrength: Int, filtIsense: Int) {
        require(bbmtime in 0..24 && bbmclks in 0..15 && otselect in 0..3 && drvstrength in 0..3 && filtIsense in 0..3) { "value out of range" }
        writeReg(
            REG_DRV_CONF,
            bbmtime.toLong() or (bbmclks.toLong() shl 8) or (otselect.toLong() shl 16) or (drvstrength.toLong() shl 18) or
                (filtIsense.toLong() shl 20),
        )
    }

    /** `SHORT_CONF` — board-specific: s2vsLevel 4–15, s2gLevel 2–15, shortfilter 0–3, shortdelay 0|1. */
    fun configureShortDetection(s2vsLevel: Int, s2gLevel: Int, shortfilter: Int, shortdelay: Int) {
        require(s2vsLevel in 4..15 && s2gLevel in 2..15 && shortfilter in 0..3 && shortdelay in 0..1) { "value out of range" }
        writeReg(
            REG_SHORT_CONF,
            s2vsLevel.toLong() or (s2gLevel.toLong() shl 8) or (shortfilter.toLong() shl 16) or (shortdelay.toLong() shl 18),
        )
    }

    /**
     * `ENCMODE`; [flags] is, in register bit order: pol_a, pol_b, pol_n, ignore_ab, clr_cont, clr_once, pos_edge,
     * neg_edge, clr_enc_x, latch_x_act, decimal.
     */
    fun configureEncoder(flags: BooleanArray) {
        require(flags.size == 11) { "11 flags required" }
        var v = 0L
        for (i in 0 until 11) v = v or flag(flags[i], 1L shl i)
        writeReg(REG_ENCMODE, v)
    }

    /** `ENC_CONST`: signed 16.16 (or integer + 4-digit decimal fraction when `enc_sel_decimal` is set). */
    fun setEncoderConstant(value: Double) {
        require(abs(value) <= 32767.9999) { "value out of range" }
        var raw: Long
        if ((shadow[REG_ENCMODE] and (1L shl 10)) != 0L) {
            val a = abs(value)
            val whole = floor(a).toLong()
            raw = (whole shl 16) or ((a - whole) * 10000.0).roundToLong()
            if (value < 0) raw = -raw
        } else {
            raw = (value * 65536.0).roundToLong()
        }
        writeReg(REG_ENC_CONST, raw and 0xFFFFFFFFL)
    }

    /** `X_ENC`. */
    fun setEncoderPosition(counts: Int) = writeReg(REG_X_ENC, counts.toLong() and 0xFFFFFFFFL)

    /** `X_ENC` signed. */
    fun readEncoderPosition(): Int = readReg(REG_X_ENC).toInt()

    /** `ENC_LATCH` signed. */
    fun readEncoderLatch(): Int = readReg(REG_ENC_LATCH).toInt()

    /** `ENC_DEVIATION` 0…2²⁰−1 (0 = off). */
    fun setEncoderDeviation(steps: Long) = threshold(REG_ENC_DEVIATION, steps)

    /** `(n_event, deviation_warn)`. */
    fun readEncoderStatus(): Pair<Boolean, Boolean> {
        val s = readReg(REG_ENC_STATUS)
        return Pair(bit(s, 0), bit(s, 1))
    }

    /** Write 1 to the `ENC_STATUS` event bits. */
    fun clearEncoderStatus() = writeReg(REG_ENC_STATUS, 3)

    /** `SLAVECONF.SLAVEADDR` 0–253 (UART only); later datagrams use the new address. */
    fun setSlaveAddress(slaveaddr: Int) {
        requireUart()
        require(slaveaddr in 0..253) { "address must be 0-253" }
        writeReg(REG_SLAVECONF, slaveaddr.toLong() or (sendDelayVal.toLong() shl 8))
        address = slaveaddr
    }

    /** `SLAVECONF.SENDDELAY` 0–15 (UART only; at least 2 on multi-node buses). */
    fun setSendDelay(sd: Int) {
        requireUart()
        require(sd in 0..15) { "sendDelay must be 0-15" }
        sendDelayVal = sd
        writeReg(REG_SLAVECONF, (shadow[REG_SLAVECONF] and 0xFF) or (sd.toLong() shl 8))
    }

    /** `OUTPUT` bit 0, the `NAO` polarity (UART only). */
    fun setNaoPolarity(level: Boolean) {
        requireUart()
        writeReg(REG_OUTPUT, flag(level, 1))
    }

    /** `IFCNT` (UART only). */
    fun readIfcnt(): Int {
        requireUart()
        return (readReg(REG_IFCNT) and 0xFF).toInt()
    }

    /** `MSCNT` 0–1023. */
    fun microstepPosition(): Int = (readReg(REG_MSCNT) and 0x3FF).toInt()

    /** `DRV_STATUS.CS_ACTUAL` 0–31. */
    fun readCurrentScale(): Int = ((readReg(REG_DRV_STATUS) shr 16) and 0x1F).toInt()

    /** Signed `(CUR_A, CUR_B)` from `MSCURACT`. */
    fun readCoilCurrents(): Pair<Int, Int> {
        val raw = readReg(REG_MSCURACT)
        return Pair(signExtend(raw shr 16, 9), signExtend(raw, 9))
    }

    /** `TSTEP` (20 bit). */
    fun readTstep(): Long = readReg(REG_TSTEP) and 0xFFFFFL

    /** `(PWM_SCALE_SUM, PWM_SCALE_AUTO)`. */
    fun readPwmScale(): Pair<Int, Int> {
        val raw = readReg(REG_PWM_SCALE)
        return Pair((raw and 0xFF).toInt(), signExtend(raw shr 16, 9))
    }

    /** `(PWM_OFS_AUTO, PWM_GRAD_AUTO)`. */
    fun readPwmAuto(): Pair<Int, Int> {
        val raw = readReg(REG_PWM_AUTO)
        return Pair((raw and 0xFF).toInt(), ((raw shr 16) and 0xFF).toInt())
    }

    /** Signed `(phase A, phase B)` from `OFFSET_READ`. */
    fun readOffset(): Pair<Int, Int> {
        val raw = readReg(REG_OFFSET_READ)
        return Pair((raw shr 8).toByte().toInt(), raw.toByte().toInt())
    }

    /** Decoded `DRV_STATUS` (one read). */
    fun readStatus(): Status {
        val s = readReg(REG_DRV_STATUS)
        return Status(
            (s shr 31) == 1L, bit(s, 14), bit(s, 15), ((s shr 16) and 0x1F).toInt(), (s and 0x3FF).toInt(), bit(s, 24),
            bit(s, 26), bit(s, 25), (s and (3L shl 27)) != 0L, (s and (3L shl 12)) != 0L, (s and (3L shl 29)) != 0L,
        )
    }

    /** Decoded `GSTAT` (does not clear). */
    fun readGlobalStatus(): GlobalStatus {
        val g = readReg(REG_GSTAT)
        return GlobalStatus(bit(g, 0), bit(g, 1), bit(g, 2))
    }

    /** Write `0x07` to `GSTAT`. */
    fun clearGlobalStatus() = writeReg(REG_GSTAT, 7)

    /** Decoded `IOIN`. */
    fun readIoin(): Ioin {
        val r = readReg(REG_IOIN)
        return Ioin(r, bit(r, 0), bit(r, 1), bit(r, 2), bit(r, 3), bit(r, 4), bit(r, 5), bit(r, 6), bit(r, 7), ((r shr 24) and 0xFF).toInt())
    }
}
