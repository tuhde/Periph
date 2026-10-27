package it.uhde.periph.chips.accelerometer

import groovy.transform.CompileStatic
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection

import java.util.concurrent.ConcurrentHashMap
import java.util.function.IntConsumer

/**
 * LSM303DLHC full interface: extends LSM303Minimal with range, data-rate and mode selection,
 * high-pass filter, 32-level FIFO, two inertial interrupt generators, single and double click
 * detection, interrupt routing (INT1 / INT2 / DRDY), the magnetometer configuration, relative
 * temperature, and software compass heading.
 *
 * Interrupts are delivered per INT line. onInterrupt(callback) uses the accelerometer
 * connection's InputPin for INT1; INT2 needs an explicit pin passed to
 * onInterrupt(callback, pin, intPin). With no pin wired the driver falls back to a 5 ms
 * polling thread. The callback receives the status value from pollInterrupt() and only fires
 * when that value is non-zero.
 *
 * The chip's INTx lines are active-high by default; setInterruptPolarity(true) makes the
 * driver trigger on falling edges.
 */
@CompileStatic
class LSM303Full extends LSM303Minimal {

    /** Status bit: new accelerometer sample (STATUS_REG_A.ZYXDA). INT1 only. */
    static final int SOURCE_DATA_READY = 0x0001
    /** Status bit: FIFO content exceeds watermark (FIFO_SRC_REG_A.WTM). INT1 only. */
    static final int SOURCE_WATERMARK = 0x0002
    /** Status bit: FIFO overrun (FIFO_SRC_REG_A.OVRN_FIFO). INT1 only. */
    static final int SOURCE_OVERRUN = 0x0004
    /** Status bit: inertial generator 1 event (INT1_SOURCE_A.IA). INT1 or INT2. */
    static final int SOURCE_IA1 = 0x0008
    /** Status bit: inertial generator 2 event (INT2_SOURCE_A.IA). INT1 or INT2. */
    static final int SOURCE_IA2 = 0x0010
    /** Status bit: single or double click (CLICK_SRC_A.IA). INT1 or INT2. */
    static final int SOURCE_CLICK = 0x0020
    /** Boot procedure running. INT2 only; pin-only, never reported by pollInterrupt(). */
    static final int SOURCE_BOOT = 0x0040
    /** New magnetometer sample (SR_REG_M.DRDY). Always on the DRDY pin; not routable. */
    static final int SOURCE_MAG_DATA_READY = 0x0080

    private static final int CTRL1_LPEN = 0x08
    private static final int CTRL4_HR = 0x08
    private static final int CTRL4_FS_MASK = 0x30
    private static final int CTRL5_FIFO_EN = 0x40
    private static final int CTRL5_BOOT = 0x80
    private static final int CTRL5_LIR_INT1 = 0x08
    private static final int CTRL5_D4D_INT1 = 0x04
    private static final int CTRL5_LIR_INT2 = 0x02
    private static final int CTRL5_D4D_INT2 = 0x01
    private static final int CTRL6_BOOT_I1 = 0x10
    private static final int CTRL6_H_LACTIVE = 0x02

    /** Bitmask of sources routed to an INT line by enableInterrupt(). */
    private int enabledSources = 0
    /** Mirrors H_LACTIVE; selects the edge trigger used by onInterrupt(). */
    private boolean activeLow = false

    private final Map<Integer, IntConsumer> callbacks = new ConcurrentHashMap<Integer, IntConsumer>()
    private final EdgeHandler[] edgeHandlers = new EdgeHandler[3]
    private final InputPin[] armedPins = new InputPin[3]
    private final Thread[] pollThreads = new Thread[3]

    /**
     * Construct the driver and run the initialisation sequence.
     *
     * @param accelConnection register connection to the accelerometer (address 0x19)
     * @param magConnection   register connection to the magnetometer (address 0x1E)
     * @throws IOException on bus error or if the magnetometer identity does not match
     */
    LSM303Full(RegisterConnection accelConnection, RegisterConnection magConnection) throws IOException {
        super(accelConnection, magConnection)
    }

    // =====================================================================
    // Accelerometer configuration
    // =====================================================================

    /**
     * Set the full-scale range.
     *
     * @param rangeG full scale in g: 2, 4, 8 or 16
     * @throws IllegalArgumentException if rangeG is not one of the allowed values
     */
    void setRange(int rangeG) throws IOException {
        int code
        switch (rangeG) {
            case 2: code = 0; break
            case 4: code = 1; break
            case 8: code = 2; break
            case 16: code = 3; break
            default: throw new IllegalArgumentException('range must be 2, 4, 8 or 16 g')
        }
        accelUpdate(REG_CTRL_REG4_A, CTRL4_FS_MASK, code << 4)
        accelFs = code
    }

    /**
     * Set the output data rate.
     *
     * 1620 Hz and 5376 Hz are valid only in low-power mode; 1344 Hz is valid only in normal or
     * high-resolution mode (they share the ODR code).
     *
     * @param rateHz 0 (power-down), 1, 10, 25, 50, 100, 200, 400, 1344, 1620 or 5376
     * @throws IllegalArgumentException if the rate is unknown or not allowed in the current mode
     */
    void setDataRate(int rateHz) throws IOException {
        int code
        switch (rateHz) {
            case 0: code = 0x0; break
            case 1: code = 0x1; break
            case 10: code = 0x2; break
            case 25: code = 0x3; break
            case 50: code = 0x4; break
            case 100: code = 0x5; break
            case 200: code = 0x6; break
            case 400: code = 0x7; break
            case 1620:
                requireMode(0, '1620 Hz is low-power only')
                code = 0x8
                break
            case 1344:
                if (accelMode == 0) throw new IllegalArgumentException('1344 Hz is normal / high-resolution only')
                code = 0x9
                break
            case 5376:
                requireMode(0, '5376 Hz is low-power only')
                code = 0x9
                break
            default: throw new IllegalArgumentException("unsupported data rate: " + rateHz)
        }
        accelUpdate(REG_CTRL_REG1_A, 0xF0, code << 4)
        odrHz = rateHz
    }

    private void requireMode(int mode, String message) {
        if (accelMode != mode) throw new IllegalArgumentException(message)
    }

    /**
     * Select the accelerometer operating mode.
     *
     * Maps to LPen / HR: 0 low-power (LPen=1, HR=0), 1 normal (LPen=0, HR=0), 2 high-resolution
     * (LPen=0, HR=1). Changes the resolution used by readAcceleration() and readFifo().
     *
     * @param mode 0 low-power, 1 normal, 2 high-resolution
     * @throws IllegalArgumentException if mode is out of range
     */
    void setMode(int mode) throws IOException {
        if (mode < 0 || mode > 2) throw new IllegalArgumentException('mode must be 0, 1 or 2')
        accelUpdate(REG_CTRL_REG1_A, CTRL1_LPEN, mode == 0 ? CTRL1_LPEN : 0)
        accelUpdate(REG_CTRL_REG4_A, CTRL4_HR, mode == 2 ? CTRL4_HR : 0)
        accelMode = mode
    }

    /**
     * Enable or disable individual accelerometer axes.
     *
     * @param x enable the X axis (default true)
     * @param y enable the Y axis (default true)
     * @param z enable the Z axis (default true)
     */
    void setAxes(boolean x = true, boolean y = true, boolean z = true) throws IOException {
        int bits = (x ? 0x01 : 0) | (y ? 0x02 : 0) | (z ? 0x04 : 0)
        accelUpdate(REG_CTRL_REG1_A, 0x07, bits)
    }

    /** Put the accelerometer into power-down (ODR = 0000). Register contents are retained. */
    void powerDown() throws IOException {
        accelUpdate(REG_CTRL_REG1_A, 0xF0, 0)
        odrHz = 0
    }

    /**
     * Read the raw accelerometer counts at the current resolution.
     *
     * @return [x, y, z] signed counts (right-shifted by the current mode)
     */
    int[] readAccelerationRaw() throws IOException {
        return accelCounts(accel.read(REG_OUT_X_L_A | ACCEL_AUTO_INC, 6))
    }

    /** @return true if a new accelerometer sample is available (STATUS_REG_A.ZYXDA) */
    boolean newDataAvailable() throws IOException {
        return (accelRead(REG_STATUS_REG_A) & 0x08) != 0
    }

    /**
     * @return STATUS_REG_A byte: ZYXOR 7, ZOR 6, YOR 5, XOR 4, ZYXDA 3, ZDA 2, YDA 1, XDA 0
     */
    int readStatus() throws IOException {
        return accelRead(REG_STATUS_REG_A)
    }

    // =====================================================================
    // High-pass filter
    // =====================================================================

    /**
     * Configure the high-pass filter (CTRL_REG2_A).
     *
     * @param mode         HPM 0–3: 0 normal (reset by reading resetHighPass()), 1 reference
     *                     filtering, 2 normal, 3 autoreset on interrupt
     * @param cutoff       HPCF<2:1> 0–3 (no Hz values are tabulated in this datasheet revision)
     * @param filterOutput route filtered data to the output registers and FIFO (FDS)
     * @param onIa1        filter generator 1 (HPIS1)
     * @param onIa2        filter generator 2 (HPIS2)
     * @param onClick      filter click detection (HPCLICK)
     * @throws IllegalArgumentException if mode or cutoff is outside 0–3
     */
    void setHighPass(int mode = 0, int cutoff = 0, boolean filterOutput = false,
                     boolean onIa1 = false, boolean onIa2 = false, boolean onClick = false) throws IOException {
        if (mode < 0 || mode > 3) throw new IllegalArgumentException('HPM must be 0..3')
        if (cutoff < 0 || cutoff > 3) throw new IllegalArgumentException('HPCF must be 0..3')
        int v = ((mode & 0x03) << 6) | ((cutoff & 0x03) << 4)
                | (filterOutput ? 0x08 : 0) | (onClick ? 0x04 : 0)
                | (onIa2 ? 0x02 : 0) | (onIa1 ? 0x01 : 0)
        accelWrite(REG_CTRL_REG2_A, v)
    }

    /**
     * Read REFERENCE_A; in HPM = 00 this resets the high-pass filter.
     *
     * @return the reference register value
     */
    int resetHighPass() throws IOException {
        return accelRead(REG_REFERENCE_A)
    }

    /**
     * Write the high-pass reference value (used when HPM = 01).
     *
     * @param value reference value, 0–255
     * @throws IllegalArgumentException if value is outside 0–255
     */
    void setReference(int value) throws IOException {
        if (value < 0 || value > 255) throw new IllegalArgumentException('reference must be 0..255')
        accelWrite(REG_REFERENCE_A, value)
    }

    // =====================================================================
    // FIFO
    // =====================================================================

    /**
     * Configure the FIFO. FIFO_EN in CTRL_REG5_A is set for modes 1–3. Returning to bypass
     * clears the buffer (the chip does this); to restart FIFO mode after a drain go through bypass.
     *
     * @param mode        0 bypass, 1 FIFO, 2 stream, 3 trigger (stream-to-FIFO)
     * @param watermark   watermark level, 0–31 (default 0)
     * @param triggerInt2 trigger source for trigger mode: false = INT1, true = INT2 (default false)
     * @throws IllegalArgumentException if a value is out of range
     */
    void setFifo(int mode, int watermark = 0, boolean triggerInt2 = false) throws IOException {
        if (mode < 0 || mode > 3) throw new IllegalArgumentException('FIFO mode must be 0..3')
        if (watermark < 0 || watermark > 31) throw new IllegalArgumentException('watermark must be 0..31')
        accelWrite(REG_FIFO_CTRL_REG_A, (mode << 6) | (triggerInt2 ? 0x20 : 0) | watermark)
        accelUpdate(REG_CTRL_REG5_A, CTRL5_FIFO_EN, mode != 0 ? CTRL5_FIFO_EN : 0)
    }

    /** @return number of unread FIFO sample sets (FSS<4:0>, 0–32) */
    int fifoLevel() throws IOException {
        return accelRead(REG_FIFO_SRC_REG_A) & 0x1F
    }

    /**
     * Drain all unread FIFO sample sets. See readFifo(int).
     *
     * @return list of [x, y, z] samples in g
     */
    double[][] readFifo() throws IOException {
        return readFifo(Integer.MAX_VALUE)
    }

    /**
     * Read up to count FIFO sample sets, one 6-byte burst per sample.
     *
     * @param count maximum number of samples to read (capped at the current FSS)
     * @return list of [x, y, z] samples in g, possibly empty
     */
    double[][] readFifo(int count) throws IOException {
        int n = Math.min(count, fifoLevel())
        if (n < 0) n = 0
        double[][] out = new double[n][]
        for (int i = 0; i < n; i++) {
            out[i] = convertAccel(accel.read(REG_OUT_X_L_A | ACCEL_AUTO_INC, 6))
        }
        return out
    }

    /**
     * Decode FIFO_SRC_REG_A.
     *
     * @return [watermark, overrun, empty, level]: flags are 0 or 1, level is FSS
     */
    int[] readFifoStatus() throws IOException {
        int v = accelRead(REG_FIFO_SRC_REG_A)
        int[] out = new int[4]
        out[0] = (v >> 7) & 1
        out[1] = (v >> 6) & 1
        out[2] = (v >> 5) & 1
        out[3] = v & 0x1F
        return out
    }

    // =====================================================================
    // Inertial interrupt generators
    // =====================================================================

    /**
     * Configure an inertial interrupt generator (threshold, duration and event mask).
     *
     * Writes INTx_CFG_A, INTx_THS_A and INTx_DURATION_A. The threshold uses 1 LSb = full
     * scale / 128; the duration uses 1 LSb = 1/ODR. The generator's source must still be routed
     * with enableInterrupt().
     *
     * @param generator  1 or 2
     * @param events     6-bit mask: ZHIE (bit 5), ZLIE 4, YHIE 3, YLIE 2, XHIE 1, XLIE 0
     * @param thresholdG threshold in g
     * @param durationMs minimum event duration in ms (default 0)
     * @param andMode    true = AND of the enabled events, false = OR (default false)
     * @throws IllegalArgumentException if generator or events is out of range
     */
    void setMotionInterrupt(int generator, int events, double thresholdG,
                            double durationMs = 0.0d, boolean andMode = false) throws IOException {
        checkGenerator(generator)
        if (events < 0 || events > 0x3F) throw new IllegalArgumentException('events must be a 6-bit mask')
        int cfg = generator == 1 ? REG_INT1_CFG_A : REG_INT2_CFG_A
        int ths = generator == 1 ? REG_INT1_THS_A : REG_INT2_THS_A
        int dur = generator == 1 ? REG_INT1_DURATION_A : REG_INT2_DURATION_A
        accelWrite(cfg, (andMode ? 0x80 : 0) | events)
        accelWrite(ths, thresholdCode(thresholdG))
        accelWrite(dur, timeCode(durationMs, 127))
    }

    /**
     * Free-fall detection: all three axes low, AND combination (INTx_CFG_A = 0x95 at the defaults).
     *
     * @param generator  1 or 2 (default 1)
     * @param thresholdG threshold in g (default 0.35)
     * @param durationMs minimum duration in ms (default 30)
     */
    void setFreeFall(int generator = 1, double thresholdG = 0.35d, double durationMs = 30.0d) throws IOException {
        setMotionInterrupt(generator, 0x15, thresholdG, durationMs, true)
    }

    /**
     * 6D orientation: movement recognition (default) or position recognition, optionally 4D.
     * All six zone events are enabled; the generator's threshold register is not changed.
     *
     * @param generator 1 or 2 (default 1)
     * @param position  true = position recognition (AOI=1, level); false = movement recognition
     *                  (pulse of one ODR period) (default false)
     * @param fourD     true = 4D detection (D4D_INTx set) (default false)
     */
    void setOrientation(int generator = 1, boolean position = false, boolean fourD = false) throws IOException {
        checkGenerator(generator)
        int cfg = generator == 1 ? REG_INT1_CFG_A : REG_INT2_CFG_A
        accelWrite(cfg, (position ? 0x80 : 0) | 0x40 | 0x3F)
        int mask = generator == 1 ? CTRL5_D4D_INT1 : CTRL5_D4D_INT2
        accelUpdate(REG_CTRL_REG5_A, mask, fourD ? mask : 0)
    }

    /**
     * Latch an inertial generator's interrupt until its source register is read.
     *
     * @param generator 1 or 2
     * @param enabled   true to latch (LIR_INTx)
     */
    void setLatch(int generator, boolean enabled) throws IOException {
        checkGenerator(generator)
        int mask = generator == 1 ? CTRL5_LIR_INT1 : CTRL5_LIR_INT2
        accelUpdate(REG_CTRL_REG5_A, mask, enabled ? mask : 0)
    }

    /**
     * Read INTx_SOURCE_A. Clears the generator's IA flag and INT line.
     *
     * @param generator 1 or 2
     * @return the source byte: bit 6 IA, bits 5–0 ZH … XL
     */
    int readInterruptSource(int generator) throws IOException {
        checkGenerator(generator)
        return accelRead(generator == 1 ? REG_INT1_SOURCE_A : REG_INT2_SOURCE_A)
    }

    // =====================================================================
    // Click detection
    // =====================================================================

    /**
     * Configure single and double click detection.
     *
     * The axis masks use X=1, Y=2, Z=4. Writes CLICK_CFG_A, CLICK_THS_A (1 LSb = full scale /
     * 128) and the TIME_* registers (1 LSb = 1/ODR).
     *
     * @param single      axis mask for single click (default 0)
     * @param doubleClick axis mask for double click (default 0)
     * @param thresholdG  click threshold in g (default 0.5)
     * @param timeLimitMs maximum time above threshold in ms (default 10)
     * @param latencyMs   dead time after the first click before a second is detected, in ms (default 20)
     * @param windowMs    window in which the second click must occur, in ms (default 50)
     * @throws IllegalArgumentException if an axis mask is outside 0–7
     */
    void setClick(int single = 0, int doubleClick = 0, double thresholdG = 0.5d,
                  double timeLimitMs = 10.0d, double latencyMs = 20.0d, double windowMs = 50.0d) throws IOException {
        if (single < 0 || single > 7 || doubleClick < 0 || doubleClick > 7) {
            throw new IllegalArgumentException('click axis masks must be 0..7')
        }
        int cfg = (single & 0x01) | ((single & 0x02) << 1) | ((single & 0x04) << 2)
                | ((doubleClick & 0x01) << 1) | ((doubleClick & 0x02) << 2) | ((doubleClick & 0x04) << 3)
        accelWrite(REG_CLICK_CFG_A, cfg)
        accelWrite(REG_CLICK_THS_A, thresholdCode(thresholdG))
        accelWrite(REG_TIME_LIMIT_A, timeCode(timeLimitMs, 127))
        accelWrite(REG_TIME_LATENCY_A, timeCode(latencyMs, 255))
        accelWrite(REG_TIME_WINDOW_A, timeCode(windowMs, 255))
    }

    /**
     * Read CLICK_SRC_A. Clears the click interrupt.
     *
     * @return the source byte: bit 6 IA, 5 DCLICK, 4 SCLICK, 3 Sign, 2 Z, 1 Y, 0 X
     */
    int readClickSource() throws IOException {
        return accelRead(REG_CLICK_SRC_A)
    }

    // =====================================================================
    // Interrupt routing and polarity
    // =====================================================================

    /**
     * Set the INT1 / INT2 polarity (H_LACTIVE). Also changes the edge trigger used by
     * subsequent onInterrupt() calls.
     *
     * @param activeLow true for active-low INT lines
     */
    void setInterruptPolarity(boolean activeLow) throws IOException {
        accelUpdate(REG_CTRL_REG6_A, CTRL6_H_LACTIVE, activeLow ? CTRL6_H_LACTIVE : 0)
        this.activeLow = activeLow
    }

    /**
     * Route an interrupt source to an INT line. The pin is ignored for INT1-only
     * (SOURCE_DATA_READY, SOURCE_WATERMARK, SOURCE_OVERRUN) and INT2-only (SOURCE_BOOT) sources.
     * The generator or function behind the source is configured separately.
     *
     * @param source one of the SOURCE_* constants except SOURCE_MAG_DATA_READY
     * @param pin    INT line, 1 or 2 (default 1)
     * @throws IllegalArgumentException for an unknown source, a non-routable source or a bad pin
     */
    void enableInterrupt(int source, int pin = 1) throws IOException {
        routeSource(source, true, pin)
        enabledSources |= source
    }

    /**
     * Remove an interrupt source from an INT line. See enableInterrupt().
     *
     * @param source one of the SOURCE_* constants except SOURCE_MAG_DATA_READY
     * @param pin    INT line, 1 or 2 (default 1)
     * @throws IllegalArgumentException for an unknown source, a non-routable source or a bad pin
     */
    void disableInterrupt(int source, int pin = 1) throws IOException {
        routeSource(source, false, pin)
        enabledSources &= ~source
    }

    protected void routeSource(int source, boolean on, int pin) throws IOException {
        checkPin(pin)
        int bit = on ? 1 : 0
        switch (source) {
            case SOURCE_DATA_READY:
                accelUpdate(REG_CTRL_REG3_A, 0x10, bit * 0x10)
                break
            case SOURCE_WATERMARK:
                accelUpdate(REG_CTRL_REG3_A, 0x04, bit * 0x04)
                break
            case SOURCE_OVERRUN:
                accelUpdate(REG_CTRL_REG3_A, 0x02, bit * 0x02)
                break
            case SOURCE_IA1:
                routeToPin(0x40, pin, on)
                break
            case SOURCE_IA2:
                routeToPin(0x20, pin, on)
                break
            case SOURCE_CLICK:
                routeToPin(0x80, pin, on)
                break
            case SOURCE_BOOT:
                accelUpdate(REG_CTRL_REG6_A, CTRL6_BOOT_I1, bit * CTRL6_BOOT_I1)
                break
            case SOURCE_MAG_DATA_READY:
                throw new IllegalArgumentException('SOURCE_MAG_DATA_READY is on the DRDY pin and cannot be routed')
            default:
                throw new IllegalArgumentException('unknown interrupt source: 0x' + Integer.toHexString(source))
        }
    }

    /**
     * Route a function to INT1 (CTRL_REG3_A) or INT2 (CTRL_REG6_A). Both registers use the same
     * bit layout: 0x80 click, 0x40 generator 1, 0x20 generator 2.
     */
    protected void routeToPin(int bit, int pin, boolean on) throws IOException {
        int reg = pin == 2 ? REG_CTRL_REG6_A : REG_CTRL_REG3_A
        accelUpdate(reg, bit, on ? bit : 0)
    }

    // =====================================================================
    // Interrupt subscribe / poll
    // =====================================================================

    /**
     * Subscribe to INT1 using the accelerometer connection's InputPin, or a 5 ms polling thread
     * if none is wired. Equivalent to onInterrupt(callback, 1).
     *
     * @param callback receives the non-zero pollInterrupt() status; bus errors inside the handler are dropped
     */
    void onInterrupt(IntConsumer callback) {
        onInterrupt(callback, 1, accel.intPin())
    }

    /**
     * Subscribe on one INT line. INT1 uses the connection's InputPin when one is wired; INT2
     * has no pin of its own, so it falls back to polling unless a pin is supplied with
     * onInterrupt(callback, pin, intPin).
     *
     * @param callback receives the non-zero pollInterrupt() status
     * @param pin      INT line, 1 or 2
     * @throws IllegalArgumentException if pin is not 1 or 2
     */
    void onInterrupt(IntConsumer callback, int pin) {
        onInterrupt(callback, pin, pin == 1 ? accel.intPin() : null)
    }

    /**
     * Subscribe on one INT line using an explicit InputPin, or polling if intPin is null.
     *
     * @param callback receives the non-zero pollInterrupt() status
     * @param pin      INT line, 1 or 2
     * @param intPin   pin wired to that INT line, or null for the 5 ms polling fallback
     * @throws IllegalArgumentException if pin is not 1 or 2
     */
    void onInterrupt(IntConsumer callback, int pin, InputPin intPin) {
        checkPin(pin)
        offInterrupt(pin)
        callbacks.put(pin, callback)
        if (intPin != null) {
            EdgeHandler handler = { -> dispatch(pin) } as EdgeHandler
            edgeHandlers[pin] = handler
            armedPins[pin] = intPin
            intPin.onEdge(handler, activeLow ? EdgeTrigger.FALLING : EdgeTrigger.RISING)
        } else {
            Thread t = new Thread({ -> pollLoop(pin) } as Runnable, 'lsm303-int' + pin)
            t.setDaemon(true)
            pollThreads[pin] = t
            t.start()
        }
    }

    /**
     * Unsubscribe one INT line: removes the edge handler or stops the polling thread.
     *
     * @param pin INT line, 1 or 2 (default 1)
     * @throws IllegalArgumentException if pin is not 1 or 2
     */
    void offInterrupt(int pin = 1) {
        checkPin(pin)
        callbacks.remove(pin)
        InputPin armed = armedPins[pin]
        EdgeHandler handler = edgeHandlers[pin]
        if (armed != null && handler != null) armed.offEdge(handler)
        armedPins[pin] = null
        edgeHandlers[pin] = null
        Thread t = pollThreads[pin]
        if (t != null) t.interrupt()
        pollThreads[pin] = null
    }

    protected void pollLoop(int pin) {
        while (!Thread.currentThread().isInterrupted()) {
            dispatch(pin)
            try {
                Thread.sleep(5L)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    protected void dispatch(int pin) {
        IntConsumer cb = callbacks.get(pin)
        if (cb == null) return
        try {
            int status = pollInterrupt()
            if (status != 0) cb.accept(status)
        } catch (IOException e) {
            // Bus error inside an interrupt context: nothing to report to; drop this event.
        }
    }

    /**
     * Read the status of every enabled source and return it as one bitmask (bit layout matches
     * the SOURCE_* constants). SOURCE_MAG_DATA_READY is always checked because it is wired to the
     * DRDY pin. Reading INTx_SOURCE_A and CLICK_SRC_A clears their latched flags.
     *
     * @return combined status bitmask, 0 if nothing is pending
     */
    int pollInterrupt() throws IOException {
        int status = 0
        int en = enabledSources
        if ((en & SOURCE_DATA_READY) != 0 && newDataAvailable()) status |= SOURCE_DATA_READY
        if ((en & (SOURCE_WATERMARK | SOURCE_OVERRUN)) != 0) {
            int fifo = accelRead(REG_FIFO_SRC_REG_A)
            if ((en & SOURCE_WATERMARK) != 0 && (fifo & 0x80) != 0) status |= SOURCE_WATERMARK
            if ((en & SOURCE_OVERRUN) != 0 && (fifo & 0x40) != 0) status |= SOURCE_OVERRUN
        }
        if ((en & SOURCE_IA1) != 0 && (readInterruptSource(1) & 0x40) != 0) status |= SOURCE_IA1
        if ((en & SOURCE_IA2) != 0 && (readInterruptSource(2) & 0x40) != 0) status |= SOURCE_IA2
        if ((en & SOURCE_CLICK) != 0 && (readClickSource() & 0x40) != 0) status |= SOURCE_CLICK
        if (magDataReady()) status |= SOURCE_MAG_DATA_READY
        return status
    }

    // =====================================================================
    // Magnetometer
    // =====================================================================

    /**
     * Set the magnetometer full-scale range (CRB_REG_M.GN).
     *
     * @param rangeUt full scale in µT: 130, 190, 250, 400, 470, 560 or 810 (±1.3 … ±8.1 gauss)
     * @throws IllegalArgumentException if rangeUt is not one of the allowed values
     */
    void setMagRange(int rangeUt) throws IOException {
        int idx = -1
        for (int i = 0; i < MAG_RANGE_UT.length; i++) {
            if (MAG_RANGE_UT[i] == rangeUt) {
                idx = i
                break
            }
        }
        if (idx < 0) throw new IllegalArgumentException("unsupported magnetometer range: " + rangeUt + " uT")
        magWrite(REG_CRB_REG_M, (idx + 1) << 5)
        magGainXy = (double) MAG_GAIN_XY[idx]
        magGainZ = (double) MAG_GAIN_Z[idx]
        magRangeUt = rangeUt
    }

    /**
     * Set the magnetometer output data rate (CRA_REG_M.DO); keeps TEMP_EN.
     *
     * @param rateHz 0.75, 1.5, 3, 7.5, 15, 30, 75 or 220
     * @throws IllegalArgumentException if the rate is not supported
     */
    void setMagDataRate(double rateHz) throws IOException {
        int code
        if (rateHz == 0.75d) {
            code = 0
        } else if (rateHz == 1.5d) {
            code = 1
        } else if (rateHz == 3.0d) {
            code = 2
        } else if (rateHz == 7.5d) {
            code = 3
        } else if (rateHz == 15.0d) {
            code = 4
        } else if (rateHz == 30.0d) {
            code = 5
        } else if (rateHz == 75.0d) {
            code = 6
        } else if (rateHz == 220.0d) {
            code = 7
        } else {
            throw new IllegalArgumentException("unsupported magnetometer rate: " + rateHz + " Hz")
        }
        magUpdate(REG_CRA_REG_M, 0x1C, code << 2)
    }

    /**
     * Set the magnetometer operating mode (MR_REG_M.MD).
     *
     * @param mode 0 continuous, 1 single conversion (then sleep), 2 sleep
     * @throws IllegalArgumentException if mode is out of range
     */
    void setMagMode(int mode) throws IOException {
        if (mode < 0 || mode > 2) throw new IllegalArgumentException('magnetometer mode must be 0, 1 or 2')
        magWrite(REG_MR_REG_M, mode)
    }

    /**
     * Read the magnetometer raw counts in X, Y, Z order.
     *
     * @return [x, y, z] signed counts
     */
    int[] readMagneticRaw() throws IOException {
        return readMagCounts()
    }

    /** @return true if SR_REG_M.DRDY is set */
    boolean magDataReady() throws IOException {
        return (magRead(REG_SR_REG_M) & 0x01) != 0
    }

    /**
     * Decode SR_REG_M.
     *
     * @return [drdy, lock], each 0 or 1
     */
    int[] readMagStatus() throws IOException {
        int v = magRead(REG_SR_REG_M)
        int[] out = new int[2]
        out[0] = v & 1
        out[1] = (v >> 1) & 1
        return out
    }

    /**
     * Read the magnetometer identification registers.
     *
     * @return three bytes IRA, IRB, IRC; 0x48 0x34 0x33 on a genuine part
     */
    byte[] readMagIdentity() throws IOException {
        return mag.read(REG_IRA_REG_M, 3)
    }

    /**
     * Enable or disable the magnetometer temperature sensor (CRA_REG_M.TEMP_EN).
     *
     * @param enabled true to enable
     */
    void enableTemperature(boolean enabled) throws IOException {
        magUpdate(REG_CRA_REG_M, 0x80, enabled ? 0x80 : 0)
    }

    /**
     * Read the relative temperature. Enables the sensor on first use if it is off. The datasheet
     * gives no absolute reference, so the value is a delta only.
     *
     * @return temperature change in °C (8 LSB/°C, relative to an unspecified reference)
     */
    double readTemperatureDelta() throws IOException {
        if ((magRead(REG_CRA_REG_M) & 0x80) == 0) enableTemperature(true)
        byte[] raw = mag.read(REG_TEMP_OUT_H_M, 2)
        int v = Register.toSigned(((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF), 16) >> 4
        return v / 8.0d
    }

    /**
     * Compass heading in degrees, 0 to 360, using atan2(y, x).
     *
     * Flat: atan2(my, mx) from the magnetometer only. Tilt-compensated: also reads the
     * accelerometer, derives roll and pitch from the normalised gravity vector, and projects the
     * field onto the horizontal plane. Both sensors are assumed to share one body frame; the
     * datasheet does not document their axis alignment. No declination is applied.
     *
     * @param tiltCompensate true to read the accelerometer and compensate for tilt (default true)
     * @return heading in degrees, 0 to 360
     */
    double heading(boolean tiltCompensate = true) throws IOException {
        double[] m = readMagnetic()
        double mx = m[0]
        double my = m[1]
        double mz = m[2]
        double h
        if (!tiltCompensate) {
            h = Math.atan2(my, mx)
        } else {
            double[] a = readAcceleration()
            double n = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
            if (n == 0.0d) {
                h = Math.atan2(my, mx)
            } else {
                double ax = a[0] / n
                double ay = a[1] / n
                double az = a[2] / n
                double roll = Math.atan2(ay, az)
                double pitch = Math.atan2(-ax, ay * Math.sin(roll) + az * Math.cos(roll))
                double xh = mx * Math.cos(pitch) + mz * Math.sin(pitch)
                double yh = mx * Math.sin(roll) * Math.sin(pitch) + my * Math.cos(roll) -
                        mz * Math.sin(roll) * Math.cos(pitch)
                h = Math.atan2(yh, xh)
            }
        }
        double deg = Math.toDegrees(h)
        if (deg < 0.0d) deg += 360.0d
        if (deg >= 360.0d) deg -= 360.0d
        return deg
    }

    // =====================================================================
    // Reboot
    // =====================================================================

    /**
     * Reboot the accelerometer memory (CTRL_REG5_A.BOOT) and wait 5 ms. The datasheet does not
     * say which control registers survive, so construct a new driver to re-run init afterwards.
     */
    void reboot() throws IOException {
        accelUpdate(REG_CTRL_REG5_A, CTRL5_BOOT, CTRL5_BOOT)
        sleepMs(5L)
    }

    protected void checkGenerator(int generator) {
        if (generator != 1 && generator != 2) throw new IllegalArgumentException('generator must be 1 or 2')
    }

    protected void checkPin(int pin) {
        if (pin != 1 && pin != 2) throw new IllegalArgumentException('INT pin must be 1 or 2')
    }
}
