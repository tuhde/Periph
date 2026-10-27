package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.Register;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

/**
 * LSM303DLHC full interface — extends {@link LSM303Minimal} with range, data-rate and
 * mode selection, high-pass filter, 32-level FIFO, two inertial interrupt generators,
 * single/double click detection, interrupt routing (INT1 / INT2 / DRDY), the
 * magnetometer configuration, relative temperature, and software compass heading.
 *
 * <p>Interrupts are delivered per INT line. {@link #onInterrupt(IntConsumer)} uses the
 * accelerometer connection's {@link InputPin} for INT1; INT2 needs an explicit pin via
 * {@link #onInterrupt(IntConsumer, int, InputPin)}. With no pin wired the driver falls
 * back to a 5&nbsp;ms polling thread. The callback receives the status value from
 * {@link #pollInterrupt()}, and only fires when that value is non-zero.
 *
 * <p>The chip's {@code INTx} lines are active-high by default; {@link #setInterruptPolarity}
 * with {@code activeLow=true} makes the driver trigger on falling edges.
 */
public class LSM303Full extends LSM303Minimal {

    /** Status bit: new accelerometer sample ({@code STATUS_REG_A.ZYXDA}). INT1 only. */
    public static final int SOURCE_DATA_READY = 0x0001;
    /** Status bit: FIFO content exceeds watermark ({@code FIFO_SRC_REG_A.WTM}). INT1 only. */
    public static final int SOURCE_WATERMARK = 0x0002;
    /** Status bit: FIFO overrun ({@code FIFO_SRC_REG_A.OVRN_FIFO}). INT1 only. */
    public static final int SOURCE_OVERRUN = 0x0004;
    /** Status bit: inertial generator 1 event ({@code INT1_SOURCE_A.IA}). INT1 or INT2. */
    public static final int SOURCE_IA1 = 0x0008;
    /** Status bit: inertial generator 2 event ({@code INT2_SOURCE_A.IA}). INT1 or INT2. */
    public static final int SOURCE_IA2 = 0x0010;
    /** Status bit: single or double click ({@code CLICK_SRC_A.IA}). INT1 or INT2. */
    public static final int SOURCE_CLICK = 0x0020;
    /** Boot procedure running. INT2 only; pin-only, never reported by {@link #pollInterrupt()}. */
    public static final int SOURCE_BOOT = 0x0040;
    /** New magnetometer sample ({@code SR_REG_M.DRDY}). Always on the DRDY pin; not routable. */
    public static final int SOURCE_MAG_DATA_READY = 0x0080;

    private static final int CTRL1_LPEN = 0x08;
    private static final int CTRL4_HR = 0x08;
    private static final int CTRL4_FS_MASK = 0x30;
    private static final int CTRL5_FIFO_EN = 0x40;
    private static final int CTRL5_BOOT = 0x80;
    private static final int CTRL5_LIR_INT1 = 0x08;
    private static final int CTRL5_D4D_INT1 = 0x04;
    private static final int CTRL5_LIR_INT2 = 0x02;
    private static final int CTRL5_D4D_INT2 = 0x01;
    private static final int CTRL6_BOOT_I1 = 0x10;
    private static final int CTRL6_H_LACTIVE = 0x02;

    /** Bitmask of sources currently routed to an INT line (by {@link #enableInterrupt}). */
    private int enabledSources = 0;
    /** Mirrors {@code H_LACTIVE}; selects the edge trigger used by {@link #onInterrupt}. */
    private boolean activeLow = false;

    private final Map<Integer, IntConsumer> callbacks = new ConcurrentHashMap<>();
    private final EdgeHandler[] edgeHandlers = new EdgeHandler[3];
    private final InputPin[] armedPins = new InputPin[3];
    private final Thread[] pollThreads = new Thread[3];

    /**
     * Construct the driver and run the initialisation sequence.
     *
     * @param accelConnection register connection to the accelerometer (address 0x19)
     * @param magConnection   register connection to the magnetometer (address 0x1E)
     * @throws IOException on bus error or if the magnetometer identity does not match
     */
    public LSM303Full(RegisterConnection accelConnection, RegisterConnection magConnection) throws IOException {
        super(accelConnection, magConnection);
    }

    // =====================================================================
    // Accelerometer configuration
    // =====================================================================

    /**
     * Set the full-scale range.
     *
     * @param rangeG full scale in <i>g</i>: 2, 4, 8 or 16
     * @throws IOException on bus error
     * @throws IllegalArgumentException if {@code rangeG} is not one of the allowed values
     */
    public void setRange(int rangeG) throws IOException {
        int code;
        switch (rangeG) {
            case 2:  code = 0; break;
            case 4:  code = 1; break;
            case 8:  code = 2; break;
            case 16: code = 3; break;
            default: throw new IllegalArgumentException("range must be 2, 4, 8 or 16 g");
        }
        accelUpdate(REG_CTRL_REG4_A, CTRL4_FS_MASK, code << 4);
        accelFs = code;
    }

    /**
     * Set the output data rate.
     *
     * <p>1620&nbsp;Hz and 5376&nbsp;Hz are valid only in low-power mode; 1344&nbsp;Hz is
     * valid only in normal or high-resolution mode (they share the ODR code).
     *
     * @param rateHz 0 (power-down), 1, 10, 25, 50, 100, 200, 400, 1344, 1620 or 5376
     * @throws IOException on bus error
     * @throws IllegalArgumentException if the rate is unknown or not allowed in the current mode
     */
    public void setDataRate(int rateHz) throws IOException {
        int code;
        switch (rateHz) {
            case 0:    code = 0x0; break;
            case 1:    code = 0x1; break;
            case 10:   code = 0x2; break;
            case 25:   code = 0x3; break;
            case 50:   code = 0x4; break;
            case 100:  code = 0x5; break;
            case 200:  code = 0x6; break;
            case 400:  code = 0x7; break;
            case 1620:
                requireMode(0, "1620 Hz is low-power only");
                code = 0x8;
                break;
            case 1344:
                if (accelMode == 0) throw new IllegalArgumentException("1344 Hz is normal / high-resolution only");
                code = 0x9;
                break;
            case 5376:
                requireMode(0, "5376 Hz is low-power only");
                code = 0x9;
                break;
            default: throw new IllegalArgumentException("unsupported data rate: " + rateHz);
        }
        accelUpdate(REG_CTRL_REG1_A, 0xF0, code << 4);
        odrHz = rateHz;
    }

    private void requireMode(int mode, String message) {
        if (accelMode != mode) throw new IllegalArgumentException(message);
    }

    /**
     * Select the accelerometer operating mode.
     *
     * <p>Maps to {@code LPen}/{@code HR}: 0 low-power ({@code LPen}=1, {@code HR}=0),
     * 1 normal ({@code LPen}=0, {@code HR}=0), 2 high-resolution ({@code LPen}=0, {@code HR}=1).
     * Changes the resolution used by {@link #readAcceleration()} and {@link #readFifo}.
     *
     * @param mode 0 low-power, 1 normal, 2 high-resolution
     * @throws IOException on bus error
     * @throws IllegalArgumentException if {@code mode} is out of range
     */
    public void setMode(int mode) throws IOException {
        if (mode < 0 || mode > 2) throw new IllegalArgumentException("mode must be 0, 1 or 2");
        accelUpdate(REG_CTRL_REG1_A, CTRL1_LPEN, mode == 0 ? CTRL1_LPEN : 0);
        accelUpdate(REG_CTRL_REG4_A, CTRL4_HR, mode == 2 ? CTRL4_HR : 0);
        accelMode = mode;
    }

    /**
     * Enable or disable individual accelerometer axes.
     *
     * @param x enable the X axis
     * @param y enable the Y axis
     * @param z enable the Z axis
     * @throws IOException on bus error
     */
    public void setAxes(boolean x, boolean y, boolean z) throws IOException {
        int bits = (x ? 0x01 : 0) | (y ? 0x02 : 0) | (z ? 0x04 : 0);
        accelUpdate(REG_CTRL_REG1_A, 0x07, bits);
    }

    /**
     * Put the accelerometer into power-down ({@code ODR} = 0000). Register contents are retained.
     *
     * @throws IOException on bus error
     */
    public void powerDown() throws IOException {
        accelUpdate(REG_CTRL_REG1_A, 0xF0, 0);
        odrHz = 0;
    }

    /**
     * Read the raw accelerometer counts at the current resolution.
     *
     * @return {@code [x, y, z]} signed counts (right-shifted by the current mode)
     * @throws IOException on bus error
     */
    public int[] readAccelerationRaw() throws IOException {
        return accelCounts(accel.read(REG_OUT_X_L_A | ACCEL_AUTO_INC, 6));
    }

    /**
     * @return {@code true} if a new accelerometer sample is available ({@code STATUS_REG_A.ZYXDA})
     * @throws IOException on bus error
     */
    public boolean newDataAvailable() throws IOException {
        return (accelRead(REG_STATUS_REG_A) & 0x08) != 0;
    }

    /**
     * @return {@code STATUS_REG_A} byte: {@code ZYXOR} 7, {@code ZOR} 6, {@code YOR} 5,
     *         {@code XOR} 4, {@code ZYXDA} 3, {@code ZDA} 2, {@code YDA} 1, {@code XDA} 0
     * @throws IOException on bus error
     */
    public int readStatus() throws IOException {
        return accelRead(REG_STATUS_REG_A);
    }

    // =====================================================================
    // High-pass filter
    // =====================================================================

    /**
     * Configure the high-pass filter ({@code CTRL_REG2_A}).
     *
     * @param mode         {@code HPM} 0–3: 0 normal (reset by reading {@link #resetHighPass}),
     *                     1 reference filtering, 2 normal, 3 autoreset on interrupt
     * @param cutoff       {@code HPCF<2:1>} 0–3 (no Hz values are tabulated in this datasheet revision)
     * @param filterOutput route filtered data to the output registers and FIFO ({@code FDS})
     * @param onIa1        filter generator 1 ({@code HPIS1})
     * @param onIa2        filter generator 2 ({@code HPIS2})
     * @param onClick      filter click detection ({@code HPCLICK})
     * @throws IOException on bus error
     * @throws IllegalArgumentException if {@code mode} or {@code cutoff} is outside 0–3
     */
    public void setHighPass(int mode, int cutoff, boolean filterOutput, boolean onIa1,
                            boolean onIa2, boolean onClick) throws IOException {
        if (mode < 0 || mode > 3) throw new IllegalArgumentException("HPM must be 0..3");
        if (cutoff < 0 || cutoff > 3) throw new IllegalArgumentException("HPCF must be 0..3");
        int v = ((mode & 0x03) << 6) | ((cutoff & 0x03) << 4)
              | (filterOutput ? 0x08 : 0) | (onClick ? 0x04 : 0)
              | (onIa2 ? 0x02 : 0) | (onIa1 ? 0x01 : 0);
        accelWrite(REG_CTRL_REG2_A, v);
    }

    /**
     * Read {@code REFERENCE_A}; in {@code HPM} = 00 this resets the high-pass filter.
     *
     * @return the reference register value
     * @throws IOException on bus error
     */
    public int resetHighPass() throws IOException {
        return accelRead(REG_REFERENCE_A);
    }

    /**
     * Write the high-pass reference value (used when {@code HPM} = 01).
     *
     * @param value reference value, 0–255
     * @throws IOException on bus error
     * @throws IllegalArgumentException if {@code value} is outside 0–255
     */
    public void setReference(int value) throws IOException {
        if (value < 0 || value > 255) throw new IllegalArgumentException("reference must be 0..255");
        accelWrite(REG_REFERENCE_A, value);
    }

    // =====================================================================
    // FIFO
    // =====================================================================

    /**
     * Configure the FIFO in bypass mode with no watermark. See {@link #setFifo(int, int, boolean)}.
     *
     * @param mode 0 bypass, 1 FIFO, 2 stream, 3 trigger
     * @throws IOException on bus error
     */
    public void setFifo(int mode) throws IOException {
        setFifo(mode, 0, false);
    }

    /**
     * Configure the FIFO. {@code FIFO_EN} in {@code CTRL_REG5_A} is set for modes 1–3.
     * Returning to bypass clears the buffer (the chip does this); to restart FIFO mode
     * after a drain go through bypass.
     *
     * @param mode         0 bypass, 1 FIFO, 2 stream, 3 trigger (stream-to-FIFO)
     * @param watermark    watermark level, 0–31
     * @param triggerInt2  trigger source for trigger mode: {@code false} = INT1, {@code true} = INT2
     * @throws IOException on bus error
     * @throws IllegalArgumentException if a value is out of range
     */
    public void setFifo(int mode, int watermark, boolean triggerInt2) throws IOException {
        if (mode < 0 || mode > 3) throw new IllegalArgumentException("FIFO mode must be 0..3");
        if (watermark < 0 || watermark > 31) throw new IllegalArgumentException("watermark must be 0..31");
        accelWrite(REG_FIFO_CTRL_REG_A, (mode << 6) | (triggerInt2 ? 0x20 : 0) | watermark);
        accelUpdate(REG_CTRL_REG5_A, CTRL5_FIFO_EN, mode != 0 ? CTRL5_FIFO_EN : 0);
    }

    /**
     * @return number of unread FIFO sample sets ({@code FSS<4:0>}, 0–32)
     * @throws IOException on bus error
     */
    public int fifoLevel() throws IOException {
        return accelRead(REG_FIFO_SRC_REG_A) & 0x1F;
    }

    /**
     * Drain all unread FIFO sample sets. See {@link #readFifo(int)}.
     *
     * @return list of {@code [x, y, z]} samples in <i>g</i>
     * @throws IOException on bus error
     */
    public double[][] readFifo() throws IOException {
        return readFifo(Integer.MAX_VALUE);
    }

    /**
     * Read up to {@code count} FIFO sample sets, one 6-byte burst per sample.
     *
     * @param count maximum number of samples to read (capped at the current {@code FSS})
     * @return list of {@code [x, y, z]} samples in <i>g</i>, possibly empty
     * @throws IOException on bus error
     */
    public double[][] readFifo(int count) throws IOException {
        int n = Math.min(count, fifoLevel());
        double[][] out = new double[Math.max(n, 0)][];
        for (int i = 0; i < out.length; i++) {
            out[i] = convertAccel(accel.read(REG_OUT_X_L_A | ACCEL_AUTO_INC, 6));
        }
        return out;
    }

    /**
     * Decode {@code FIFO_SRC_REG_A}.
     *
     * @return {@code [watermark, overrun, empty, level]}: flags are 0 or 1, level is {@code FSS}
     * @throws IOException on bus error
     */
    public int[] readFifoStatus() throws IOException {
        int v = accelRead(REG_FIFO_SRC_REG_A);
        return new int[]{(v >> 7) & 1, (v >> 6) & 1, (v >> 5) & 1, v & 0x1F};
    }

    // =====================================================================
    // Inertial interrupt generators
    // =====================================================================

    /**
     * Configure an inertial interrupt generator (threshold / duration / event mask).
     *
     * <p>Writes {@code INTx_CFG_A}, {@code INTx_THS_A} and {@code INTx_DURATION_A}.
     * The threshold uses 1 LSb = full scale / 128; the duration uses 1 LSb = 1/ODR.
     * The generator's source must still be routed with {@link #enableInterrupt}.
     *
     * @param generator 1 or 2
     * @param events    6-bit mask of {@code ZHIE} (bit 5), {@code ZLIE} 4, {@code YHIE} 3,
     *                  {@code YLIE} 2, {@code XHIE} 1, {@code XLIE} 0
     * @param thresholdG threshold in <i>g</i>
     * @param durationMs minimum event duration in ms
     * @param andMode   {@code true} = AND of the enabled events, {@code false} = OR
     * @throws IOException on bus error
     * @throws IllegalArgumentException if {@code generator} or {@code events} is out of range
     */
    public void setMotionInterrupt(int generator, int events, double thresholdG,
                                   double durationMs, boolean andMode) throws IOException {
        checkGenerator(generator);
        if (events < 0 || events > 0x3F) throw new IllegalArgumentException("events must be a 6-bit mask");
        int cfg = generator == 1 ? REG_INT1_CFG_A : REG_INT2_CFG_A;
        int ths = generator == 1 ? REG_INT1_THS_A : REG_INT2_THS_A;
        int dur = generator == 1 ? REG_INT1_DURATION_A : REG_INT2_DURATION_A;
        accelWrite(cfg, (andMode ? 0x80 : 0) | events);
        accelWrite(ths, thresholdCode(thresholdG));
        accelWrite(dur, timeCode(durationMs, 127));
    }

    /**
     * Free-fall detection: all three axes low, AND combination ({@code INTx_CFG_A} = 0x95 at the defaults).
     *
     * @param generator  1 or 2
     * @param thresholdG threshold in <i>g</i>
     * @param durationMs minimum duration in ms
     * @throws IOException on bus error
     */
    public void setFreeFall(int generator, double thresholdG, double durationMs) throws IOException {
        setMotionInterrupt(generator, 0x15, thresholdG, durationMs, true);
    }

    /**
     * 6D orientation: movement recognition (default) or position recognition, optionally 4D.
     * All six zone events are enabled; the generator's threshold register is not changed.
     *
     * @param generator 1 or 2
     * @param position  {@code true} = position recognition ({@code AOI}=1, level);
     *                  {@code false} = movement recognition (pulse of one ODR period)
     * @param fourD     {@code true} = 4D detection ({@code D4D_INTx} set)
     * @throws IOException on bus error
     */
    public void setOrientation(int generator, boolean position, boolean fourD) throws IOException {
        checkGenerator(generator);
        int cfg = generator == 1 ? REG_INT1_CFG_A : REG_INT2_CFG_A;
        accelWrite(cfg, (position ? 0x80 : 0) | 0x40 | 0x3F);
        int mask = generator == 1 ? CTRL5_D4D_INT1 : CTRL5_D4D_INT2;
        accelUpdate(REG_CTRL_REG5_A, mask, fourD ? mask : 0);
    }

    /**
     * Latch an inertial generator's interrupt until its source register is read.
     *
     * @param generator 1 or 2
     * @param enabled   {@code true} to latch ({@code LIR_INTx})
     * @throws IOException on bus error
     */
    public void setLatch(int generator, boolean enabled) throws IOException {
        checkGenerator(generator);
        int mask = generator == 1 ? CTRL5_LIR_INT1 : CTRL5_LIR_INT2;
        accelUpdate(REG_CTRL_REG5_A, mask, enabled ? mask : 0);
    }

    /**
     * Read {@code INTx_SOURCE_A}. <b>Clears the generator's {@code IA} flag and INT line.</b>
     *
     * @param generator 1 or 2
     * @return the source byte: bit 6 {@code IA}, bits 5–0 {@code ZH} … {@code XL}
     * @throws IOException on bus error
     */
    public int readInterruptSource(int generator) throws IOException {
        checkGenerator(generator);
        return accelRead(generator == 1 ? REG_INT1_SOURCE_A : REG_INT2_SOURCE_A);
    }

    // =====================================================================
    // Click detection
    // =====================================================================

    /**
     * Configure single and double click detection.
     *
     * <p>The axis masks use X=1, Y=2, Z=4. Writes {@code CLICK_CFG_A}, {@code CLICK_THS_A}
     * (1 LSb = full scale / 128) and the {@code TIME_*} registers (1 LSb = 1/ODR).
     *
     * @param single      axis mask for single click
     * @param doubleClick axis mask for double click
     * @param thresholdG  click threshold in <i>g</i>
     * @param timeLimitMs maximum time above threshold in ms
     * @param latencyMs   dead time after the first click before a second is detected, in ms
     * @param windowMs    window in which the second click must occur, in ms
     * @throws IOException on bus error
     * @throws IllegalArgumentException if an axis mask is outside 0–7
     */
    public void setClick(int single, int doubleClick, double thresholdG, double timeLimitMs,
                         double latencyMs, double windowMs) throws IOException {
        if (single < 0 || single > 7 || doubleClick < 0 || doubleClick > 7) {
            throw new IllegalArgumentException("click axis masks must be 0..7");
        }
        int cfg = (single & 0x01) | ((single & 0x02) << 1) | ((single & 0x04) << 2)
                | ((doubleClick & 0x01) << 1) | ((doubleClick & 0x02) << 2) | ((doubleClick & 0x04) << 3);
        accelWrite(REG_CLICK_CFG_A, cfg);
        accelWrite(REG_CLICK_THS_A, thresholdCode(thresholdG));
        accelWrite(REG_TIME_LIMIT_A, timeCode(timeLimitMs, 127));
        accelWrite(REG_TIME_LATENCY_A, timeCode(latencyMs, 255));
        accelWrite(REG_TIME_WINDOW_A, timeCode(windowMs, 255));
    }

    /**
     * Read {@code CLICK_SRC_A}. <b>Clears the click interrupt.</b>
     *
     * @return the source byte: bit 6 {@code IA}, 5 {@code DCLICK}, 4 {@code SCLICK},
     *         3 {@code Sign}, 2 {@code Z}, 1 {@code Y}, 0 {@code X}
     * @throws IOException on bus error
     */
    public int readClickSource() throws IOException {
        return accelRead(REG_CLICK_SRC_A);
    }

    // =====================================================================
    // Interrupt routing and polarity
    // =====================================================================

    /**
     * Set the INT1 / INT2 polarity ({@code H_LACTIVE}). Also changes the edge trigger
     * used by subsequent {@link #onInterrupt} calls.
     *
     * @param activeLow {@code true} for active-low INT lines
     * @throws IOException on bus error
     */
    public void setInterruptPolarity(boolean activeLow) throws IOException {
        accelUpdate(REG_CTRL_REG6_A, CTRL6_H_LACTIVE, activeLow ? CTRL6_H_LACTIVE : 0);
        this.activeLow = activeLow;
    }

    /**
     * Route an interrupt source to an INT line. The pin is ignored for INT1-only
     * ({@link #SOURCE_DATA_READY}, {@link #SOURCE_WATERMARK}, {@link #SOURCE_OVERRUN}) and
     * INT2-only ({@link #SOURCE_BOOT}) sources. The generator or function behind the
     * source is configured separately.
     *
     * @param source one of the {@code SOURCE_*} constants except {@link #SOURCE_MAG_DATA_READY}
     * @param pin    INT line, 1 or 2
     * @throws IOException on bus error
     * @throws IllegalArgumentException for an unknown source, a non-routable source or a bad pin
     */
    public void enableInterrupt(int source, int pin) throws IOException {
        routeSource(source, true, pin);
        enabledSources |= source;
    }

    /** Route an interrupt source to INT1. See {@link #enableInterrupt(int, int)}. */
    public void enableInterrupt(int source) throws IOException {
        enableInterrupt(source, 1);
    }

    /**
     * Remove an interrupt source from an INT line. See {@link #enableInterrupt(int, int)}.
     *
     * @param source one of the {@code SOURCE_*} constants except {@link #SOURCE_MAG_DATA_READY}
     * @param pin    INT line, 1 or 2
     * @throws IOException on bus error
     * @throws IllegalArgumentException for an unknown source, a non-routable source or a bad pin
     */
    public void disableInterrupt(int source, int pin) throws IOException {
        routeSource(source, false, pin);
        enabledSources &= ~source;
    }

    /** Remove an interrupt source from INT1. See {@link #disableInterrupt(int, int)}. */
    public void disableInterrupt(int source) throws IOException {
        disableInterrupt(source, 1);
    }

    private void routeSource(int source, boolean on, int pin) throws IOException {
        checkPin(pin);
        switch (source) {
            case SOURCE_DATA_READY:
                accelUpdate(REG_CTRL_REG3_A, 0x10, on ? 0x10 : 0);
                break;
            case SOURCE_WATERMARK:
                accelUpdate(REG_CTRL_REG3_A, 0x04, on ? 0x04 : 0);
                break;
            case SOURCE_OVERRUN:
                accelUpdate(REG_CTRL_REG3_A, 0x02, on ? 0x02 : 0);
                break;
            case SOURCE_IA1:
                routeToPin(0x40, pin, on);
                break;
            case SOURCE_IA2:
                routeToPin(0x20, pin, on);
                break;
            case SOURCE_CLICK:
                routeToPin(0x80, pin, on);
                break;
            case SOURCE_BOOT:
                accelUpdate(REG_CTRL_REG6_A, CTRL6_BOOT_I1, on ? CTRL6_BOOT_I1 : 0);
                break;
            case SOURCE_MAG_DATA_READY:
                throw new IllegalArgumentException("SOURCE_MAG_DATA_READY is on the DRDY pin and cannot be routed");
            default:
                throw new IllegalArgumentException("unknown interrupt source: 0x" + Integer.toHexString(source));
        }
    }

    /**
     * Route a function to INT1 ({@code CTRL_REG3_A}) or INT2 ({@code CTRL_REG6_A}). Both
     * registers use the same bit layout: 0x80 click, 0x40 generator 1, 0x20 generator 2.
     */
    private void routeToPin(int bit, int pin, boolean on) throws IOException {
        int reg = pin == 2 ? REG_CTRL_REG6_A : REG_CTRL_REG3_A;
        accelUpdate(reg, bit, on ? bit : 0);
    }

    // =====================================================================
    // Interrupt subscribe / poll
    // =====================================================================

    /**
     * Subscribe to INT1 using the accelerometer connection's {@link InputPin}, or a
     * 5&nbsp;ms polling thread if none is wired. Equivalent to {@code onInterrupt(callback, 1)}.
     *
     * @param callback receives the non-zero {@link #pollInterrupt()} status; bus errors
     *                 inside the handler are dropped
     */
    public void onInterrupt(IntConsumer callback) {
        onInterrupt(callback, 1);
    }

    /**
     * Subscribe on one INT line. INT1 uses the connection's {@link InputPin} when one is
     * wired; INT2 has no pin of its own, so it falls back to polling unless one is supplied
     * with {@link #onInterrupt(IntConsumer, int, InputPin)}.
     *
     * @param callback receives the non-zero {@link #pollInterrupt()} status
     * @param pin      INT line, 1 or 2
     * @throws IllegalArgumentException if {@code pin} is not 1 or 2
     */
    public void onInterrupt(IntConsumer callback, int pin) {
        onInterrupt(callback, pin, pin == 1 ? accel.intPin() : null);
    }

    /**
     * Subscribe on one INT line using an explicit {@link InputPin}, or polling if {@code intPin} is {@code null}.
     *
     * @param callback receives the non-zero {@link #pollInterrupt()} status
     * @param pin      INT line, 1 or 2
     * @param intPin   pin wired to that INT line, or {@code null} for the 5&nbsp;ms polling fallback
     * @throws IllegalArgumentException if {@code pin} is not 1 or 2
     */
    public void onInterrupt(IntConsumer callback, int pin, InputPin intPin) {
        checkPin(pin);
        offInterrupt(pin);
        callbacks.put(pin, callback);
        if (intPin != null) {
            EdgeHandler handler = () -> dispatch(pin);
            edgeHandlers[pin] = handler;
            armedPins[pin] = intPin;
            intPin.onEdge(handler, activeLow ? EdgeTrigger.FALLING : EdgeTrigger.RISING);
        } else {
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    dispatch(pin);
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "lsm303-int" + pin);
            t.setDaemon(true);
            pollThreads[pin] = t;
            t.start();
        }
    }

    /** Unsubscribe INT1. See {@link #offInterrupt(int)}. */
    public void offInterrupt() {
        offInterrupt(1);
    }

    /**
     * Unsubscribe one INT line: removes the edge handler or stops the polling thread.
     *
     * @param pin INT line, 1 or 2
     * @throws IllegalArgumentException if {@code pin} is not 1 or 2
     */
    public void offInterrupt(int pin) {
        checkPin(pin);
        callbacks.remove(pin);
        if (armedPins[pin] != null) {
            armedPins[pin].offEdge(edgeHandlers[pin]);
            armedPins[pin] = null;
            edgeHandlers[pin] = null;
        }
        if (pollThreads[pin] != null) {
            pollThreads[pin].interrupt();
            pollThreads[pin] = null;
        }
    }

    private void dispatch(int pin) {
        IntConsumer cb = callbacks.get(pin);
        if (cb == null) return;
        try {
            int status = pollInterrupt();
            if (status != 0) cb.accept(status);
        } catch (IOException e) {
            // Bus error inside an interrupt context: nothing to report to; drop this event.
        }
    }

    /**
     * Read the status of every enabled source and return it as one bitmask (bit
     * layout matches the {@code SOURCE_*} constants). {@link #SOURCE_MAG_DATA_READY} is
     * always checked because it is wired to the DRDY pin. Reading {@code INTx_SOURCE_A}
     * and {@code CLICK_SRC_A} <b>clears</b> their latched flags.
     *
     * @return combined status bitmask, 0 if nothing is pending
     * @throws IOException on bus error
     */
    public int pollInterrupt() throws IOException {
        int status = 0;
        int en = enabledSources;
        if ((en & SOURCE_DATA_READY) != 0 && newDataAvailable()) status |= SOURCE_DATA_READY;
        if ((en & (SOURCE_WATERMARK | SOURCE_OVERRUN)) != 0) {
            int fifo = accelRead(REG_FIFO_SRC_REG_A);
            if ((en & SOURCE_WATERMARK) != 0 && (fifo & 0x80) != 0) status |= SOURCE_WATERMARK;
            if ((en & SOURCE_OVERRUN) != 0 && (fifo & 0x40) != 0) status |= SOURCE_OVERRUN;
        }
        if ((en & SOURCE_IA1) != 0 && (readInterruptSource(1) & 0x40) != 0) status |= SOURCE_IA1;
        if ((en & SOURCE_IA2) != 0 && (readInterruptSource(2) & 0x40) != 0) status |= SOURCE_IA2;
        if ((en & SOURCE_CLICK) != 0 && (readClickSource() & 0x40) != 0) status |= SOURCE_CLICK;
        if (magDataReady()) status |= SOURCE_MAG_DATA_READY;
        return status;
    }

    // =====================================================================
    // Magnetometer
    // =====================================================================

    /**
     * Set the magnetometer full-scale range ({@code CRB_REG_M.GN}).
     *
     * @param rangeUt full scale in µT: 130, 190, 250, 400, 470, 560 or 810 (±1.3 … ±8.1 gauss)
     * @throws IOException on bus error
     * @throws IllegalArgumentException if {@code rangeUt} is not one of the allowed values
     */
    public void setMagRange(int rangeUt) throws IOException {
        int idx = -1;
        for (int i = 0; i < MAG_RANGE_UT.length; i++) {
            if (MAG_RANGE_UT[i] == rangeUt) { idx = i; break; }
        }
        if (idx < 0) throw new IllegalArgumentException("unsupported magnetometer range: " + rangeUt + " uT");
        magWrite(REG_CRB_REG_M, (idx + 1) << 5);
        magGainXy = MAG_GAIN_XY[idx];
        magGainZ = MAG_GAIN_Z[idx];
        magRangeUt = rangeUt;
    }

    /**
     * Set the magnetometer output data rate ({@code CRA_REG_M.DO}); keeps {@code TEMP_EN}.
     *
     * @param rateHz 0.75, 1.5, 3, 7.5, 15, 30, 75 or 220
     * @throws IOException on bus error
     * @throws IllegalArgumentException if the rate is not supported
     */
    public void setMagDataRate(double rateHz) throws IOException {
        int code;
        if (rateHz == 0.75)       code = 0;
        else if (rateHz == 1.5)   code = 1;
        else if (rateHz == 3.0)   code = 2;
        else if (rateHz == 7.5)   code = 3;
        else if (rateHz == 15.0)  code = 4;
        else if (rateHz == 30.0)  code = 5;
        else if (rateHz == 75.0)  code = 6;
        else if (rateHz == 220.0) code = 7;
        else throw new IllegalArgumentException("unsupported magnetometer rate: " + rateHz + " Hz");
        magUpdate(REG_CRA_REG_M, 0x1C, code << 2);
    }

    /**
     * Set the magnetometer operating mode ({@code MR_REG_M.MD}).
     *
     * @param mode 0 continuous, 1 single conversion (then sleep), 2 sleep
     * @throws IOException on bus error
     * @throws IllegalArgumentException if {@code mode} is out of range
     */
    public void setMagMode(int mode) throws IOException {
        if (mode < 0 || mode > 2) throw new IllegalArgumentException("magnetometer mode must be 0, 1 or 2");
        magWrite(REG_MR_REG_M, mode);
    }

    /**
     * Read the magnetometer raw counts in X, Y, Z order.
     *
     * @return {@code [x, y, z]} signed counts
     * @throws IOException on bus error
     */
    public int[] readMagneticRaw() throws IOException {
        return readMagCounts();
    }

    /**
     * @return {@code true} if {@code SR_REG_M.DRDY} is set
     * @throws IOException on bus error
     */
    public boolean magDataReady() throws IOException {
        return (magRead(REG_SR_REG_M) & 0x01) != 0;
    }

    /**
     * Decode {@code SR_REG_M}.
     *
     * @return {@code [drdy, lock]}, each 0 or 1
     * @throws IOException on bus error
     */
    public int[] readMagStatus() throws IOException {
        int v = magRead(REG_SR_REG_M);
        return new int[]{v & 1, (v >> 1) & 1};
    }

    /**
     * Read the magnetometer identification registers.
     *
     * @return three bytes {@code IRA}, {@code IRB}, {@code IRC}; {@code 0x48 0x34 0x33} on a genuine part
     * @throws IOException on bus error
     */
    public byte[] readMagIdentity() throws IOException {
        return mag.read(REG_IRA_REG_M, 3);
    }

    /**
     * Enable or disable the magnetometer temperature sensor ({@code CRA_REG_M.TEMP_EN}).
     *
     * @param enabled {@code true} to enable
     * @throws IOException on bus error
     */
    public void enableTemperature(boolean enabled) throws IOException {
        magUpdate(REG_CRA_REG_M, 0x80, enabled ? 0x80 : 0);
    }

    /**
     * Read the relative temperature. Enables the sensor on first use if it is off.
     * The datasheet gives no absolute reference, so the value is a delta only.
     *
     * @return temperature change in °C (8 LSB/°C, relative to an unspecified reference)
     * @throws IOException on bus error
     */
    public double readTemperatureDelta() throws IOException {
        if ((magRead(REG_CRA_REG_M) & 0x80) == 0) enableTemperature(true);
        byte[] raw = mag.read(REG_TEMP_OUT_H_M, 2);
        int v = Register.toSigned(((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF), 16) >> 4;
        return v / 8.0;
    }

    /** Compass heading with tilt compensation. See {@link #heading(boolean)}. */
    public double heading() throws IOException {
        return heading(true);
    }

    /**
     * Compass heading in degrees, 0 to 360, using {@code atan2(y, x)}.
     *
     * <p>Flat: {@code atan2(my, mx)} from the magnetometer only. Tilt-compensated: also
     * reads the accelerometer, derives roll and pitch from the normalised gravity vector,
     * and projects the field onto the horizontal plane. Both sensors are assumed to share
     * one body frame; the datasheet does not document their axis alignment. No declination
     * is applied.
     *
     * @param tiltCompensate {@code true} to read the accelerometer and compensate for tilt
     * @return heading in degrees, 0 to 360
     * @throws IOException on bus error
     */
    public double heading(boolean tiltCompensate) throws IOException {
        double[] m = readMagnetic();
        double mx = m[0], my = m[1], mz = m[2];
        double h;
        if (!tiltCompensate) {
            h = Math.atan2(my, mx);
        } else {
            double[] a = readAcceleration();
            double n = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
            if (n == 0.0) {
                h = Math.atan2(my, mx);
            } else {
                double ax = a[0] / n, ay = a[1] / n, az = a[2] / n;
                double roll = Math.atan2(ay, az);
                double pitch = Math.atan2(-ax, ay * Math.sin(roll) + az * Math.cos(roll));
                double xh = mx * Math.cos(pitch) + mz * Math.sin(pitch);
                double yh = mx * Math.sin(roll) * Math.sin(pitch) + my * Math.cos(roll)
                          - mz * Math.sin(roll) * Math.cos(pitch);
                h = Math.atan2(yh, xh);
            }
        }
        double deg = Math.toDegrees(h);
        if (deg < 0) deg += 360.0;
        if (deg >= 360.0) deg -= 360.0;
        return deg;
    }

    // =====================================================================
    // Reboot
    // =====================================================================

    /**
     * Reboot the accelerometer memory ({@code CTRL_REG5_A.BOOT}) and wait 5&nbsp;ms.
     * The datasheet does not say which control registers survive, so run the
     * initialisation again (construct a new driver) afterwards.
     *
     * @throws IOException on bus error
     */
    public void reboot() throws IOException {
        accelUpdate(REG_CTRL_REG5_A, CTRL5_BOOT, CTRL5_BOOT);
        sleepMs(5);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static void checkGenerator(int generator) {
        if (generator != 1 && generator != 2) throw new IllegalArgumentException("generator must be 1 or 2");
    }

    private static void checkPin(int pin) {
        if (pin != 1 && pin != 2) throw new IllegalArgumentException("INT pin must be 1 or 2");
    }
}
