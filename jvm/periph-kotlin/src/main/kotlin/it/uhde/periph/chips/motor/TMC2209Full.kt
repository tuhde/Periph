package it.uhde.periph.chips.motor

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.OutputPin
import kotlin.math.abs

/**
 * TMC2209 full interface — extends [TMC2209Minimal] with raw register access,
 * independent hold current, microstep and chopper configuration, StealthChop
 * tuning, GPIO STEP/DIR pulsing and every status read-back.
 *
 * @param connection UART connection to `PDN_UART`
 * @param rSenseOhm external sense resistor in ohm
 * @param address UART node address 0–3
 * @param enPin optional `ENN` output (active low)
 * @param echo true when TX and RX share `PDN_UART`
 * @param stepPin optional `STEP` output
 * @param dirPin optional `DIR` output
 * @param diagPin optional `DIAG` input (stall / driver error)
 * @param multiNode true when several chips share the UART; init then writes `NODECONF.SENDDELAY = 2` first
 * @param fclkHz chip clock in Hz (12 MHz internal)
 */
class TMC2209Full(
    connection: Connection,
    rSenseOhm: Double = 0.11,
    address: Int = 0,
    enPin: OutputPin? = null,
    echo: Boolean = true,
    private val stepPin: OutputPin? = null,
    private val dirPin: OutputPin? = null,
    private val diagPin: InputPin? = null,
    multiNode: Boolean = false,
    fclkHz: Long = 12_000_000L,
) : TMC2209Minimal(connection, rSenseOhm, address, enPin, echo, fclkHz, multiNode) {

    /** Decoded `DRV_STATUS`. */
    data class Status(
        val stst: Boolean, val stealth: Boolean, val csActual: Int, val otpw: Boolean, val ot: Boolean,
        val shortGnd: Boolean, val shortSupply: Boolean, val openLoad: Boolean,
        val t120: Boolean, val t143: Boolean, val t150: Boolean, val t157: Boolean,
    )

    /** Decoded `GSTAT`. */
    data class GlobalStatus(val reset: Boolean, val drvErr: Boolean, val uvCp: Boolean)

    /** Decoded `IOIN` identity fields. */
    data class Ioin(
        val raw: Long, val enn: Boolean, val ms1: Boolean, val ms2: Boolean, val diag: Boolean,
        val pdnUart: Boolean, val step: Boolean, val spread: Boolean, val dir: Boolean, val version: Int,
    )

    /** StealthChop auto-tuning results. */
    data class PwmAuto(val pwmScaleSum: Int, val pwmScaleAuto: Int, val pwmOfsAuto: Int, val pwmGradAuto: Int)

    /** The two signed 9-bit `MSCURACT` values in register order. */
    data class CoilCurrents(val low: Int, val high: Int)

    private fun readable(r: Int) = r in intArrayOf(0x00, 0x01, 0x02, 0x05, 0x06, 0x07, 0x12, 0x41, 0x6A, 0x6B, 0x6C, 0x6F, 0x70, 0x71, 0x72)

    private fun writable(r: Int) = r in intArrayOf(0x00, 0x01, 0x03, 0x10, 0x11, 0x13, 0x14, 0x22, 0x40, 0x42, 0x6C, 0x70)

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
     * Write a raw 32-bit register.
     *
     * @throws IllegalArgumentException for read-only registers, `OTP_PROG` and `FACTORY_CONF`
     */
    fun writeRegister(reg: Int, value: Long) {
        require(writable(reg)) { String.format("register 0x%02X is not writable", reg) }
        writeReg(reg, value)
    }

    /** Set the standstill hold current in A RMS. */
    fun setHoldCurrent(rmsA: Double) {
        holdCs = currentToCs(rmsA)
        writeIholdIrun()
    }

    /**
     * Set `IHOLDDELAY` (0–15) and `TPOWERDOWN` (0–255).
     *
     * @throws IllegalArgumentException if out of range
     */
    fun setCurrentDelay(iholdDelay: Int, tpowerdown: Int) {
        require(iholdDelay in 0..15 && tpowerdown in 0..255) { "ihold_delay 0-15 / tpowerdown 0-255" }
        this.iholdDelay = iholdDelay
        writeIholdIrun()
        writeReg(REG_TPOWERDOWN, tpowerdown.toLong())
    }

    /**
     * Select the microstep resolution: 1, 2, 4, 8, 16, 32, 64, 128 or 256.
     *
     * @throws IllegalArgumentException for any other value
     */
    fun setMicrosteps(n: Int) {
        val mres = when (n) {
            256 -> 0; 128 -> 1; 64 -> 2; 32 -> 3; 16 -> 4; 8 -> 5; 4 -> 6; 2 -> 7; 1 -> 8
            else -> throw IllegalArgumentException("microsteps must be a power of two, 1-256")
        }
        modify(REG_CHOPCONF, 0xFL shl 24, mres.toLong() shl 24)
        stepRes = n
    }

    /** `CHOPCONF.intpol`. */
    fun setInterpolation(enabled: Boolean) = modify(REG_CHOPCONF, 1L shl 28, if (enabled) 1L shl 28 else 0L)

    /** `true` = SpreadCycle, `false` = StealthChop. */
    fun setChopperMode(spreadcycle: Boolean) =
        modify(REG_GCONF, GCONF_EN_SPREADCYCLE, if (spreadcycle) GCONF_EN_SPREADCYCLE else 0L)

    /** `TPWMTHRS` (20 bit); 0 disables the threshold. */
    fun setStealthchopThreshold(tpwmthrs: Long) = writeReg(REG_TPWMTHRS, tpwmthrs and 0xFFFFFL)

    /** `CHOPCONF` timing fields; [hend] is the raw field 0–15 (offset −3). */
    fun configureChopper(toff: Int, tbl: Int, hstrt: Int, hend: Int) {
        val mask = 0xFL or (3L shl 15) or (7L shl 4) or (0xFL shl 7)
        val value = (toff.toLong() and 0xF) or ((tbl.toLong() and 3) shl 15) or ((hstrt.toLong() and 7) shl 4) or
            ((hend.toLong() and 0xF) shl 7)
        if (toff != 0) this.toff = toff and 0xF
        modify(REG_CHOPCONF, mask, value)
    }

    /** Write the StealthChop fields of `PWMCONF`. */
    fun configureStealthchop(
        autoscale: Boolean, autograd: Boolean, pwmOfs: Int, pwmGrad: Int, pwmFreq: Int, pwmReg: Int, pwmLim: Int,
    ) {
        val mask = (0xFL shl 28) or (0xFL shl 24) or (1L shl 19) or (1L shl 18) or (3L shl 16) or (0xFFL shl 8) or 0xFFL
        val value = ((pwmLim.toLong() and 0xF) shl 28) or ((pwmReg.toLong() and 0xF) shl 24) or
            (if (autograd) 1L shl 19 else 0L) or (if (autoscale) 1L shl 18 else 0L) or
            ((pwmFreq.toLong() and 3) shl 16) or ((pwmGrad.toLong() and 0xFF) shl 8) or (pwmOfs.toLong() and 0xFF)
        modify(REG_PWMCONF, mask, value)
    }

    /**
     * `PWMCONF.freewheel` 0–3.
     *
     * @throws IllegalArgumentException if out of range
     */
    fun setFreewheel(mode: Int) {
        require(mode in 0..3) { "freewheel mode 0-3" }
        modify(REG_PWMCONF, 3L shl 20, mode.toLong() shl 20)
    }

    /** `true` selects the low 0.18 V sense range (also changes the current conversion). */
    fun setVsense(highSensitivity: Boolean) {
        modify(REG_CHOPCONF, 1L shl 17, if (highSensitivity) 1L shl 17 else 0L)
        vsenseOn = highSensitivity
    }

    /** `GCONF.internal_Rsense`; [setCurrent] then throws. */
    fun setInternalRsense(enabled: Boolean) {
        modify(REG_GCONF, GCONF_INTERNAL_RSENSE, if (enabled) GCONF_INTERNAL_RSENSE else 0L)
        rsenseInternal = enabled
    }

    /** `GCONF.I_scale_analog`. */
    fun setAnalogScaling(enabled: Boolean) =
        modify(REG_GCONF, GCONF_I_SCALE_ANALOG, if (enabled) GCONF_I_SCALE_ANALOG else 0L)

    /** `GCONF.shaft`. */
    fun setDirectionInverted(inverted: Boolean) = modify(REG_GCONF, GCONF_SHAFT, if (inverted) GCONF_SHAFT else 0L)

    /**
     * INDEX output: 0 first microstep, 1 `otpw`, 2 step pulses.
     *
     * @throws IllegalArgumentException if out of range
     */
    fun setIndexOutput(mode: Int) {
        val bits = when (mode) {
            0 -> 0L
            1 -> GCONF_INDEX_OTPW
            2 -> GCONF_INDEX_STEP
            else -> throw IllegalArgumentException("index mode 0-2")
        }
        modify(REG_GCONF, GCONF_INDEX_OTPW or GCONF_INDEX_STEP, bits)
    }

    /** Keep (`true`) or disable (`false`) the low-side-short and short-to-GND protections. */
    fun setDriverProtection(shortLs: Boolean, shortGnd: Boolean) {
        val value = (if (shortLs) 0L else 1L shl 31) or (if (shortGnd) 0L else 1L shl 30)
        modify(REG_CHOPCONF, (1L shl 31) or (1L shl 30), value)
    }

    /** `CHOPCONF.dedge`. */
    fun setDoubleEdge(enabled: Boolean) = modify(REG_CHOPCONF, 1L shl 29, if (enabled) 1L shl 29 else 0L)

    /**
     * `NODECONF.SENDDELAY` 0–15.
     *
     * @throws IllegalArgumentException if out of range
     */
    fun setSendDelay(n: Int) {
        require(n in 0..15) { "send delay 0-15" }
        writeReg(REG_NODECONF, n.toLong() shl 8)
    }

    /**
     * Set the StallGuard4 threshold (`SGTHRS`); a stall is signalled when `SG_RESULT <= 2 * sgthrs`.
     *
     * @param sgthrs 0–255, higher is more sensitive
     * @throws IllegalArgumentException if out of range
     */
    fun setStallThreshold(sgthrs: Int) {
        require(sgthrs in 0..255) { "sgthrs 0-255" }
        writeReg(REG_SGTHRS, sgthrs.toLong())
    }

    /**
     * Set the lower velocity limit for CoolStep and the `DIAG` stall output.
     *
     * @param tcoolthrs `TCOOLTHRS` (20 bit); 0 disables
     * @throws IllegalArgumentException if out of range
     */
    fun setCoolstepThreshold(tcoolthrs: Long) {
        require(tcoolthrs in 0..0xFFFFFL) { "tcoolthrs 0-1048575" }
        writeReg(REG_TCOOLTHRS, tcoolthrs)
    }

    /**
     * Write `COOLCONF`.
     *
     * @param semin lower threshold 0–15; 0 disables CoolStep
     * @param semax upper threshold offset 0–15
     * @param seup current up step width 0–3 (1, 2, 4, 8)
     * @param sedn current down step speed 0–3
     * @param seimin 0 for half IRUN minimum, 1 for quarter
     * @throws IllegalArgumentException if a field is out of range
     */
    fun configureCoolstep(semin: Int, semax: Int, seup: Int, sedn: Int, seimin: Int) {
        require(semin in 0..15 && semax in 0..15 && seup in 0..3 && sedn in 0..3 && seimin in 0..1) {
            "COOLCONF field out of range"
        }
        writeReg(
            REG_COOLCONF,
            semin.toLong() or (seup.toLong() shl 5) or (semax.toLong() shl 8) or (sedn.toLong() shl 13) or
                (seimin.toLong() shl 15),
        )
    }

    /** `SG_RESULT` 0–510 (higher means less load). */
    fun readStallguard(): Int = (readReg(REG_SG_RESULT) and 0x3FF).toInt()

    /**
     * `DIAG` pin level (stall or driver error).
     *
     * @throws IllegalStateException if no DIAG pin was given
     */
    fun isStalled(): Boolean = checkNotNull(diagPin) { "isStalled() requires a DIAG pin" }.isHigh()

    /**
     * Emit STEP pulses on the GPIO pin.
     *
     * @param count number of pulses; negative reverses (needs a DIR pin)
     * @param delayS high and low time of each pulse in seconds
     * @throws IllegalStateException if no STEP pin (or no DIR pin for a negative count) was given
     */
    fun step(count: Int, delayS: Double) {
        val step = checkNotNull(stepPin) { "step() requires a STEP pin" }
        check(count >= 0 || dirPin != null) { "negative count requires a DIR pin" }
        dirPin?.set(count >= 0)
        val nanos = (delayS * 1e9).toLong()
        repeat(abs(count)) {
            step.set(true)
            Thread.sleep(nanos / 1_000_000L, (nanos % 1_000_000L).toInt())
            step.set(false)
            Thread.sleep(nanos / 1_000_000L, (nanos % 1_000_000L).toInt())
        }
    }

    /** `MSCNT` 0–1023. */
    fun microstepPosition(): Int = (readReg(REG_MSCNT) and 0x3FF).toInt()

    /** `DRV_STATUS.CS_ACTUAL` 0–31. */
    fun readCurrentScale(): Int = ((readReg(REG_DRV_STATUS) shr 16) and 0x1F).toInt()

    /** Signed `MSCURACT` values in register order. */
    fun readCoilCurrents(): CoilCurrents {
        val raw = readReg(REG_MSCURACT)
        return CoilCurrents(signed(raw, 9), signed(raw shr 16, 9))
    }

    /** `TSTEP` in clock periods (20 bit). */
    fun readTstep(): Long = readReg(REG_TSTEP) and 0xFFFFFL

    /** t120..t157 as bits 0–3. */
    fun readTemperatureFlags(): Int = ((readReg(REG_DRV_STATUS) shr 8) and 0xF).toInt()

    /** Decoded `DRV_STATUS`. */
    fun readStatus(): Status {
        val s = readReg(REG_DRV_STATUS)
        return Status(
            (s and (1L shl 31)) != 0L, (s and (1L shl 30)) != 0L, ((s shr 16) and 0x1F).toInt(),
            (s and 0x01) != 0L, (s and 0x02) != 0L, (s and 0x0C) != 0L, (s and 0x30) != 0L, (s and 0xC0) != 0L,
            (s and (1L shl 8)) != 0L, (s and (1L shl 9)) != 0L, (s and (1L shl 10)) != 0L, (s and (1L shl 11)) != 0L,
        )
    }

    /** Decoded `GSTAT`; [clear] writes the flags back (write-1-to-clear). */
    fun readGlobalStatus(clear: Boolean = false): GlobalStatus {
        val g = readReg(REG_GSTAT)
        if (clear) writeReg(REG_GSTAT, 0x07)
        return GlobalStatus((g and 1) != 0L, (g and 2) != 0L, (g and 4) != 0L)
    }

    /** `IFCNT` write counter 0–255. */
    fun readIfcnt(): Int = (readReg(REG_IFCNT) and 0xFF).toInt()

    /** `IOIN` raw word, decoded pin states and `VERSION`. */
    fun readIoin(): Ioin {
        val raw = readReg(REG_IOIN)
        return Ioin(
            raw, (raw and 1L) != 0L, (raw and 4L) != 0L, (raw and 8L) != 0L, (raw and 0x10L) != 0L,
            (raw and 0x40L) != 0L, (raw and 0x80L) != 0L, (raw and 0x100L) != 0L, (raw and 0x200L) != 0L,
            ((raw shr 24) and 0xFF).toInt(),
        )
    }

    /** `OTP_READ` 24-bit word. */
    fun readOtp(): Long = readReg(REG_OTP_READ) and 0xFFFFFFL

    /** `PWM_SCALE` / `PWM_AUTO` results. */
    fun readPwmAuto(): PwmAuto {
        val scale = readReg(REG_PWM_SCALE)
        val auto = readReg(REG_PWM_AUTO)
        return PwmAuto((scale and 0xFF).toInt(), signed(scale shr 16, 9), (auto and 0xFF).toInt(), ((auto shr 16) and 0xFF).toInt())
    }
}
