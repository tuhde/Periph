package it.uhde.periph.chips.tof

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * VL53L1X long-distance Time-of-Flight laser-ranging sensor
 * (STMicroelectronics) — minimal interface.
 *
 * 940 nm VCSEL emitter, 16×16 SPAD receiving array behind a lens and an
 * embedded ranging microcontroller measuring absolute distance up to 4 m at
 * up to 50 Hz. Two distance modes (short ~1.3 m, robust in sunlight; long
 * ~3.6–4 m in the dark), a 15–500 ms timing budget and a programmable region
 * of interest (4×4 to 16×16 SPADs). The datasheet has no register map:
 * registers, the default configuration block and all sequences follow ST's
 * Ultra Lite Driver (STSW-IMG009). Registers use a 16-bit index; multi-byte
 * registers are big-endian.
 *
 * Pin-to-pin compatible with the VL53L0X and shares its Java [VL53Base]
 * (register access, polling, boot wait, interrupt delivery, re-addressing)
 * and public API shape.
 *
 * Construction runs the full initialization sequence (XSHUT high if the
 * connection has an `enPin`, boot wait, firmware boot poll, sensor ID check,
 * ULD default configuration, 2V8 I/O mode, GPIO1 active low, settling
 * ranging) and leaves the chip idle in long distance mode with a 100 ms
 * timing budget. Methods are `@Synchronized`, serializing multi-register
 * sequences against the Full class's interrupt polling thread.
 *
 * Multiple sensors on one bus: all VL53L0X/VL53L1X sensors power up at 0x29.
 * Hold every sensor's XSHUT low (each connection disabled), then for each
 * sensor in turn enable its XSHUT, construct a driver on 0x29, call
 * `setAddress(new)`, and build the real driver on a connection at the new
 * address. The new address is volatile.
 *
 * @param connection configured I²C connection bound to the device (0x29)
 * @throws IOException on bus error, a sensor ID other than 0xEACC, or an init poll timeout
 */
open class VL53L1XMinimal(connection: Connection) : VL53Base(connection, 2, "VL53L1X") {

    companion object {
        /** Power-on I²C address. */
        const val DEFAULT_ADDRESS = 0x29

        /** Expected `IDENTIFICATION__MODEL_ID` + `MODULE_TYPE` word. */
        const val SENSOR_ID = 0xEACC

        /** `IDENTIFICATION__MODEL_ID`. */
        const val MODEL_ID = 0xEA

        /** `IDENTIFICATION__MODULE_TYPE`. */
        const val MODULE_TYPE = 0xCC

        /** Mapped range status meaning "range valid". */
        const val RANGE_STATUS_VALID = 0

        const val REG_I2C_SLAVE_DEVICE_ADDRESS = 0x0001
        const val REG_VHV_CONFIG_LOOP_BOUND = 0x0008
        const val REG_VHV_INIT = 0x000B
        const val REG_XTALK_PLANE_OFFSET = 0x0016
        const val REG_XTALK_X_GRADIENT = 0x0018
        const val REG_XTALK_Y_GRADIENT = 0x001A
        const val REG_PART_TO_PART_OFFSET = 0x001E
        const val REG_MM_INNER_OFFSET = 0x0020
        const val REG_MM_OUTER_OFFSET = 0x0022
        const val REG_PAD_I2C_HV_EXTSUP = 0x002E
        const val REG_GPIO_EXTSUP_HV = 0x002F
        const val REG_GPIO_HV_MUX_CTRL = 0x0030
        const val REG_GPIO_TIO_HV_STATUS = 0x0031
        const val REG_INTERRUPT_CONFIG_GPIO = 0x0046
        const val REG_PHASECAL_TIMEOUT = 0x004B
        const val REG_RANGE_TIMEOUT_A = 0x005E
        const val REG_RANGE_VCSEL_PERIOD_A = 0x0060
        const val REG_RANGE_TIMEOUT_B = 0x0061
        const val REG_RANGE_VCSEL_PERIOD_B = 0x0063
        const val REG_SIGMA_THRESH = 0x0064
        const val REG_MIN_COUNT_RATE_RTN_LIMIT = 0x0066
        const val REG_RANGE_VALID_PHASE_HIGH = 0x0069
        const val REG_INTERMEASUREMENT_PERIOD = 0x006C
        const val REG_THRESH_HIGH = 0x0072
        const val REG_THRESH_LOW = 0x0074
        const val REG_SD_WOI_SD0 = 0x0078
        const val REG_SD_INITIAL_PHASE_SD0 = 0x007A
        const val REG_ROI_CENTRE_SPAD = 0x007F
        const val REG_ROI_XY_SIZE = 0x0080
        const val REG_INTERRUPT_CLEAR = 0x0086
        const val REG_MODE_START = 0x0087
        const val REG_RESULT_RANGE_STATUS = 0x0089
        const val REG_OSC_CALIBRATE_VAL = 0x00DE
        const val REG_FIRMWARE_SYSTEM_STATUS = 0x00E5
        const val REG_MODEL_ID = 0x010F
        const val REG_MODULE_TYPE = 0x0110
        const val REG_REVISION_ID = 0x0111
        const val REG_MODE_ROI_CENTRE_SPAD = 0x013E

        private const val DEFAULT_CONFIG_START = 0x002D

        /** ULD `VL51L1X_DEFAULT_CONFIGURATION` — opaque, written verbatim to 0x002D..0x0087. */
        private val DEFAULT_CONFIGURATION = intArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x02, 0x00, 0x02, 0x08, 0x00, 0x08, 0x10, 0x01, 0x01, 0x00, 0x00, 0x00,
            0x00, 0xFF, 0x00, 0x0F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20, 0x0B, 0x00, 0x00, 0x02, 0x0A, 0x21,
            0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x00, 0xC8, 0x00, 0x00, 0x38, 0xFF, 0x01, 0x00, 0x08, 0x00,
            0x00, 0x01, 0xCC, 0x0F, 0x01, 0xF1, 0x0D, 0x01, 0x68, 0x00, 0x80, 0x08, 0xB8, 0x00, 0x00, 0x00,
            0x00, 0x0F, 0x89, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x0F, 0x0D, 0x0E, 0x0E, 0x00,
            0x00, 0x02, 0xC7, 0xFF, 0x9B, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
        )

        /** ULD `status_rtn`: raw RESULT__RANGE_STATUS (bits 4:0) → mapped range status. */
        private val STATUS_MAP = intArrayOf(
            255, 255, 255, 5, 2, 4, 1, 7, 3, 0, 255, 255, 9, 13, 255, 255, 255, 255, 10, 6, 255, 255, 11, 12,
        )
    }

    protected var rangeStatus = 255
    protected val result = ByteArray(17)

    init {
        initSequence()
    }

    private fun initSequence() {
        bootWait()
        waitUntil({ (read8(REG_FIRMWARE_SYSTEM_STATUS) and 0x01) != 0 }, "boot")

        val sensorId = read16(REG_MODEL_ID)
        if (sensorId != SENSOR_ID) {
            throw IOException("VL53L1X not found: expected sensor ID 0xEACC, got 0x%04X".format(sensorId))
        }

        for (i in DEFAULT_CONFIGURATION.indices) write8(DEFAULT_CONFIG_START + i, DEFAULT_CONFIGURATION[i])

        // 2V8 I/O mode for I²C and GPIO1 pads; GPIO1 active low.
        write8(REG_PAD_I2C_HV_EXTSUP, 0x01)
        write8(REG_GPIO_EXTSUP_HV, 0x01)
        write8(REG_GPIO_HV_MUX_CTRL, 0x11)

        // Settling ranging (ULD SensorInit), then two-bound VHV from the previous temperature.
        write8(REG_MODE_START, 0x40)
        waitUntil({ isDataReady() }, "data ready")
        write8(REG_INTERRUPT_CLEAR, 0x01)
        write8(REG_MODE_START, 0x00)
        write8(REG_VHV_CONFIG_LOOP_BOUND, 0x09)
        write8(REG_VHV_INIT, 0x00)
    }

    /** `true` if GPIO1 is asserted (active low: bit 0 of GPIO__TIO_HV_STATUS is 0). */
    protected fun isDataReady(): Boolean = (read8(REG_GPIO_TIO_HV_STATUS) and 0x01) == 0

    protected fun readResult() {
        val b = readBlock(REG_RESULT_RANGE_STATUS, 17)
        write8(REG_INTERRUPT_CLEAR, 0x01)
        b.copyInto(result, 0, 0, 17)
        val raw = result[0].toInt() and 0x1F
        rangeStatus = if (raw < STATUS_MAP.size) STATUS_MAP[raw] else 255
    }

    /** Unsigned big-endian word from the result block (masked, no sign extension). */
    protected fun resultWord(i: Int): Int = ((result[i].toInt() and 0xFF) shl 8) or (result[i + 1].toInt() and 0xFF)

    protected fun waitAndRead(): Int {
        waitUntil({ isDataReady() }, "data ready")
        readResult()
        return resultWord(13)
    }

    /**
     * Take one single-shot measurement. Blocks for about one timing budget
     * (100 ms by default). Returns the raw range even when the measurement is
     * not valid; check [rangeValid].
     *
     * @return distance in mm
     * @throws IOException on bus error, or if the measurement does not complete within 500 ms
     */
    @Synchronized
    fun distance(): Int {
        write8(REG_INTERRUPT_CLEAR, 0x01)
        write8(REG_MODE_START, 0x10)
        return waitAndRead()
    }

    /**
     * Report whether the most recent measurement was valid.
     *
     * @return `true` iff the mapped range status was 0 (range valid)
     */
    fun rangeValid(): Boolean = rangeStatus == RANGE_STATUS_VALID

    /** Active `SOURCE_*` value decoded from SYSTEM__INTERRUPT_CONFIG_GPIO. */
    protected fun activeSource(): Int {
        val v = read8(REG_INTERRUPT_CONFIG_GPIO)
        if ((v and 0x20) != 0) return SOURCE_NEW_SAMPLE_READY
        return intArrayOf(SOURCE_LEVEL_LOW, SOURCE_LEVEL_HIGH, SOURCE_OUT_OF_WINDOW, SOURCE_IN_WINDOW)[v and 0x03]
    }

    @Synchronized
    @Throws(IOException::class)
    override fun pollInterruptStatus(): Int {
        if (!isDataReady()) return 0
        write8(REG_INTERRUPT_CLEAR, 0x01)
        return activeSource()
    }
}
