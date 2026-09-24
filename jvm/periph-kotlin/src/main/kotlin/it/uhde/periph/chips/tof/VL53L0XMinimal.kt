package it.uhde.periph.chips.tof

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * VL53L0X Time-of-Flight laser-ranging sensor (STMicroelectronics) —
 * minimal interface.
 *
 * 940 nm VCSEL emitter, SPAD receiving array and an embedded ranging
 * microcontroller measuring absolute distance up to ~2 m, largely
 * independent of target reflectance. The datasheet has no register map:
 * registers, the tuning table and the init/calibration sequences follow
 * ST's STSW-IMG005 API (the same derivation as Pololu's VL53L0X library).
 * Multi-byte registers are big-endian.
 *
 * Construction runs the full initialization sequence (XSHUT high if the
 * connection has an `enPin`, 1.2 ms boot wait, model ID check, 2V8 I/O mode,
 * reference SPADs, default tuning, GPIO1 = new sample ready active low,
 * ~33 ms timing budget, VHV + phase reference calibration) and leaves the
 * chip idle. Methods are `@Synchronized`, serializing multi-register
 * sequences against the Full class's interrupt polling thread.
 *
 * Multiple sensors on one bus: all power up at 0x29. Hold every sensor's
 * XSHUT low (each connection disabled), then for each sensor in turn enable
 * its XSHUT, construct a driver on 0x29, call `setAddress(new)`, and build
 * the real driver on a connection at the new address. The new address is
 * volatile — it reverts to 0x29 on power-up or an XSHUT low pulse.
 *
 * @param connection configured I²C connection bound to the device (0x29)
 * @throws IOException on bus error, a model ID other than 0xEE, or an init poll timeout
 */
open class VL53L0XMinimal(protected val connection: Connection) {

    companion object {
        /** Power-on I²C address. */
        const val DEFAULT_ADDRESS = 0x29

        /** Expected `IDENTIFICATION_MODEL_ID`. */
        const val MODEL_ID = 0xEE

        /** Device range status meaning "range complete — valid". */
        const val RANGE_STATUS_VALID = 11

        const val REG_SYSRANGE_START = 0x00
        const val REG_SYSTEM_SEQUENCE_CONFIG = 0x01
        const val REG_SYSTEM_INTERMEASUREMENT = 0x04
        const val REG_SYSTEM_INTERRUPT_CONFIG = 0x0A
        const val REG_SYSTEM_INTERRUPT_CLEAR = 0x0B
        const val REG_SYSTEM_THRESH_HIGH = 0x0C
        const val REG_SYSTEM_THRESH_LOW = 0x0E
        const val REG_RESULT_INTERRUPT_STATUS = 0x13
        const val REG_RESULT_RANGE_STATUS = 0x14
        const val REG_CROSSTALK_COMPENSATION = 0x20
        const val REG_PART_TO_PART_RANGE_OFFSET = 0x28
        const val REG_PHASECAL_CONFIG_TIMEOUT = 0x30
        const val REG_GLOBAL_CONFIG_VCSEL_WIDTH = 0x32
        const val REG_FINAL_MIN_COUNT_RATE_RTN = 0x44
        const val REG_MSRC_CONFIG_TIMEOUT = 0x46
        const val REG_FINAL_VALID_PHASE_LOW = 0x47
        const val REG_FINAL_VALID_PHASE_HIGH = 0x48
        const val REG_DYNAMIC_SPAD_NUM_REQ = 0x4E
        const val REG_DYNAMIC_SPAD_START_OFFSET = 0x4F
        const val REG_PRE_RANGE_VCSEL_PERIOD = 0x50
        const val REG_PRE_RANGE_TIMEOUT = 0x51
        const val REG_PRE_VALID_PHASE_LOW = 0x56
        const val REG_PRE_VALID_PHASE_HIGH = 0x57
        const val REG_MSRC_CONFIG_CONTROL = 0x60
        const val REG_FINAL_RANGE_VCSEL_PERIOD = 0x70
        const val REG_FINAL_RANGE_TIMEOUT = 0x71
        const val REG_POWER_FORCE = 0x80
        const val REG_GPIO_HV_MUX_ACTIVE_HIGH = 0x84
        const val REG_I2C_MODE = 0x88
        const val REG_VHV_PAD_EXTSUP_HV = 0x89
        const val REG_I2C_SLAVE_DEVICE_ADDRESS = 0x8A
        const val REG_STOP_VARIABLE = 0x91
        const val REG_SPAD_ENABLES_REF_0 = 0xB0
        const val REG_REF_EN_START_SELECT = 0xB6
        const val REG_MODEL_ID = 0xC0
        const val REG_REVISION_ID = 0xC2
        const val REG_OSC_CALIBRATE_VAL = 0xF8
        const val REG_PAGE_SELECT = 0xFF

        const val SEQ_TCC = 0x10
        const val SEQ_DSS = 0x08
        const val SEQ_MSRC = 0x04
        const val SEQ_PRE_RANGE = 0x40
        const val SEQ_FINAL_RANGE = 0x80
        const val SEQ_OPERATING = 0xE8

        private const val TIMEOUT_MS = 500L
        private const val MIN_TIMING_BUDGET_US = 20000

        // Timing-budget overheads, µs.
        private const val START_OVERHEAD = 1910
        private const val END_OVERHEAD = 960
        private const val MSRC_OVERHEAD = 660
        private const val TCC_OVERHEAD = 590
        private const val DSS_OVERHEAD = 690
        private const val PRE_RANGE_OVERHEAD = 660
        private const val FINAL_RANGE_OVERHEAD = 550

        // ST DefaultTuningSettings — opaque, written verbatim in this order as (reg, value) pairs.
        private val TUNING = intArrayOf(
            0xFF, 0x01, 0x00, 0x00, 0xFF, 0x00, 0x09, 0x00, 0x10, 0x00, 0x11, 0x00, 0x24, 0x01, 0x25, 0xFF, 0x75, 0x00,
            0xFF, 0x01, 0x4E, 0x2C, 0x48, 0x00, 0x30, 0x20, 0xFF, 0x00, 0x30, 0x09, 0x54, 0x00, 0x31, 0x04, 0x32, 0x03,
            0x40, 0x83, 0x46, 0x25, 0x60, 0x00, 0x27, 0x00, 0x50, 0x06, 0x51, 0x00, 0x52, 0x96, 0x56, 0x08, 0x57, 0x30,
            0x61, 0x00, 0x62, 0x00, 0x64, 0x00, 0x65, 0x00, 0x66, 0xA0, 0xFF, 0x01, 0x22, 0x32, 0x47, 0x14, 0x49, 0xFF,
            0x4A, 0x00, 0xFF, 0x00, 0x7A, 0x0A, 0x7B, 0x00, 0x78, 0x21, 0xFF, 0x01, 0x23, 0x34, 0x42, 0x00, 0x44, 0xFF,
            0x45, 0x26, 0x46, 0x05, 0x40, 0x40, 0x0E, 0x06, 0x20, 0x1A, 0x43, 0x40, 0xFF, 0x00, 0x34, 0x03, 0x35, 0x44,
            0xFF, 0x01, 0x31, 0x04, 0x4B, 0x09, 0x4C, 0x05, 0x4D, 0x04, 0xFF, 0x00, 0x44, 0x00, 0x45, 0x20, 0x47, 0x08,
            0x48, 0x28, 0x67, 0x00, 0x70, 0x04, 0x71, 0x01, 0x72, 0xFE, 0x76, 0x00, 0x77, 0x00, 0xFF, 0x01, 0x0D, 0x01,
            0xFF, 0x00, 0x80, 0x01, 0x01, 0xF8, 0xFF, 0x01, 0x8E, 0x01, 0x00, 0x01, 0xFF, 0x00, 0x80, 0x00,
        )

        internal fun decodeVcsel(reg: Int): Int = (reg + 1) shl 1
        internal fun encodeVcsel(pclks: Int): Int = (pclks shr 1) - 1
        internal fun macroPeriodNs(pclks: Int): Long = (2304L * pclks * 1655 + 500) / 1000
        internal fun mclksToUs(mclks: Long, pclks: Int): Int = ((mclks * macroPeriodNs(pclks) + 500) / 1000).toInt()
        internal fun usToMclks(us: Long, pclks: Int): Int {
            val period = macroPeriodNs(pclks)
            return ((us * 1000 + period / 2) / period).toInt()
        }
        internal fun decodeTimeout(reg: Int): Int = ((reg and 0xFF) shl (reg shr 8)) + 1
        internal fun encodeTimeout(mclks: Int): Int {
            if (mclks <= 0) return 0
            var ls = mclks - 1
            var ms = 0
            while (ls > 0xFF) {
                ls = ls shr 1
                ms++
            }
            return (ms shl 8) or (ls and 0xFF)
        }
    }

    /** Sequence-step timeouts read from the registers. */
    protected data class StepTimeouts(val msrcUs: Int, val preMclks: Int, val preUs: Int, val finalPclks: Int, val finalUs: Int)

    protected var stopVariable = 0
    protected var rangeStatus = 0
    protected var timingBudgetUs = 0
    protected val result = ByteArray(12)

    init {
        initSequence()
    }

    protected fun wr(reg: Int, value: Int) {
        connection.write(byteArrayOf(reg.toByte(), value.toByte()))
    }

    protected fun rd(reg: Int): Int = connection.writeRead(byteArrayOf(reg.toByte()), 1)[0].toInt() and 0xFF

    protected fun wr16(reg: Int, value: Int) {
        connection.write(byteArrayOf(reg.toByte(), (value shr 8).toByte(), value.toByte()))
    }

    /** Read a 16-bit big-endian register as an unsigned value (masked, no sign extension). */
    protected fun rd16(reg: Int): Int {
        val b = connection.writeRead(byteArrayOf(reg.toByte()), 2)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    protected fun wr32(reg: Int, value: Long) {
        connection.write(byteArrayOf(reg.toByte(), (value shr 24).toByte(), (value shr 16).toByte(),
            (value shr 8).toByte(), value.toByte()))
    }

    protected fun await(reg: Int, mask: Int, untilSet: Boolean, what: String) {
        val start = System.nanoTime()
        while (true) {
            if (((rd(reg) and mask) != 0) == untilSet) return
            if ((System.nanoTime() - start) / 1_000_000 > TIMEOUT_MS) {
                throw IOException("VL53L0X timeout waiting for $what")
            }
        }
    }

    private fun initSequence() {
        if (connection.enPin() != null) connection.enable()
        try {
            Thread.sleep(2)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        val model = rd(REG_MODEL_ID)
        if (model != MODEL_ID) {
            throw IOException("VL53L0X not found: expected model ID 0xEE, got 0x%02X".format(model))
        }

        // 2V8 I/O mode, standard I²C mode.
        wr(REG_VHV_PAD_EXTSUP_HV, rd(REG_VHV_PAD_EXTSUP_HV) or 0x01)
        wr(REG_I2C_MODE, 0x00)

        // Stop variable.
        wr(REG_POWER_FORCE, 0x01)
        wr(REG_PAGE_SELECT, 0x01)
        wr(REG_SYSRANGE_START, 0x00)
        stopVariable = rd(REG_STOP_VARIABLE)
        wr(REG_SYSRANGE_START, 0x01)
        wr(REG_PAGE_SELECT, 0x00)
        wr(REG_POWER_FORCE, 0x00)

        // Disable MSRC and pre-range signal-rate limit checks; 0.25 MCPS limit.
        wr(REG_MSRC_CONFIG_CONTROL, rd(REG_MSRC_CONFIG_CONTROL) or 0x12)
        wr16(REG_FINAL_MIN_COUNT_RATE_RTN, 0x0020)
        wr(REG_SYSTEM_SEQUENCE_CONFIG, 0xFF)

        val spad = spadInfo()
        val spadCount = spad and 0x7F
        val spadIsAperture = (spad and 0x80) != 0

        // Reference SPADs.
        val refMap = connection.writeRead(byteArrayOf(REG_SPAD_ENABLES_REF_0.toByte()), 6)
        wr(REG_PAGE_SELECT, 0x01)
        wr(REG_DYNAMIC_SPAD_START_OFFSET, 0x00)
        wr(REG_DYNAMIC_SPAD_NUM_REQ, 0x2C)
        wr(REG_PAGE_SELECT, 0x00)
        wr(REG_REF_EN_START_SELECT, 0xB4)
        val first = if (spadIsAperture) 12 else 0
        var enabled = 0
        val out = ByteArray(7)
        out[0] = REG_SPAD_ENABLES_REF_0.toByte()
        refMap.copyInto(out, 1, 0, 6)
        for (i in 0 until 48) {
            val bit = 1 shl (i % 8)
            val cur = out[1 + i / 8].toInt() and 0xFF
            if (i < first || enabled == spadCount) {
                out[1 + i / 8] = (cur and bit.inv()).toByte()
            } else if ((cur and bit) != 0) {
                enabled++
            }
        }
        connection.write(out)

        // Default tuning settings.
        for (i in TUNING.indices step 2) wr(TUNING[i], TUNING[i + 1])

        // GPIO1 = new sample ready, active low.
        wr(REG_SYSTEM_INTERRUPT_CONFIG, 0x04)
        wr(REG_GPIO_HV_MUX_ACTIVE_HIGH, rd(REG_GPIO_HV_MUX_ACTIVE_HIGH) and 0x10.inv())
        wr(REG_SYSTEM_INTERRUPT_CLEAR, 0x01)

        val budget = getTimingBudget()
        wr(REG_SYSTEM_SEQUENCE_CONFIG, SEQ_OPERATING)
        setTimingBudgetInternal(budget)

        refCalibration()
    }

    private fun spadInfo(): Int {
        wr(REG_POWER_FORCE, 0x01)
        wr(REG_PAGE_SELECT, 0x01)
        wr(REG_SYSRANGE_START, 0x00)
        wr(REG_PAGE_SELECT, 0x06)
        wr(0x83, rd(0x83) or 0x04)
        wr(REG_PAGE_SELECT, 0x07)
        wr(0x81, 0x01)
        wr(REG_POWER_FORCE, 0x01)
        wr(0x94, 0x6B)
        wr(0x83, 0x00)
        val timeout = runCatching { await(0x83, 0xFF, true, "SPAD info") }.exceptionOrNull()
        wr(0x83, 0x01)
        val tmp = rd(0x92)
        wr(0x81, 0x00)
        wr(REG_PAGE_SELECT, 0x06)
        wr(0x83, rd(0x83) and 0x04.inv())
        wr(REG_PAGE_SELECT, 0x01)
        wr(REG_SYSRANGE_START, 0x01)
        wr(REG_PAGE_SELECT, 0x00)
        wr(REG_POWER_FORCE, 0x00)
        if (timeout != null) throw timeout
        return tmp
    }

    protected fun singleRefCalibration(vhvInit: Int) {
        wr(REG_SYSRANGE_START, 0x01 or vhvInit)
        val timeout = runCatching { await(REG_RESULT_INTERRUPT_STATUS, 0x07, true, "reference calibration") }
            .exceptionOrNull()
        wr(REG_SYSTEM_INTERRUPT_CLEAR, 0x01)
        wr(REG_SYSRANGE_START, 0x00)
        if (timeout != null) throw timeout
    }

    protected fun refCalibration() {
        val seq = rd(REG_SYSTEM_SEQUENCE_CONFIG)
        try {
            wr(REG_SYSTEM_SEQUENCE_CONFIG, 0x01)
            singleRefCalibration(0x40)
            wr(REG_SYSTEM_SEQUENCE_CONFIG, 0x02)
            singleRefCalibration(0x00)
        } finally {
            wr(REG_SYSTEM_SEQUENCE_CONFIG, seq)
        }
    }

    protected fun stepTimeouts(enables: Int): StepTimeouts {
        val prePclks = decodeVcsel(rd(REG_PRE_RANGE_VCSEL_PERIOD))
        val msrcUs = mclksToUs((rd(REG_MSRC_CONFIG_TIMEOUT) + 1).toLong(), prePclks)
        val preMclks = decodeTimeout(rd16(REG_PRE_RANGE_TIMEOUT))
        val preUs = mclksToUs(preMclks.toLong(), prePclks)
        val finalPclks = decodeVcsel(rd(REG_FINAL_RANGE_VCSEL_PERIOD))
        var finalMclks = decodeTimeout(rd16(REG_FINAL_RANGE_TIMEOUT))
        if ((enables and SEQ_PRE_RANGE) != 0) finalMclks -= preMclks
        return StepTimeouts(msrcUs, preMclks, preUs, finalPclks, mclksToUs(finalMclks.toLong(), finalPclks))
    }

    private fun fixedOverheadUs(enables: Int, t: StepTimeouts): Int {
        var budget = START_OVERHEAD + END_OVERHEAD
        if ((enables and SEQ_TCC) != 0) budget += t.msrcUs + TCC_OVERHEAD
        if ((enables and SEQ_DSS) != 0) {
            budget += 2 * (t.msrcUs + DSS_OVERHEAD)
        } else if ((enables and SEQ_MSRC) != 0) {
            budget += t.msrcUs + MSRC_OVERHEAD
        }
        if ((enables and SEQ_PRE_RANGE) != 0) budget += t.preUs + PRE_RANGE_OVERHEAD
        return budget
    }

    protected fun getTimingBudget(): Int {
        val enables = rd(REG_SYSTEM_SEQUENCE_CONFIG)
        val t = stepTimeouts(enables)
        var budget = fixedOverheadUs(enables, t)
        if ((enables and SEQ_FINAL_RANGE) != 0) budget += t.finalUs + FINAL_RANGE_OVERHEAD
        return budget
    }

    protected fun setTimingBudgetInternal(budgetUs: Int) {
        require(budgetUs >= MIN_TIMING_BUDGET_US) { "timing budget must be >= $MIN_TIMING_BUDGET_US us" }
        val enables = rd(REG_SYSTEM_SEQUENCE_CONFIG)
        val t = stepTimeouts(enables)
        var used = fixedOverheadUs(enables, t)
        if ((enables and SEQ_FINAL_RANGE) != 0) {
            used += FINAL_RANGE_OVERHEAD
            require(used <= budgetUs) { "timing budget $budgetUs us is below the enabled steps overhead $used us" }
            var finalMclks = usToMclks((budgetUs - used).toLong(), t.finalPclks)
            if ((enables and SEQ_PRE_RANGE) != 0) finalMclks += t.preMclks
            wr16(REG_FINAL_RANGE_TIMEOUT, encodeTimeout(finalMclks))
        }
        timingBudgetUs = budgetUs
    }

    protected fun stopVariablePreamble() {
        wr(REG_POWER_FORCE, 0x01)
        wr(REG_PAGE_SELECT, 0x01)
        wr(REG_SYSRANGE_START, 0x00)
        wr(REG_STOP_VARIABLE, stopVariable)
        wr(REG_SYSRANGE_START, 0x01)
        wr(REG_PAGE_SELECT, 0x00)
        wr(REG_POWER_FORCE, 0x00)
    }

    protected fun readResult() {
        val b = connection.writeRead(byteArrayOf(REG_RESULT_RANGE_STATUS.toByte()), 12)
        wr(REG_SYSTEM_INTERRUPT_CLEAR, 0x01)
        b.copyInto(result, 0, 0, 12)
        rangeStatus = (result[0].toInt() and 0x78) shr 3
    }

    /** Unsigned big-endian word from the result block (masked, no sign extension). */
    protected fun resultWord(i: Int): Int = ((result[i].toInt() and 0xFF) shl 8) or (result[i + 1].toInt() and 0xFF)

    protected fun waitAndRead(): Int {
        await(REG_RESULT_INTERRUPT_STATUS, 0x07, true, "data ready")
        readResult()
        return resultWord(10)
    }

    /**
     * Take one single-shot measurement. Blocks for about one timing budget
     * (33 ms by default). Returns the raw range even when the measurement is
     * not valid — typically 8190 or 8191 with no target in range; check
     * [rangeValid].
     *
     * @return distance in mm
     * @throws IOException on bus error, or if the measurement does not start
     *   or complete within 500 ms
     */
    @Synchronized
    fun distance(): Int {
        stopVariablePreamble()
        wr(REG_SYSRANGE_START, 0x01)
        await(REG_SYSRANGE_START, 0x01, false, "ranging start")
        return waitAndRead()
    }

    /**
     * Report whether the most recent measurement was valid.
     *
     * @return `true` iff the device range status was 11 (range complete)
     */
    fun rangeValid(): Boolean = rangeStatus == RANGE_STATUS_VALID
}
