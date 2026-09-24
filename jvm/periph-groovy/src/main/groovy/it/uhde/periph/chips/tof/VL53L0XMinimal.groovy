package it.uhde.periph.chips.tof

import groovy.transform.CompileStatic

import it.uhde.periph.connection.Connection

import java.io.IOException

/**
 * VL53L0X Time-of-Flight laser-ranging sensor (STMicroelectronics) — minimal interface.
 *
 * <p>940&nbsp;nm VCSEL emitter, SPAD receiving array and an embedded ranging microcontroller
 * measuring absolute distance up to ~2&nbsp;m, largely independent of target reflectance. The
 * datasheet has no register map: registers, the tuning table and the init/calibration sequences
 * follow ST's STSW-IMG005 API (the same derivation as Pololu's VL53L0X library). Multi-byte
 * registers are big-endian.
 *
 * <p>The constructor runs the full initialization sequence (XSHUT high if the connection has an
 * {@code enPin}, 1.2&nbsp;ms boot wait, model ID check, 2V8 I/O mode, reference SPADs, default
 * tuning, GPIO1 = new sample ready active low, ~33&nbsp;ms timing budget, VHV + phase reference
 * calibration) and leaves the chip idle. {@link #distance()} then takes one single-shot
 * measurement per call.
 *
 * <p>Multiple sensors on one bus: all power up at {@code 0x29}. Hold every sensor's XSHUT low
 * (each connection disabled), then for each sensor in turn enable its XSHUT, construct a driver on
 * {@code 0x29}, call {@code setAddress(new)}, and build the real driver on a connection at the
 * new address. The new address is volatile — it reverts to {@code 0x29} on power-up or an XSHUT
 * low pulse.
 *
 * <p>Register access, polling, boot wait, interrupt delivery and re-addressing come from the
 * shared {@link VL53Base} ({@code specs/tof/_vl53_base.md}).
 */
@CompileStatic
class VL53L0XMinimal extends VL53Base {

    /** Power-on 7-bit I²C address. */
    public static final int DEFAULT_ADDRESS = 0x29

    /** Expected {@code IDENTIFICATION_MODEL_ID}. */
    public static final int MODEL_ID = 0xEE

    /** Device range status meaning "range complete — valid". */
    public static final int RANGE_STATUS_VALID = 11

    protected static final int REG_SYSRANGE_START            = 0x00
    protected static final int REG_SYSTEM_SEQUENCE_CONFIG    = 0x01
    protected static final int REG_SYSTEM_INTERMEASUREMENT   = 0x04
    protected static final int REG_SYSTEM_INTERRUPT_CONFIG   = 0x0A
    protected static final int REG_SYSTEM_INTERRUPT_CLEAR    = 0x0B
    protected static final int REG_SYSTEM_THRESH_HIGH        = 0x0C
    protected static final int REG_SYSTEM_THRESH_LOW         = 0x0E
    protected static final int REG_RESULT_INTERRUPT_STATUS   = 0x13
    protected static final int REG_RESULT_RANGE_STATUS       = 0x14
    protected static final int REG_CROSSTALK_COMPENSATION    = 0x20
    protected static final int REG_PART_TO_PART_RANGE_OFFSET = 0x28
    protected static final int REG_PHASECAL_CONFIG_TIMEOUT   = 0x30
    protected static final int REG_GLOBAL_CONFIG_VCSEL_WIDTH = 0x32
    protected static final int REG_FINAL_MIN_COUNT_RATE_RTN  = 0x44
    protected static final int REG_MSRC_CONFIG_TIMEOUT       = 0x46
    protected static final int REG_FINAL_VALID_PHASE_LOW     = 0x47
    protected static final int REG_FINAL_VALID_PHASE_HIGH    = 0x48
    protected static final int REG_DYNAMIC_SPAD_NUM_REQ      = 0x4E
    protected static final int REG_DYNAMIC_SPAD_START_OFFSET = 0x4F
    protected static final int REG_PRE_RANGE_VCSEL_PERIOD    = 0x50
    protected static final int REG_PRE_RANGE_TIMEOUT         = 0x51
    protected static final int REG_PRE_VALID_PHASE_LOW       = 0x56
    protected static final int REG_PRE_VALID_PHASE_HIGH      = 0x57
    protected static final int REG_MSRC_CONFIG_CONTROL       = 0x60
    protected static final int REG_FINAL_RANGE_VCSEL_PERIOD  = 0x70
    protected static final int REG_FINAL_RANGE_TIMEOUT       = 0x71
    protected static final int REG_POWER_FORCE               = 0x80
    protected static final int REG_GPIO_HV_MUX_ACTIVE_HIGH   = 0x84
    protected static final int REG_I2C_MODE                  = 0x88
    protected static final int REG_VHV_PAD_EXTSUP_HV         = 0x89
    protected static final int REG_I2C_SLAVE_DEVICE_ADDRESS  = 0x8A
    protected static final int REG_STOP_VARIABLE             = 0x91
    protected static final int REG_SPAD_ENABLES_REF_0        = 0xB0
    protected static final int REG_REF_EN_START_SELECT       = 0xB6
    protected static final int REG_MODEL_ID                  = 0xC0
    protected static final int REG_REVISION_ID               = 0xC2
    protected static final int REG_OSC_CALIBRATE_VAL         = 0xF8
    protected static final int REG_PAGE_SELECT               = 0xFF

    protected static final int SEQ_TCC         = 0x10
    protected static final int SEQ_DSS         = 0x08
    protected static final int SEQ_MSRC        = 0x04
    protected static final int SEQ_PRE_RANGE   = 0x40
    protected static final int SEQ_FINAL_RANGE = 0x80
    protected static final int SEQ_OPERATING   = 0xE8

    private static final int MIN_TIMING_BUDGET_US = 20000

    // Timing-budget overheads, µs.
    private static final int START_OVERHEAD       = 1910
    private static final int END_OVERHEAD         = 960
    private static final int MSRC_OVERHEAD        = 660
    private static final int TCC_OVERHEAD         = 590
    private static final int DSS_OVERHEAD         = 690
    private static final int PRE_RANGE_OVERHEAD   = 660
    private static final int FINAL_RANGE_OVERHEAD = 550

    // ST DefaultTuningSettings — opaque, written verbatim in this order as (reg, value) pairs.
    private static final int[] TUNING = [
        0xFF, 0x01, 0x00, 0x00, 0xFF, 0x00, 0x09, 0x00, 0x10, 0x00, 0x11, 0x00, 0x24, 0x01, 0x25, 0xFF, 0x75, 0x00,
        0xFF, 0x01, 0x4E, 0x2C, 0x48, 0x00, 0x30, 0x20, 0xFF, 0x00, 0x30, 0x09, 0x54, 0x00, 0x31, 0x04, 0x32, 0x03,
        0x40, 0x83, 0x46, 0x25, 0x60, 0x00, 0x27, 0x00, 0x50, 0x06, 0x51, 0x00, 0x52, 0x96, 0x56, 0x08, 0x57, 0x30,
        0x61, 0x00, 0x62, 0x00, 0x64, 0x00, 0x65, 0x00, 0x66, 0xA0, 0xFF, 0x01, 0x22, 0x32, 0x47, 0x14, 0x49, 0xFF,
        0x4A, 0x00, 0xFF, 0x00, 0x7A, 0x0A, 0x7B, 0x00, 0x78, 0x21, 0xFF, 0x01, 0x23, 0x34, 0x42, 0x00, 0x44, 0xFF,
        0x45, 0x26, 0x46, 0x05, 0x40, 0x40, 0x0E, 0x06, 0x20, 0x1A, 0x43, 0x40, 0xFF, 0x00, 0x34, 0x03, 0x35, 0x44,
        0xFF, 0x01, 0x31, 0x04, 0x4B, 0x09, 0x4C, 0x05, 0x4D, 0x04, 0xFF, 0x00, 0x44, 0x00, 0x45, 0x20, 0x47, 0x08,
        0x48, 0x28, 0x67, 0x00, 0x70, 0x04, 0x71, 0x01, 0x72, 0xFE, 0x76, 0x00, 0x77, 0x00, 0xFF, 0x01, 0x0D, 0x01,
        0xFF, 0x00, 0x80, 0x01, 0x01, 0xF8, 0xFF, 0x01, 0x8E, 0x01, 0x00, 0x01, 0xFF, 0x00, 0x80, 0x00,
    ] as int[]

    /** Sequence-step timeouts read from the registers. */
    protected static class StepTimeouts {
        final int msrcUs
        final int preMclks
        final int preUs
        final int finalPclks
        final int finalUs

        StepTimeouts(int msrcUs, int preMclks, int preUs, int finalPclks, int finalUs) {
            this.msrcUs = msrcUs
            this.preMclks = preMclks
            this.preUs = preUs
            this.finalPclks = finalPclks
            this.finalUs = finalUs
        }
    }

    protected int stopVariable
    protected int rangeStatus
    protected int timingBudgetUs
    protected final byte[] result = new byte[12]

    /**
     * Construct the driver and run the full initialization sequence. Methods are
     * {@code synchronized}, serializing multi-register sequences against the Full class's
     * interrupt polling thread.
     *
     * @param connection configured I²C connection pointing at the device (0x29)
     * @throws IOException on bus error, a model ID other than 0xEE, or an init poll timeout
     */
    VL53L0XMinimal(Connection connection) throws IOException {
        super(connection, 1, "VL53L0X")
        init()
    }

    protected void wr(int reg, int value) throws IOException {
        write8(reg, value)
    }

    protected int rd(int reg) throws IOException {
        return read8(reg)
    }

    protected void wr16(int reg, int value) throws IOException {
        write16(reg, value)
    }

    protected int rd16(int reg) throws IOException {
        return read16(reg)
    }

    protected void wr32(int reg, long value) throws IOException {
        write32(reg, value)
    }

    protected void await(int reg, int mask, boolean untilSet, String what) throws IOException {
        waitUntil({ -> ((rd(reg) & mask) != 0) == untilSet }, what)
    }

    protected static int decodeVcsel(int reg) {
        return (reg + 1) << 1
    }

    protected static int encodeVcsel(int pclks) {
        return (pclks >> 1) - 1
    }

    protected static long macroPeriodNs(int pclks) {
        return (2304L * pclks * 1655 + 500).intdiv(1000) as long
    }

    protected static int mclksToUs(long mclks, int pclks) {
        return (int) ((mclks * macroPeriodNs(pclks) + 500).intdiv(1000))
    }

    protected static int usToMclks(long us, int pclks) {
        long period = macroPeriodNs(pclks)
        return (int) ((us * 1000 + period.intdiv(2)).intdiv(period))
    }

    protected static int decodeTimeout(int reg) {
        return ((reg & 0xFF) << (reg >> 8)) + 1
    }

    protected static int encodeTimeout(int mclks) {
        if (mclks <= 0) return 0
        int ls = mclks - 1
        int ms = 0
        while (ls > 0xFF) {
            ls >>= 1
            ms++
        }
        return (ms << 8) | (ls & 0xFF)
    }

    private void init() throws IOException {
        bootWait()

        int model = rd(REG_MODEL_ID)
        if (model != MODEL_ID) {
            throw new IOException(String.format("VL53L0X not found: expected model ID 0x%02X, got 0x%02X",
                    MODEL_ID, model))
        }

        // 2V8 I/O mode, standard I²C mode.
        wr(REG_VHV_PAD_EXTSUP_HV, rd(REG_VHV_PAD_EXTSUP_HV) | 0x01)
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
        wr(REG_MSRC_CONFIG_CONTROL, rd(REG_MSRC_CONFIG_CONTROL) | 0x12)
        wr16(REG_FINAL_MIN_COUNT_RATE_RTN, 0x0020)
        wr(REG_SYSTEM_SEQUENCE_CONFIG, 0xFF)

        int spad = spadInfo()
        int spadCount = spad & 0x7F
        boolean spadIsAperture = (spad & 0x80) != 0

        // Reference SPADs.
        byte[] refMap = readBlock(REG_SPAD_ENABLES_REF_0, 6)
        wr(REG_PAGE_SELECT, 0x01)
        wr(REG_DYNAMIC_SPAD_START_OFFSET, 0x00)
        wr(REG_DYNAMIC_SPAD_NUM_REQ, 0x2C)
        wr(REG_PAGE_SELECT, 0x00)
        wr(REG_REF_EN_START_SELECT, 0xB4)
        int first = spadIsAperture ? 12 : 0
        int enabled = 0
        byte[] out = refMap.clone()
        for (int i = 0; i < 48; i++) {
            int bit = 1 << (i % 8)
            if (i < first || enabled == spadCount) {
                out[i.intdiv(8)] = (byte) (out[i.intdiv(8)] & ~bit)
            } else if ((out[i.intdiv(8)] & bit) != 0) {
                enabled++
            }
        }
        writeBlock(REG_SPAD_ENABLES_REF_0, out)

        // Default tuning settings.
        for (int i = 0; i < TUNING.length; i += 2) wr(TUNING[i], TUNING[i + 1])

        // GPIO1 = new sample ready, active low.
        wr(REG_SYSTEM_INTERRUPT_CONFIG, 0x04)
        wr(REG_GPIO_HV_MUX_ACTIVE_HIGH, rd(REG_GPIO_HV_MUX_ACTIVE_HIGH) & ~0x10)
        wr(REG_SYSTEM_INTERRUPT_CLEAR, 0x01)

        int budget = getTimingBudget()
        wr(REG_SYSTEM_SEQUENCE_CONFIG, SEQ_OPERATING)
        setTimingBudgetInternal(budget)

        refCalibration()
    }

    private int spadInfo() throws IOException {
        wr(REG_POWER_FORCE, 0x01)
        wr(REG_PAGE_SELECT, 0x01)
        wr(REG_SYSRANGE_START, 0x00)
        wr(REG_PAGE_SELECT, 0x06)
        wr(0x83, rd(0x83) | 0x04)
        wr(REG_PAGE_SELECT, 0x07)
        wr(0x81, 0x01)
        wr(REG_POWER_FORCE, 0x01)
        wr(0x94, 0x6B)
        wr(0x83, 0x00)
        IOException timeout = null
        try {
            await(0x83, 0xFF, true, "SPAD info")
        } catch (IOException e) {
            timeout = e
        }
        wr(0x83, 0x01)
        int tmp = rd(0x92)
        wr(0x81, 0x00)
        wr(REG_PAGE_SELECT, 0x06)
        wr(0x83, rd(0x83) & ~0x04)
        wr(REG_PAGE_SELECT, 0x01)
        wr(REG_SYSRANGE_START, 0x01)
        wr(REG_PAGE_SELECT, 0x00)
        wr(REG_POWER_FORCE, 0x00)
        if (timeout != null) throw timeout
        return tmp
    }

    protected void singleRefCalibration(int vhvInit) throws IOException {
        wr(REG_SYSRANGE_START, 0x01 | vhvInit)
        IOException timeout = null
        try {
            await(REG_RESULT_INTERRUPT_STATUS, 0x07, true, "reference calibration")
        } catch (IOException e) {
            timeout = e
        }
        wr(REG_SYSTEM_INTERRUPT_CLEAR, 0x01)
        wr(REG_SYSRANGE_START, 0x00)
        if (timeout != null) throw timeout
    }

    protected void refCalibration() throws IOException {
        int seq = rd(REG_SYSTEM_SEQUENCE_CONFIG)
        try {
            wr(REG_SYSTEM_SEQUENCE_CONFIG, 0x01)
            singleRefCalibration(0x40)
            wr(REG_SYSTEM_SEQUENCE_CONFIG, 0x02)
            singleRefCalibration(0x00)
        } finally {
            wr(REG_SYSTEM_SEQUENCE_CONFIG, seq)
        }
    }

    protected StepTimeouts stepTimeouts(int enables) throws IOException {
        int prePclks = decodeVcsel(rd(REG_PRE_RANGE_VCSEL_PERIOD))
        int msrcUs = mclksToUs(rd(REG_MSRC_CONFIG_TIMEOUT) + 1, prePclks)
        int preMclks = decodeTimeout(rd16(REG_PRE_RANGE_TIMEOUT))
        int preUs = mclksToUs(preMclks, prePclks)
        int finalPclks = decodeVcsel(rd(REG_FINAL_RANGE_VCSEL_PERIOD))
        int finalMclks = decodeTimeout(rd16(REG_FINAL_RANGE_TIMEOUT))
        if ((enables & SEQ_PRE_RANGE) != 0) finalMclks -= preMclks
        return new StepTimeouts(msrcUs, preMclks, preUs, finalPclks, mclksToUs(finalMclks, finalPclks))
    }

    private static int fixedOverheadUs(int enables, StepTimeouts t) {
        int budget = START_OVERHEAD + END_OVERHEAD
        if ((enables & SEQ_TCC) != 0) budget += t.msrcUs + TCC_OVERHEAD
        if ((enables & SEQ_DSS) != 0) {
            budget += 2 * (t.msrcUs + DSS_OVERHEAD)
        } else if ((enables & SEQ_MSRC) != 0) {
            budget += t.msrcUs + MSRC_OVERHEAD
        }
        if ((enables & SEQ_PRE_RANGE) != 0) budget += t.preUs + PRE_RANGE_OVERHEAD
        return budget
    }

    protected int getTimingBudget() throws IOException {
        int enables = rd(REG_SYSTEM_SEQUENCE_CONFIG)
        StepTimeouts t = stepTimeouts(enables)
        int budget = fixedOverheadUs(enables, t)
        if ((enables & SEQ_FINAL_RANGE) != 0) budget += t.finalUs + FINAL_RANGE_OVERHEAD
        return budget
    }

    protected void setTimingBudgetInternal(int budgetUs) throws IOException {
        if (budgetUs < MIN_TIMING_BUDGET_US) {
            throw new IllegalArgumentException("timing budget must be >= " + MIN_TIMING_BUDGET_US + " us")
        }
        int enables = rd(REG_SYSTEM_SEQUENCE_CONFIG)
        StepTimeouts t = stepTimeouts(enables)
        int used = fixedOverheadUs(enables, t)
        if ((enables & SEQ_FINAL_RANGE) != 0) {
            used += FINAL_RANGE_OVERHEAD
            if (used > budgetUs) {
                throw new IllegalArgumentException("timing budget " + budgetUs
                        + " us is below the enabled steps overhead " + used + " us")
            }
            int finalMclks = usToMclks(budgetUs - used, t.finalPclks)
            if ((enables & SEQ_PRE_RANGE) != 0) finalMclks += t.preMclks
            wr16(REG_FINAL_RANGE_TIMEOUT, encodeTimeout(finalMclks))
        }
        timingBudgetUs = budgetUs
    }

    protected void stopVariablePreamble() throws IOException {
        wr(REG_POWER_FORCE, 0x01)
        wr(REG_PAGE_SELECT, 0x01)
        wr(REG_SYSRANGE_START, 0x00)
        wr(REG_STOP_VARIABLE, stopVariable)
        wr(REG_SYSRANGE_START, 0x01)
        wr(REG_PAGE_SELECT, 0x00)
        wr(REG_POWER_FORCE, 0x00)
    }

    protected void readResult() throws IOException {
        byte[] b = readBlock(REG_RESULT_RANGE_STATUS, 12)
        wr(REG_SYSTEM_INTERRUPT_CLEAR, 0x01)
        System.arraycopy(b, 0, result, 0, 12)
        rangeStatus = (result[0] & 0x78) >> 3
    }

    protected int resultWord(int i) {
        return ((result[i] & 0xFF) << 8) | (result[i + 1] & 0xFF)
    }

    protected int waitAndRead() throws IOException {
        await(REG_RESULT_INTERRUPT_STATUS, 0x07, true, "data ready")
        readResult()
        return resultWord(10)
    }

    /**
     * Take one single-shot measurement. Blocks for about one timing budget (33&nbsp;ms by default).
     * Returns the raw range even when the measurement is not valid — typically 8190 or 8191 with no
     * target in range; check {@link #rangeValid()}.
     *
     * @return distance in mm
     * @throws IOException on bus error, or if the measurement does not start or complete within
     *                     500&nbsp;ms
     */
    public synchronized int distance() throws IOException {
        stopVariablePreamble()
        wr(REG_SYSRANGE_START, 0x01)
        await(REG_SYSRANGE_START, 0x01, false, "ranging start")
        return waitAndRead()
    }

    /**
     * Report whether the most recent measurement was valid.
     *
     * @return {@code true} iff the device range status was 11 (range complete)
     */
    public boolean rangeValid() {
        return rangeStatus == RANGE_STATUS_VALID
    }

    @Override
    protected synchronized int pollInterruptStatus() throws IOException {
        int status = rd(REG_RESULT_INTERRUPT_STATUS) & 0x07
        if (status != 0) wr(REG_SYSTEM_INTERRUPT_CLEAR, 0x01)
        return status
    }
}
