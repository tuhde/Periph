package it.uhde.periph.chips.tof;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * VL53L1X long-distance Time-of-Flight laser-ranging sensor (STMicroelectronics) — minimal
 * interface.
 *
 * <p>940&nbsp;nm VCSEL emitter, 16×16 SPAD receiving array behind a lens and an embedded ranging
 * microcontroller measuring absolute distance up to 4&nbsp;m at up to 50&nbsp;Hz. Two distance
 * modes (short ~1.3&nbsp;m, robust in sunlight; long ~3.6–4&nbsp;m in the dark), a 15–500&nbsp;ms
 * timing budget and a programmable region of interest (4×4 to 16×16 SPADs). The datasheet has no
 * register map: registers, the default configuration block and all sequences follow ST's Ultra
 * Lite Driver (STSW-IMG009). Registers use a 16-bit index; multi-byte registers are big-endian.
 *
 * <p>Pin-to-pin compatible with the VL53L0X and shares its {@link VL53Base} (register access,
 * polling, boot wait, interrupt delivery, re-addressing) and public API shape.
 *
 * <p>The constructor runs the full initialization sequence (XSHUT high if the connection has an
 * {@code enPin}, boot wait, firmware boot poll, sensor ID check, ULD default configuration, 2V8
 * I/O mode, GPIO1 active low, settling ranging) and leaves the chip idle in long distance mode
 * with a 100&nbsp;ms timing budget. {@link #distance()} then takes one single-shot measurement per
 * call. Methods are {@code synchronized}, serializing multi-register sequences against the Full
 * class's interrupt polling thread.
 *
 * <p>Multiple sensors on one bus: all VL53L0X/VL53L1X sensors power up at {@code 0x29}. Hold every
 * sensor's XSHUT low (each connection disabled), then for each sensor in turn enable its XSHUT,
 * construct a driver on {@code 0x29}, call {@code setAddress(new)}, and build the real driver on a
 * connection at the new address. The new address is volatile.
 */
public class VL53L1XMinimal extends VL53Base {

    /** Power-on 7-bit I²C address. */
    public static final int DEFAULT_ADDRESS = 0x29;

    /** Expected {@code IDENTIFICATION__MODEL_ID} + {@code MODULE_TYPE} word. */
    public static final int SENSOR_ID = 0xEACC;

    /** {@code IDENTIFICATION__MODEL_ID}. */
    public static final int MODEL_ID = 0xEA;

    /** {@code IDENTIFICATION__MODULE_TYPE}. */
    public static final int MODULE_TYPE = 0xCC;

    /** Mapped range status meaning "range valid". */
    public static final int RANGE_STATUS_VALID = 0;

    protected static final int REG_I2C_SLAVE_DEVICE_ADDRESS = 0x0001;
    protected static final int REG_VHV_CONFIG_LOOP_BOUND    = 0x0008;
    protected static final int REG_VHV_INIT                 = 0x000B;
    protected static final int REG_XTALK_PLANE_OFFSET       = 0x0016;
    protected static final int REG_XTALK_X_GRADIENT         = 0x0018;
    protected static final int REG_XTALK_Y_GRADIENT         = 0x001A;
    protected static final int REG_PART_TO_PART_OFFSET      = 0x001E;
    protected static final int REG_MM_INNER_OFFSET          = 0x0020;
    protected static final int REG_MM_OUTER_OFFSET          = 0x0022;
    protected static final int REG_PAD_I2C_HV_EXTSUP        = 0x002E;
    protected static final int REG_GPIO_EXTSUP_HV           = 0x002F;
    protected static final int REG_GPIO_HV_MUX_CTRL         = 0x0030;
    protected static final int REG_GPIO_TIO_HV_STATUS       = 0x0031;
    protected static final int REG_INTERRUPT_CONFIG_GPIO    = 0x0046;
    protected static final int REG_PHASECAL_TIMEOUT         = 0x004B;
    protected static final int REG_RANGE_TIMEOUT_A          = 0x005E;
    protected static final int REG_RANGE_VCSEL_PERIOD_A     = 0x0060;
    protected static final int REG_RANGE_TIMEOUT_B          = 0x0061;
    protected static final int REG_RANGE_VCSEL_PERIOD_B     = 0x0063;
    protected static final int REG_SIGMA_THRESH             = 0x0064;
    protected static final int REG_MIN_COUNT_RATE_RTN_LIMIT = 0x0066;
    protected static final int REG_RANGE_VALID_PHASE_HIGH   = 0x0069;
    protected static final int REG_INTERMEASUREMENT_PERIOD  = 0x006C;
    protected static final int REG_THRESH_HIGH              = 0x0072;
    protected static final int REG_THRESH_LOW               = 0x0074;
    protected static final int REG_SD_WOI_SD0               = 0x0078;
    protected static final int REG_SD_INITIAL_PHASE_SD0     = 0x007A;
    protected static final int REG_ROI_CENTRE_SPAD          = 0x007F;
    protected static final int REG_ROI_XY_SIZE              = 0x0080;
    protected static final int REG_INTERRUPT_CLEAR          = 0x0086;
    protected static final int REG_MODE_START               = 0x0087;
    protected static final int REG_RESULT_RANGE_STATUS      = 0x0089;
    protected static final int REG_OSC_CALIBRATE_VAL        = 0x00DE;
    protected static final int REG_FIRMWARE_SYSTEM_STATUS   = 0x00E5;
    protected static final int REG_MODEL_ID                 = 0x010F;
    protected static final int REG_MODULE_TYPE              = 0x0110;
    protected static final int REG_REVISION_ID              = 0x0111;
    protected static final int REG_MODE_ROI_CENTRE_SPAD     = 0x013E;

    private static final int DEFAULT_CONFIG_START = 0x002D;

    /** ULD {@code VL51L1X_DEFAULT_CONFIGURATION} — opaque, written verbatim to 0x002D..0x0087. */
    private static final int[] DEFAULT_CONFIGURATION = {
        0x00, 0x00, 0x00, 0x01, 0x02, 0x00, 0x02, 0x08, 0x00, 0x08, 0x10, 0x01, 0x01, 0x00, 0x00, 0x00,
        0x00, 0xFF, 0x00, 0x0F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20, 0x0B, 0x00, 0x00, 0x02, 0x0A, 0x21,
        0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x00, 0xC8, 0x00, 0x00, 0x38, 0xFF, 0x01, 0x00, 0x08, 0x00,
        0x00, 0x01, 0xCC, 0x0F, 0x01, 0xF1, 0x0D, 0x01, 0x68, 0x00, 0x80, 0x08, 0xB8, 0x00, 0x00, 0x00,
        0x00, 0x0F, 0x89, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x0F, 0x0D, 0x0E, 0x0E, 0x00,
        0x00, 0x02, 0xC7, 0xFF, 0x9B, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
    };

    /** ULD {@code status_rtn}: raw RESULT__RANGE_STATUS (bits 4:0) → mapped range status. */
    private static final int[] STATUS_MAP = {
        255, 255, 255, 5, 2, 4, 1, 7, 3, 0, 255, 255, 9, 13, 255, 255, 255, 255, 10, 6, 255, 255, 11, 12,
    };

    protected int rangeStatus = 255;
    protected final byte[] result = new byte[17];

    /**
     * Construct the driver and run the full initialization sequence.
     *
     * @param connection configured I²C connection pointing at the device (0x29)
     * @throws IOException on bus error, a sensor ID other than 0xEACC, or an init poll timeout
     */
    public VL53L1XMinimal(Connection connection) throws IOException {
        super(connection, 2, "VL53L1X");
        init();
    }

    private void init() throws IOException {
        bootWait();
        waitUntil(() -> (read8(REG_FIRMWARE_SYSTEM_STATUS) & 0x01) != 0, "boot");

        int sensorId = read16(REG_MODEL_ID);
        if (sensorId != SENSOR_ID) {
            throw new IOException(String.format("VL53L1X not found: expected sensor ID 0x%04X, got 0x%04X",
                    SENSOR_ID, sensorId));
        }

        for (int i = 0; i < DEFAULT_CONFIGURATION.length; i++) write8(DEFAULT_CONFIG_START + i, DEFAULT_CONFIGURATION[i]);

        // 2V8 I/O mode for I²C and GPIO1 pads; GPIO1 active low.
        write8(REG_PAD_I2C_HV_EXTSUP, 0x01);
        write8(REG_GPIO_EXTSUP_HV, 0x01);
        write8(REG_GPIO_HV_MUX_CTRL, 0x11);

        // Settling ranging (ULD SensorInit), then two-bound VHV from the previous temperature.
        write8(REG_MODE_START, 0x40);
        waitUntil(this::isDataReady, "data ready");
        write8(REG_INTERRUPT_CLEAR, 0x01);
        write8(REG_MODE_START, 0x00);
        write8(REG_VHV_CONFIG_LOOP_BOUND, 0x09);
        write8(REG_VHV_INIT, 0x00);
    }

    /**
     * @return true if GPIO1 is asserted (active low: bit 0 of GPIO__TIO_HV_STATUS is 0)
     * @throws IOException on bus error
     */
    protected boolean isDataReady() throws IOException {
        return (read8(REG_GPIO_TIO_HV_STATUS) & 0x01) == 0;
    }

    protected void readResult() throws IOException {
        byte[] b = readBlock(REG_RESULT_RANGE_STATUS, 17);
        write8(REG_INTERRUPT_CLEAR, 0x01);
        System.arraycopy(b, 0, result, 0, 17);
        int raw = result[0] & 0x1F;
        rangeStatus = raw < STATUS_MAP.length ? STATUS_MAP[raw] : 255;
    }

    protected int resultWord(int i) {
        return ((result[i] & 0xFF) << 8) | (result[i + 1] & 0xFF);
    }

    protected int waitAndRead() throws IOException {
        waitUntil(this::isDataReady, "data ready");
        readResult();
        return resultWord(13);
    }

    /**
     * Take one single-shot measurement. Blocks for about one timing budget (100&nbsp;ms by default).
     * Returns the raw range even when the measurement is not valid; check {@link #rangeValid()}.
     *
     * @return distance in mm
     * @throws IOException on bus error, or if the measurement does not complete within 500&nbsp;ms
     */
    public synchronized int distance() throws IOException {
        write8(REG_INTERRUPT_CLEAR, 0x01);
        write8(REG_MODE_START, 0x10);
        return waitAndRead();
    }

    /**
     * Report whether the most recent measurement was valid.
     *
     * @return {@code true} iff the mapped range status was 0 (range valid)
     */
    public boolean rangeValid() {
        return rangeStatus == RANGE_STATUS_VALID;
    }

    /**
     * @return the active {@code SOURCE_*} value decoded from SYSTEM__INTERRUPT_CONFIG_GPIO
     * @throws IOException on bus error
     */
    protected int activeSource() throws IOException {
        int v = read8(REG_INTERRUPT_CONFIG_GPIO);
        if ((v & 0x20) != 0) return SOURCE_NEW_SAMPLE_READY;
        return new int[]{SOURCE_LEVEL_LOW, SOURCE_LEVEL_HIGH, SOURCE_OUT_OF_WINDOW, SOURCE_IN_WINDOW}[v & 0x03];
    }

    @Override
    protected synchronized int pollInterruptStatus() throws IOException {
        if (!isDataReady()) return 0;
        write8(REG_INTERRUPT_CLEAR, 0x01);
        return activeSource();
    }
}
