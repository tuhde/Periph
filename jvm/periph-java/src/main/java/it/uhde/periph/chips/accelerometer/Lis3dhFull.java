package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.Register;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * LIS3DH full interface — extends {@link Lis3dhMinimal} with the complete
 * chip functionality.
 *
 * <p>Adds range / data-rate / mode selection, raw counts, status and
 * temperature reads, the auxiliary ADC, the high-pass filter, the 32-level
 * FIFO, both inertial interrupt generators (free-fall, 6D / 4D orientation),
 * single / double click detection, the sleep-to-wake activity function,
 * interrupt routing and delivery on INT1 / INT2, memory reboot, SDO
 * pull-up control and the electrostatic self-test.
 */
public class Lis3dhFull extends Lis3dhMinimal {

    /** Bit position of the source inside the enabled-source bookkeeping. */
    private static final int SOURCE_BIT_DATA_READY = 0;
    private static final int SOURCE_BIT_AUX_DATA_READY = 1;
    private static final int SOURCE_BIT_WATERMARK = 2;
    private static final int SOURCE_BIT_OVERRUN = 3;
    private static final int SOURCE_BIT_IA1 = 4;
    private static final int SOURCE_BIT_IA2 = 5;
    private static final int SOURCE_BIT_CLICK = 6;

    /** Sources routed to INT1 only (through {@code CTRL_REG3}). */
    private int enabled1 = 0;

    /** Sources routed to INT2 only (through {@code CTRL_REG6}). */
    private int enabled2 = 0;

    private volatile IntConsumer callback;
    private InputPin intPin;
    private Thread pollThread;
    private volatile boolean polling;
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * Construct the driver and run the datasheet Initialization Sequence.
     *
     * @param connection configured I²C, SMBus, or SPI register connection bound
     *                   to the chip's address (0x18 with SA0 = GND, 0x19 with
     *                   SA0 = VDD_IO)
     * @throws IOException on bus error or when {@code WHO_AM_I} is not 0x33
     */
    public Lis3dhFull(RegisterConnection connection) throws IOException {
        super(connection);
    }

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    /**
     * Set the full-scale measurement range.
     *
     * <p>Read-modify-writes the {@code FS} field of {@code CTRL_REG4} and
     * updates the cached sensitivity used by {@link #read()} and
     * {@link #readRaw()}.
     *
     * @param rangeG full-scale range in <i>g</i>: 2, 4, 8 or 16
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if {@code rangeG} is not 2, 4, 8 or 16
     */
    public void setRange(int rangeG) throws IOException {
        int index = rangeIndex(rangeG);
        if (index < 0) {
            throw new IllegalArgumentException(
                    "LIS3DH setRange: range must be one of 2, 4, 8, 16, got " + rangeG);
        }
        int ctrl4 = readReg(REG_CTRL_REG4);
        writeReg(REG_CTRL_REG4, (ctrl4 & ~0x30) | (index << 4));
        this.rangeG = rangeG;
    }

    /**
     * Set the output data rate, or power the chip down.
     *
     * <p>Read-modify-writes the {@code ODR} field of {@code CTRL_REG1} and
     * waits for the turn-on time before returning.
     *
     * @param rateHz output data rate in Hz: 0 (power-down), 1, 10, 25, 50,
     *               100, 200, 400, 1344, 1620 or 5376. 1620 Hz and 5376 Hz
     *               exist in low-power mode only, 1344 Hz in normal /
     *               high-resolution mode only
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if the rate is unknown or not allowed
     *                                  in the current mode
     */
    public void setDataRate(int rateHz) throws IOException {
        int code = rateCode(rateHz);
        if (code < 0) {
            throw new IllegalArgumentException("LIS3DH setDataRate: rate must be one of "
                    + "0, 1, 10, 25, 50, 100, 200, 400, 1344, 1620, 5376, got " + rateHz);
        }
        if ((rateHz == 1620 || rateHz == 5376) && mode != MODE_LOW_POWER) {
            throw new IllegalArgumentException("LIS3DH setDataRate: rate " + rateHz
                    + " Hz is low-power mode only");
        }
        if (rateHz == 1344 && mode == MODE_LOW_POWER) {
            throw new IllegalArgumentException(
                    "LIS3DH setDataRate: rate 1344 Hz is not available in low-power mode");
        }
        int ctrl1 = readReg(REG_CTRL_REG1);
        writeReg(REG_CTRL_REG1, (ctrl1 & 0x0F) | code);
        this.rateHz = rateHz;
        sleepMs(settleMs());
    }

    /**
     * Set the operating mode (the resolution / power trade-off).
     *
     * <p>Read-modify-writes {@code LPen} in {@code CTRL_REG1} and {@code HR}
     * in {@code CTRL_REG4}, updates the cached resolution and waits for the
     * mode's turn-on time. A rate that does not exist in the requested mode
     * is rejected, so {@code LPen} = 1 with {@code HR} = 1 is never written.
     *
     * @param mode 0 = low-power (8-bit), 1 = normal (10-bit), 2 =
     *             high-resolution (12-bit)
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if the mode is unknown or the current
     *                                  data rate is illegal in that mode
     */
    public void setMode(int mode) throws IOException {
        if (mode != MODE_LOW_POWER && mode != MODE_NORMAL && mode != MODE_HIGH_RESOLUTION) {
            throw new IllegalArgumentException("LIS3DH setMode: mode must be 0 "
                    + "(low-power), 1 (normal) or 2 (high-resolution), got " + mode);
        }
        if (mode == MODE_LOW_POWER && rateHz == 1344) {
            throw new IllegalArgumentException(
                    "LIS3DH setMode: rate 1344 Hz is not available in low-power mode");
        }
        if (mode != MODE_LOW_POWER && (rateHz == 1620 || rateHz == 5376)) {
            throw new IllegalArgumentException("LIS3DH setMode: rate " + rateHz
                    + " Hz is low-power mode only");
        }
        int ctrl1 = readReg(REG_CTRL_REG1);
        int ctrl4 = readReg(REG_CTRL_REG4);
        if (mode == MODE_LOW_POWER) {
            ctrl1 |= 0x08;   // LPen = 1
            ctrl4 &= ~0x08;  // HR = 0 (LPen = 1 with HR = 1 is not allowed)
        } else {
            ctrl1 &= ~0x08;  // LPen = 0
            if (mode == MODE_HIGH_RESOLUTION) {
                ctrl4 |= 0x08;   // HR = 1
            } else {
                ctrl4 &= ~0x08;  // HR = 0
            }
        }
        writeReg(REG_CTRL_REG1, ctrl1);
        writeReg(REG_CTRL_REG4, ctrl4);
        this.mode = mode;
        sleepMs(settleMs());
    }

    /**
     * Enable or disable the individual measurement axes.
     *
     * @param x enable the X axis
     * @param y enable the Y axis
     * @param z enable the Z axis
     * @throws IOException on bus error
     */
    public void setAxes(boolean x, boolean y, boolean z) throws IOException {
        int ctrl1 = readReg(REG_CTRL_REG1);
        int axes = (x ? 0x01 : 0x00) | (y ? 0x02 : 0x00) | (z ? 0x04 : 0x00);
        writeReg(REG_CTRL_REG1, (ctrl1 & 0xF8) | axes);
    }

    /**
     * Stop the conversions by setting {@code ODR} = 0000; the register
     * contents are retained.
     *
     * @throws IOException on bus error
     */
    public void powerDown() throws IOException {
        int ctrl1 = readReg(REG_CTRL_REG1);
        writeReg(REG_CTRL_REG1, ctrl1 & 0x0F);
        this.rateHz = 0;
    }

    // -------------------------------------------------------------------------
    // Data reads
    // -------------------------------------------------------------------------

    /**
     * Read the raw acceleration counts at the current resolution.
     *
     * <p>The counts are signed and right-shifted for the current resolution:
     * 12-bit in high-resolution mode, 10-bit in normal mode, 8-bit in
     * low-power mode.
     *
     * @return array of three ints — X, Y, Z signed counts
     * @throws IOException on bus error
     */
    public int[] readRaw() throws IOException {
        byte[] raw = readBurst(REG_OUT_X_L, 6);
        return new int[]{
                decodeAxis(raw[0], raw[1]),
                decodeAxis(raw[2], raw[3]),
                decodeAxis(raw[4], raw[5])};
    }

    /**
     * Check whether a complete X/Y/Z sample set is ready.
     *
     * @return {@code STATUS_REG.ZYXDA} — true when all three axes have new
     *         data
     * @throws IOException on bus error
     */
    public boolean newDataAvailable() throws IOException {
        return (readReg(REG_STATUS) & 0x08) != 0;
    }

    /**
     * Read the {@code STATUS_REG} byte.
     *
     * @return the overrun ({@code ZYXOR}..{@code XOR}) and data-available
     *         ({@code ZYXDA}..{@code XDA}) flags
     * @throws IOException on bus error
     */
    public int readStatus() throws IOException {
        return readReg(REG_STATUS);
    }

    /**
     * Read the relative on-chip temperature.
     *
     * <p>Enables {@code ADC_EN} and {@code TEMP_EN} in {@code TEMP_CFG_REG} on
     * first use — the channel-3 conversions run through the same ADC — and
     * waits one ODR period for the first conversion.
     *
     * <p>The datasheet specifies only 1 digit/°C with no zero-degree
     * reference, so the value is a relative delta, never an absolute
     * temperature.
     *
     * @return relative temperature in °C (8-bit two's complement, 1 digit/°C)
     * @throws IOException on bus error
     */
    public int readTemperatureDelta() throws IOException {
        int cfg = readReg(REG_TEMP_CFG);
        if ((cfg & 0xC0) != 0xC0) {
            writeReg(REG_TEMP_CFG, cfg | 0xC0);
            if (rateHz > 0) {
                sleepMs((long) Math.ceil(1000.0 / rateHz));
            }
        }
        return Register.toSigned(readReg(REG_OUT_ADC3_H), 8);
    }

    /**
     * Read one auxiliary ADC channel.
     *
     * <p>The mapping is derived from the stated input range (1.200 V ±
     * 400 mV) — the datasheet gives no explicit voltage formula.
     *
     * @param channel 1, 2 or 3. Channel 3 is the temperature-sensor input
     *                while {@code TEMP_EN} = 1
     * @return the input voltage in V
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if the channel is not 1, 2 or 3, or if
     *                                  channel 3 is requested while
     *                                  {@code TEMP_EN} = 1
     */
    public double readAdc(int channel) throws IOException {
        if (channel < 1 || channel > 3) {
            throw new IllegalArgumentException(
                    "LIS3DH readAdc: channel must be 1, 2 or 3, got " + channel);
        }
        if (channel == 3) {
            int cfg = readReg(REG_TEMP_CFG);
            if ((cfg & 0x40) != 0) {
                throw new IllegalArgumentException(
                        "LIS3DH readAdc: channel 3 is the temperature sensor while TEMP_EN = 1");
            }
        }
        int base = REG_OUT_ADC1_L + (channel - 1) * 2;
        byte[] raw = readBurst(base, 2);
        short raw16 = (short) (((raw[1] & 0xFF) << 8) | (raw[0] & 0xFF));
        int counts = raw16 >> (mode == MODE_LOW_POWER ? 8 : 6);
        return 1.200 + counts * (0.400 / 512.0);
    }

    /**
     * Enable or disable the auxiliary ADC ({@code ADC_EN} in
     * {@code TEMP_CFG_REG}).
     *
     * @param enabled true to enable the ADC, false to disable it
     * @throws IOException on bus error
     */
    public void enableAdc(boolean enabled) throws IOException {
        int cfg = readReg(REG_TEMP_CFG);
        if (enabled) {
            cfg |= 0x80;
        } else {
            cfg &= ~0x80 & 0xFF;
        }
        writeReg(REG_TEMP_CFG, cfg);
    }

    // -------------------------------------------------------------------------
    // High-pass filter
    // -------------------------------------------------------------------------

    /**
     * Configure the high-pass filter ({@code CTRL_REG2}).
     *
     * @param mode         {@code HPM}: 0 normal (reset by reading
     *                     {@code REFERENCE}), 1 reference signal for
     *                     filtering, 2 normal, 3 auto-reset on interrupt event
     * @param cutoff       {@code HPCF<2:1>} code 0–3 (the datasheet tabulates
     *                     no Hz values; they scale with ODR)
     * @param filterOutput route the filtered data to the output registers and
     *                     FIFO ({@code FDS})
     * @param onIa1        apply the filter to interrupt generator 1
     * @param onIa2        apply the filter to interrupt generator 2
     * @param onClick      apply the filter to the click function
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if {@code mode} or {@code cutoff} is
     *                                  outside 0–3
     */
    public void setHighPass(int mode, int cutoff, boolean filterOutput,
                            boolean onIa1, boolean onIa2, boolean onClick) throws IOException {
        if (mode < 0 || mode > 3) {
            throw new IllegalArgumentException("LIS3DH setHighPass: mode must be 0-3");
        }
        if (cutoff < 0 || cutoff > 3) {
            throw new IllegalArgumentException("LIS3DH setHighPass: cutoff must be 0-3");
        }
        int value = (mode << 6) | (cutoff << 4);
        if (filterOutput) value |= 0x08;
        if (onClick) value |= 0x04;
        if (onIa2) value |= 0x02;
        if (onIa1) value |= 0x01;
        writeReg(REG_CTRL_REG2, value);
    }

    /**
     * Reset the high-pass filter by reading {@code REFERENCE} ({@code HPM} =
     * 00).
     *
     * @return the {@code REFERENCE} register value that was read
     * @throws IOException on bus error
     */
    public int resetHighPass() throws IOException {
        return readReg(REG_REFERENCE);
    }

    /**
     * Write the high-pass reference value ({@code HPM} = 01).
     *
     * @param value 0–255
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if {@code value} is outside 0–255
     */
    public void setReference(int value) throws IOException {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException("LIS3DH setReference: value must be 0-255");
        }
        writeReg(REG_REFERENCE, value);
    }

    // -------------------------------------------------------------------------
    // FIFO
    // -------------------------------------------------------------------------

    /**
     * Configure the 32-level FIFO ({@code FIFO_CTRL_REG}).
     *
     * <p>Sets {@code FIFO_EN} in {@code CTRL_REG5} for modes 1–3; returning
     * to bypass (mode 0) clears the buffer. Only bypass mode empties the
     * buffer — restart a FIFO mode through bypass after a full drain.
     *
     * @param mode        0 = bypass, 1 = FIFO, 2 = stream, 3 = stream-to-FIFO
     * @param watermark   watermark level {@code FTH}, 0–31
     * @param triggerInt2 trigger source for stream-to-FIFO: false = INT1,
     *                    true = INT2 ({@code TR})
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if {@code mode} is not 0–3 or
     *                                  {@code watermark} is outside 0–31
     */
    public void setFifo(int mode, int watermark, boolean triggerInt2) throws IOException {
        if (mode < 0 || mode > 3) {
            throw new IllegalArgumentException("LIS3DH setFifo: mode must be 0 "
                    + "(bypass), 1 (FIFO), 2 (stream) or 3 (stream-to-FIFO)");
        }
        if (watermark < 0 || watermark > 31) {
            throw new IllegalArgumentException("LIS3DH setFifo: watermark must be 0-31");
        }
        int value = (mode << 6) | (triggerInt2 ? 0x20 : 0x00) | watermark;
        writeReg(REG_FIFO_CTRL, value);
        int ctrl5 = readReg(REG_CTRL_REG5);
        if (mode == 0) {
            ctrl5 &= ~0x40 & 0xFF;
        } else {
            ctrl5 |= 0x40;
        }
        writeReg(REG_CTRL_REG5, ctrl5);
    }

    /**
     * Read the number of unread sample sets in the FIFO.
     *
     * @return {@code FSS<4:0>} — 0–32 unread sample sets
     * @throws IOException on bus error
     */
    public int fifoLevel() throws IOException {
        return readReg(REG_FIFO_SRC) & 0x1F;
    }

    /**
     * Drain every sample set currently held in the FIFO.
     *
     * @return the sample sets in <i>g</i>, oldest sample first
     * @throws IOException on bus error
     */
    public List<double[]> readFifo() throws IOException {
        return readFifoSamples(fifoLevel());
    }

    /**
     * Drain at most {@code count} sample sets from the FIFO.
     *
     * <p>Each sample set is one 6-byte burst of 0x28–0x2D; the address
     * auto-increment wraps back to 0x28, so a full drain is 32 reads. Never
     * reads more than the FIFO actually holds.
     *
     * @param count maximum number of sample sets to read
     * @return the sample sets in <i>g</i>, oldest sample first
     * @throws IOException on bus error
     */
    public List<double[]> readFifo(int count) throws IOException {
        return readFifoSamples(Math.min(count, fifoLevel()));
    }

    private List<double[]> readFifoSamples(int n) throws IOException {
        List<double[]> samples = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            byte[] raw = readBurst(REG_OUT_X_L, 6);
            samples.add(new double[]{
                    scale(decodeAxis(raw[0], raw[1])),
                    scale(decodeAxis(raw[2], raw[3])),
                    scale(decodeAxis(raw[4], raw[5]))});
        }
        return samples;
    }

    /**
     * Decode {@code FIFO_SRC_REG}.
     *
     * @return the watermark, overrun and empty flags plus the number of
     *         unread sample sets
     * @throws IOException on bus error
     */
    public FifoStatus readFifoStatus() throws IOException {
        int raw = readReg(REG_FIFO_SRC);
        return new FifoStatus((raw & 0x80) != 0, (raw & 0x40) != 0,
                (raw & 0x20) != 0, raw & 0x1F);
    }

    // -------------------------------------------------------------------------
    // Inertial interrupt generators
    // -------------------------------------------------------------------------

    /**
     * Configure an inertial interrupt generator (motion / threshold).
     *
     * @param generator   1 or 2 — the {@code INT1} or {@code INT2} register block
     * @param events      6-bit {@code ZHIE}..{@code XLIE} event-enable mask
     * @param thresholdG threshold in <i>g</i>, scaled with the current
     *                   full-scale range (16 / 32 / 62 / 186 mg per LSb)
     * @param durationMs minimum duration the events must hold, in ms
     *                   ({@code INTx_DURATION}, 1 LSb = 1/ODR)
     * @param andMode     true for an AND combination of the enabled events
     *                    ({@code AOI} = 1), false for OR
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if the generator is not 1 or 2, or the
     *                                  event mask is outside 0–0x3F
     */
    public void setMotionInterrupt(int generator, int events, double thresholdG,
                                   double durationMs, boolean andMode) throws IOException {
        if (events < 0 || events > 0x3F) {
            throw new IllegalArgumentException(
                    "LIS3DH setMotionInterrupt: events must be a 6-bit mask (0-0x3F)");
        }
        int[] regs = generatorRegs(generator);
        int cfg = (andMode ? 0x80 : 0x00) | (events & 0x3F);
        writeReg(regs[0], cfg);
        writeReg(regs[1], thresholdCode(thresholdG));
        writeReg(regs[2], durationCode(durationMs, 127));
    }

    /**
     * Configure the free-fall detector: all three axes low, AND mode.
     *
     * <p>Convenience wrapper around the generator's {@code INTx_CFG} = 0x95
     * ({@code AOI} + {@code XLIE}/{@code YLIE}/{@code ZLIE}).
     *
     * @param generator   1 or 2
     * @param thresholdG free-fall threshold in <i>g</i>
     * @param durationMs minimum time below the threshold, in ms
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if the generator is not 1 or 2
     */
    public void setFreeFall(int generator, double thresholdG, double durationMs) throws IOException {
        int[] regs = generatorRegs(generator);
        writeReg(regs[0], 0x95);
        writeReg(regs[1], thresholdCode(thresholdG));
        writeReg(regs[2], durationCode(durationMs, 127));
    }

    /**
     * Configure 6D / 4D orientation detection.
     *
     * <p>{@code AOI} / {@code 6D} = 01 is <em>movement</em> recognition (a
     * one-ODR pulse), 11 is <em>position</em> recognition (level, active while
     * the orientation is inside a known zone).
     *
     * @param generator 1 or 2
     * @param position  false = 6D movement recognition, true = 6D position
     *                  recognition
     * @param fourD     enable 4D detection ({@code D4D_INTx}) — disables the
     *                  Z-axis zones
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if the generator is not 1 or 2
     */
    public void setOrientation(int generator, boolean position, boolean fourD) throws IOException {
        int[] regs = generatorRegs(generator);
        int axisZones = fourD ? 0x0F : 0x3F;
        int cfg = (position ? 0xC0 : 0x40) | axisZones;
        writeReg(regs[0], cfg);
        int ctrl5 = readReg(REG_CTRL_REG5);
        int d4dBit = generator == 1 ? 0x04 : 0x01;
        if (fourD) {
            ctrl5 |= d4dBit;
        } else {
            ctrl5 &= ~d4dBit & 0xFF;
        }
        writeReg(REG_CTRL_REG5, ctrl5);
    }

    /**
     * Latch a generator's interrupt until its {@code INTx_SRC} is read.
     *
     * @param generator 1 or 2
     * @param enabled   true to latch ({@code LIR_INTx}), false for a
     *                  self-resetting interrupt
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if the generator is not 1 or 2
     */
    public void setLatch(int generator, boolean enabled) throws IOException {
        if (generator != 1 && generator != 2) {
            throw new IllegalArgumentException(
                    "LIS3DH setLatch: generator must be 1 or 2");
        }
        int bit = generator == 1 ? 0x08 : 0x02;
        int ctrl5 = readReg(REG_CTRL_REG5);
        if (enabled) {
            ctrl5 |= bit;
        } else {
            ctrl5 &= ~bit & 0xFF;
        }
        writeReg(REG_CTRL_REG5, ctrl5);
    }

    /**
     * Read a generator's {@code INTx_SRC} — <b>clears</b> the interrupt.
     *
     * @param generator 1 or 2
     * @return the {@code INTx_SRC} byte ({@code IA} = bit 6, per-axis event
     *         flags in bits 5–0)
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if the generator is not 1 or 2
     */
    public int readInterruptSource(int generator) throws IOException {
        if (generator != 1 && generator != 2) {
            throw new IllegalArgumentException(
                    "LIS3DH readInterruptSource: generator must be 1 or 2");
        }
        return readReg(generator == 1 ? REG_INT1_SRC : REG_INT2_SRC);
    }

    // -------------------------------------------------------------------------
    // Click detection
    // -------------------------------------------------------------------------

    /**
     * Configure single / double click detection ({@code CLICK_*}).
     *
     * @param single      axis mask of the single-click enables (X = 1, Y = 2, Z = 4)
     * @param doubleClick axis mask of the double-click enables (X = 1, Y = 2, Z = 4)
     * @param thresholdG  click threshold in <i>g</i> (same scale as {@code INTx_THS})
     * @param timeLimitMs maximum time between the two clicks of a double click
     * @param latencyMs   minimum time between two consecutive clicks
     * @param windowMs    maximum time after the first click of a double click
     * @param latch       keep the interrupt high until {@code CLICK_SRC} is read
     *                    ({@code LIR_Click})
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if either axis mask is outside 0–7
     */
    public void setClick(int single, int doubleClick, double thresholdG,
                         double timeLimitMs, double latencyMs, double windowMs,
                         boolean latch) throws IOException {
        if (single < 0 || single > 7 || doubleClick < 0 || doubleClick > 7) {
            throw new IllegalArgumentException(
                    "LIS3DH setClick: single / double must be axis masks (0-7: X=1, Y=2, Z=4)");
        }
        int cfg = (single & 0x01)
                | ((single & 0x02) << 1)
                | ((single & 0x04) << 2)
                | ((doubleClick & 0x01) << 1)
                | ((doubleClick & 0x02) << 2)
                | ((doubleClick & 0x04) << 3);
        int ths = thresholdCode(thresholdG);
        writeReg(REG_CLICK_CFG, cfg);
        writeReg(REG_CLICK_THS, ths | (latch ? 0x80 : 0x00));
        writeReg(REG_TIME_LIMIT, durationCode(timeLimitMs, 127));
        writeReg(REG_TIME_LATENCY, durationCode(latencyMs, 255));
        writeReg(REG_TIME_WINDOW, durationCode(windowMs, 255));
    }

    /**
     * Read {@code CLICK_SRC} — <b>clears</b> the click interrupt.
     *
     * @return the {@code CLICK_SRC} byte ({@code IA} = bit 6, {@code DCLICK} =
     *         bit 5, {@code SCLICK} = bit 4, {@code Sign} = bit 3, axis flags
     *         in bits 2–0)
     * @throws IOException on bus error
     */
    public int readClickSource() throws IOException {
        return readReg(REG_CLICK_SRC);
    }

    // -------------------------------------------------------------------------
    // Sleep-to-wake activity
    // -------------------------------------------------------------------------

    /**
     * Configure the sleep-to-wake / return-to-sleep activity function.
     *
     * <p>While the function has dropped the chip into its 10 Hz low-power
     * state the {@code ODR}, {@code LPen} and {@code HR} settings are
     * ignored; the chip restores them on activity. Pair with
     * {@link Lis3dhSource#SOURCE_ACTIVITY}.
     *
     * @param thresholdG activation threshold in <i>g</i> ({@code ACT_THS})
     * @param durationMs activation duration in ms ({@code ACT_DUR}, where
     *                   1 LSb = (8·{@code ACT_DUR} + 1)/ODR)
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if the threshold rounds to 0 counts
     *                                  ({@code ACT_THS} must be non-zero)
     */
    public void setActivity(double thresholdG, double durationMs) throws IOException {
        int code = thresholdCode(thresholdG);
        if (code == 0) {
            throw new IllegalArgumentException(
                    "LIS3DH setActivity: threshold_g is below 1 LSb — ACT_THS must be non-zero");
        }
        int dur = roundHalfAwayFromZero((durationMs * rateHz / 1000.0 - 1) / 8.0);
        dur = Math.max(0, Math.min(255, dur));
        writeReg(REG_ACT_THS, code);
        writeReg(REG_ACT_DUR, dur);
    }

    // -------------------------------------------------------------------------
    // Interrupt routing and delivery
    // -------------------------------------------------------------------------

    /**
     * Set the shared INT1 / INT2 output polarity ({@code INT_POLARITY}).
     *
     * @param activeLow true for active-low, false for active-high (the reset
     *                  default)
     * @throws IOException on bus error
     */
    public void setInterruptPolarity(boolean activeLow) throws IOException {
        int ctrl6 = readReg(REG_CTRL_REG6);
        if (activeLow) {
            ctrl6 |= 0x02;
        } else {
            ctrl6 &= ~0x02 & 0xFF;
        }
        writeReg(REG_CTRL_REG6, ctrl6);
    }

    /**
     * Route one interrupt source to INT{@code pin}.
     *
     * <p>Only routes the source — the generator or function behind it is
     * configured with the {@code set*} methods. {@code pin} is ignored for
     * the INT1-only (data-ready, aux data-ready, watermark, overrun) and
     * INT2-only (activity, boot) sources.
     *
     * @param source one of the {@link Lis3dhSource} constants
     * @param pin    1 or 2
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if the source is unknown or the pin is
     *                                  not 1 or 2
     */
    public void enableInterrupt(int source, int pin) throws IOException {
        int[] route = route(source, pin);
        int value = readReg(route[0]);
        writeReg(route[0], value | route[1]);
        if (route[2] == 1) {
            enabled1 |= 1 << sourceBit(source);
        } else {
            enabled2 |= 1 << sourceBit(source);
        }
    }

    /**
     * Remove one interrupt source from INT{@code pin}.
     *
     * @param source one of the {@link Lis3dhSource} constants
     * @param pin    1 or 2
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if the source is unknown or the pin is
     *                                  not 1 or 2
     */
    public void disableInterrupt(int source, int pin) throws IOException {
        int[] route = route(source, pin);
        int value = readReg(route[0]);
        writeReg(route[0], value & ~route[1] & 0xFF);
        if (route[2] == 1) {
            enabled1 &= ~(1 << sourceBit(source));
        } else {
            enabled2 &= ~(1 << sourceBit(source));
        }
    }

    /**
     * Subscribe to the chip's INT line.
     *
     * <p>The callback receives the combined status returned by
     * {@link #pollInterrupt()} and is only invoked for a non-zero status.
     * Delivery uses the connection's {@code intPin()} when one is wired and
     * falls back to a 5 ms polling thread otherwise.
     *
     * @param callback called with the combined interrupt status
     */
    public void onInterrupt(IntConsumer callback) {
        this.callback = callback;
        InputPin pin = connection.intPin();
        if (pin != null) {
            intPin = pin;
            pin.onEdge(edgeHandler, EdgeTrigger.FALLING);
        } else {
            startPolling();
        }
    }

    /** Unsubscribe and stop the interrupt delivery. */
    public void offInterrupt() {
        callback = null;
        if (intPin != null) {
            intPin.offEdge(edgeHandler);
            intPin = null;
        }
        stopPolling();
    }

    /**
     * Read the source registers of every currently enabled source and return
     * the combined status.
     *
     * <p><b>Reading {@code INT1_SRC} / {@code INT2_SRC} / {@code CLICK_SRC}
     * clears their latched flags</b> (and the INT pin).
     *
     * @return the combined status — bit 0 {@link Lis3dhSource#SOURCE_DATA_READY},
     *         bit 1 {@link Lis3dhSource#SOURCE_AUX_DATA_READY}, bit 2
     *         {@link Lis3dhSource#SOURCE_WATERMARK}, bit 3
     *         {@link Lis3dhSource#SOURCE_OVERRUN}, bit 4
     *         {@link Lis3dhSource#SOURCE_IA1}, bit 5
     *         {@link Lis3dhSource#SOURCE_IA2}, bit 6
     *         {@link Lis3dhSource#SOURCE_CLICK}. Sources that are not enabled
     *         read as 0
     * @throws IOException on bus error
     */
    public int pollInterrupt() throws IOException {
        int e1 = enabled1;
        int e2 = enabled2;
        int enabled = e1 | e2;
        int status = 0;
        // The INT1-only sources are routed through CTRL_REG3.
        if ((e1 & (1 << SOURCE_BIT_DATA_READY)) != 0 && (readReg(REG_STATUS) & 0x08) != 0) {
            status |= Lis3dhSource.SOURCE_DATA_READY;
        }
        if ((e1 & (1 << SOURCE_BIT_AUX_DATA_READY)) != 0
                && (readReg(REG_STATUS_AUX) & 0x08) != 0) {
            status |= Lis3dhSource.SOURCE_AUX_DATA_READY;
        }
        if ((e1 & (1 << SOURCE_BIT_WATERMARK)) != 0
                || (e1 & (1 << SOURCE_BIT_OVERRUN)) != 0) {
            int fifo = readReg(REG_FIFO_SRC);
            if ((e1 & (1 << SOURCE_BIT_WATERMARK)) != 0 && (fifo & 0x80) != 0) {
                status |= Lis3dhSource.SOURCE_WATERMARK;
            }
            if ((e1 & (1 << SOURCE_BIT_OVERRUN)) != 0 && (fifo & 0x40) != 0) {
                status |= Lis3dhSource.SOURCE_OVERRUN;
            }
        }
        // The generator flags live in their own source register, no matter
        // which pin the generator is routed to.
        if ((enabled & (1 << SOURCE_BIT_IA1)) != 0
                && (readReg(REG_INT1_SRC) & 0x40) != 0) {
            status |= Lis3dhSource.SOURCE_IA1;
        }
        if ((enabled & (1 << SOURCE_BIT_IA2)) != 0
                && (readReg(REG_INT2_SRC) & 0x40) != 0) {
            status |= Lis3dhSource.SOURCE_IA2;
        }
        if ((enabled & (1 << SOURCE_BIT_CLICK)) != 0
                && (readReg(REG_CLICK_SRC) & 0x40) != 0) {
            status |= Lis3dhSource.SOURCE_CLICK;
        }
        return status;
    }

    // -------------------------------------------------------------------------
    // System
    // -------------------------------------------------------------------------

    /**
     * Reboot the memory content ({@code BOOT} = 1) and re-run the
     * Initialization Sequence.
     *
     * <p>Reloads the factory trim and waits 5 ms. The datasheet does not
     * say whether the control registers survive, so the Initialization
     * Sequence is re-run afterwards and the configuration returns to the
     * Minimal defaults.
     *
     * @throws IOException on bus error
     */
    public void reboot() throws IOException {
        int ctrl5 = readReg(REG_CTRL_REG5);
        writeReg(REG_CTRL_REG5, ctrl5 | 0x80);
        sleepMs(BOOT_DELAY_MS);
        this.rangeG = RANGE_2G;
        this.mode = MODE_HIGH_RESOLUTION;
        this.rateHz = 100;
        enabled1 = 0;
        enabled2 = 0;
        runInitSequence();
    }

    /**
     * Connect or disconnect the pull-up on SDO/SA0 ({@code CTRL_REG0}).
     *
     * <p>{@code CTRL_REG0} is not a normal register: bit 4 must stay 1 and
     * the other bits except {@code SDO_PU_DISC} must stay 0, so the register
     * is written with the forced values rather than read-modify-written.
     *
     * @param connected true to connect the pull-up
     *                  ({@code SDO_PU_DISC} = 0), false to disconnect it
     * @throws IOException on bus error
     */
    public void setSdoPullup(boolean connected) throws IOException {
        int value = 0x10 | (connected ? 0x00 : 0x80);
        writeReg(REG_CTRL_REG0, value);
    }

    /**
     * Run the datasheet electrostatic self-test.
     *
     * <p>Configures ±2 *g*, normal (10-bit) mode, 50 Hz with {@code BDU} on,
     * averages a few samples with {@code ST} = 00, enables {@code ST}
     * (01 = self-test 0, 10 = self-test 1), waits 2 samples and averages
     * again. Every axis must show a 17–360 LSb change (1 LSb = 4 mg at
     * 10-bit / ±2 *g*). The previous {@code CTRL_REG1} / {@code CTRL_REG4}
     * configuration is restored afterwards.
     *
     * @param mode 1 = self-test 0, 2 = self-test 1
     * @return true if every axis showed a change inside 17–360 LSb
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if {@code mode} is not 1 or 2
     */
    public boolean selfTest(int mode) throws IOException {
        if (mode != 1 && mode != 2) {
            throw new IllegalArgumentException(
                    "LIS3DH selfTest: mode must be 1 (self-test 0) or 2 (self-test 1), got " + mode);
        }
        int savedCtrl1 = readReg(REG_CTRL_REG1);
        int savedCtrl4 = readReg(REG_CTRL_REG4);
        int savedRange = this.rangeG;
        int savedMode = this.mode;
        int savedRate = this.rateHz;
        try {
            configureSelfTest(0x00);
            sleepMs(10);
            double[] before = averageRaw(5, 20);
            configureSelfTest(mode == 1 ? 0x02 : 0x04);
            sleepMs(40);  // 2 samples at 50 Hz
            double[] after = averageRaw(5, 20);
            for (int i = 0; i < 3; i++) {
                double delta = Math.abs(after[i] - before[i]);
                if (delta < 17 || delta > 360) return false;
            }
            return true;
        } finally {
            writeReg(REG_CTRL_REG1, savedCtrl1);
            writeReg(REG_CTRL_REG4, savedCtrl4);
            this.rangeG = savedRange;
            this.mode = savedMode;
            this.rateHz = savedRate;
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void configureSelfTest(int stBits) throws IOException {
        writeReg(REG_CTRL_REG4, 0x80 | stBits);
        writeReg(REG_CTRL_REG1, 0x47);  // 50 Hz, LPen = 0, X/Y/Z enabled
        this.rangeG = RANGE_2G;
        this.mode = MODE_NORMAL;
        this.rateHz = 50;
    }

    private double[] averageRaw(int samples, int delayMs) throws IOException {
        double[] total = new double[3];
        for (int i = 0; i < samples; i++) {
            int[] raw = readRaw();
            total[0] += raw[0];
            total[1] += raw[1];
            total[2] += raw[2];
            sleepMs(delayMs);
        }
        for (int i = 0; i < 3; i++) {
            total[i] /= samples;
        }
        return total;
    }

    private int[] generatorRegs(int generator) {
        if (generator == 1) {
            return new int[]{REG_INT1_CFG, REG_INT1_THS, REG_INT1_DUR};
        }
        if (generator == 2) {
            return new int[]{REG_INT2_CFG, REG_INT2_THS, REG_INT2_DUR};
        }
        throw new IllegalArgumentException("LIS3DH: generator must be 1 or 2, got " + generator);
    }

    /**
     * Interrupt-threshold LSB in mg for the current full-scale range
     * (datasheet scale, full-scale / 128).
     */
    private int thresholdLsbMg() {
        switch (rangeG) {
            case RANGE_4G:  return 32;
            case RANGE_8G:  return 62;
            case RANGE_16G: return 186;
            default:        return 16;
        }
    }

    private int thresholdCode(double thresholdG) {
        int code = roundHalfAwayFromZero(thresholdG * 1000.0 / thresholdLsbMg());
        return Math.max(0, Math.min(127, code));
    }

    private int durationCode(double durationMs, int limit) {
        // 1 LSb = 1/ODR; while powered down there is no ODR to convert with,
        // so the code is 0.
        if (rateHz <= 0) return 0;
        int code = roundHalfAwayFromZero(durationMs * rateHz / 1000.0);
        return Math.max(0, Math.min(limit, code));
    }

    private static int rangeIndex(int rangeG) {
        switch (rangeG) {
            case RANGE_2G:  return 0;
            case RANGE_4G:  return 1;
            case RANGE_8G:  return 2;
            case RANGE_16G: return 3;
            default:        return -1;
        }
    }

    private static int rateCode(int rateHz) {
        switch (rateHz) {
            case 0:    return 0x00;
            case 1:    return 0x10;
            case 10:   return 0x20;
            case 25:   return 0x30;
            case 50:   return 0x40;
            case 100:  return 0x50;
            case 200:  return 0x60;
            case 400:  return 0x70;
            case 1620: return 0x80;
            case 1344: return 0x90;
            case 5376: return 0x90;
            default:   return -1;
        }
    }

    private static int sourceBit(int source) {
        int bit = 0;
        int value = source;
        while (value > 1) {
            value >>= 1;
            bit++;
        }
        return bit;
    }

    /**
     * Return the routing of a source: the register, the mask inside it and
     * the pin whose bookkeeping tracks it (1 = INT1, 2 = INT2).
     */
    private int[] route(int source, int pin) {
        if (pin != 1 && pin != 2) {
            throw new IllegalArgumentException("LIS3DH: pin must be 1 or 2, got " + pin);
        }
        switch (source) {
            case Lis3dhSource.SOURCE_DATA_READY:
                return new int[]{REG_CTRL_REG3, 0x10, 1};
            case Lis3dhSource.SOURCE_AUX_DATA_READY:
                return new int[]{REG_CTRL_REG3, 0x08, 1};
            case Lis3dhSource.SOURCE_WATERMARK:
                return new int[]{REG_CTRL_REG3, 0x04, 1};
            case Lis3dhSource.SOURCE_OVERRUN:
                return new int[]{REG_CTRL_REG3, 0x02, 1};
            case Lis3dhSource.SOURCE_IA1:
                return new int[]{pin == 1 ? REG_CTRL_REG3 : REG_CTRL_REG6, 0x40, pin};
            case Lis3dhSource.SOURCE_IA2:
                return new int[]{pin == 1 ? REG_CTRL_REG3 : REG_CTRL_REG6, 0x20, pin};
            case Lis3dhSource.SOURCE_CLICK:
                return new int[]{pin == 1 ? REG_CTRL_REG3 : REG_CTRL_REG6, 0x80, pin};
            case Lis3dhSource.SOURCE_ACTIVITY:
                return new int[]{REG_CTRL_REG6, 0x08, 2};
            case Lis3dhSource.SOURCE_BOOT:
                return new int[]{REG_CTRL_REG6, 0x10, 2};
            default:
                throw new IllegalArgumentException("LIS3DH: unknown interrupt source 0x"
                        + Integer.toHexString(source));
        }
    }

    private void startPolling() {
        polling = true;
        pollThread = new Thread(() -> {
            while (polling) {
                handleEdge();
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "lis3dh-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void stopPolling() {
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    private void handleEdge() {
        try {
            int status = pollInterrupt();
            if (status == 0) return;
            IntConsumer cb = callback;
            if (cb != null) cb.accept(status);
        } catch (IOException ignored) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }

    /**
     * Decoded {@code FIFO_SRC_REG}.
     */
    public static final class FifoStatus {

        /** True when the FIFO content exceeds the watermark ({@code WTM}). */
        public final boolean watermark;

        /** True when the FIFO is full and overwrites the oldest sample ({@code OVRN_FIFO}). */
        public final boolean overrun;

        /** True when all samples have been read ({@code EMPTY}). */
        public final boolean empty;

        /** Number of unread sample sets ({@code FSS<4:0>}), 0–32. */
        public final int level;

        /**
         * @param watermark the watermark flag
         * @param overrun   the overrun flag
         * @param empty     the empty flag
         * @param level     the number of unread sample sets
         */
        public FifoStatus(boolean watermark, boolean overrun, boolean empty, int level) {
            this.watermark = watermark;
            this.overrun = overrun;
            this.empty = empty;
            this.level = level;
        }
    }
}
