///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.chips.accelerometer.MMA8451QFull;
import it.uhde.periph.connection.I2CConnection;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x1C").replaceFirst("^0[xX]", ""), 16);
        try (var connection = new I2CConnection(bus, addr)) {
            var accel = new MMA8451QFull(connection);            // Create MMA8451Q Full driver, (connection)

            accel.setRange(4);                                   // Set measurement range, (rangeG) → g
                                                                    // selects ±4 g; scale changes from 4096 to 2048 counts/g
            accel.setDataRate(200);                               // Set output data rate, (rateHz) → Hz
                                                                    // 800/400/200/100/50/12.5/6.25/1.56 Hz are valid
            accel.setOversampling(2, 2);                         // Set oversampling mode, (mode, sleepMode) → None
                                                                    // mode 0 normal, 1 low-noise low-power, 2 high resolution, 3 low power
            accel.setLowNoise(false);                            // Set low-noise mode, (enabled) → None
                                                                    // LNOISE; limited to ±4 g, so rejected together with ±8 g
            accel.setResolution(14);                             // Set output resolution, (bits) → None
                                                                    // 14-bit or 8-bit (F_READ); 8-bit is rejected while the FIFO is on
            accel.setHighPassOutput(true, 1);                    // Set high-pass filtered output, (enabled, cutoff=0) → None
                                                                    // HPF_OUT plus SEL 0..3; the cutoff in Hz depends on ODR and oversampling
            accel.setOffset(0.002, 0.0, -0.002);                 // Set zero-g offsets, (xG, yG, zG) → None
                                                                    // OFF_X/Y/Z at 2 mg/LSB, clamped to ±0.256 g
            double[] offsets = accel.getOffset();                // Read zero-g offsets, () → [g, g, g]
                                                                    // current OFF_X/Y/Z contents converted back to g
            int[] raw = accel.readRaw();                         // Read raw counts, () → [int, int, int]
                                                                    // signed 14-bit (or 8-bit) counts at the current resolution
            boolean ready = accel.newDataAvailable();             // Check new data, () → boolean
                                                                    // STATUS.ZYXDR; not valid while the FIFO is enabled
            int status = accel.readStatus();                     // Read STATUS register, () → int
                                                                    // raw STATUS byte (data status while the FIFO is off)
            int mode = accel.readSystemMode();                   // Read system mode, () → int
                                                                    // SYSMOD bits 1:0 — 0 standby, 1 wake, 2 sleep
            accel.setFifo(1, 16);                                // Set FIFO mode, (mode, watermark=0) → None
                                                                    // mode 0 disabled, 1 circular, 2 fill, 3 trigger
            accel.setFifoTrigger(false, false, true, false);      // Set FIFO trigger sources, (transient, orientation, pulse, motion) → None
                                                                    // TRIG_CFG bits 5..2
            int level = accel.fifoLevel();                       // Read FIFO level, () → int
                                                                    // F_CNT, 0..32 samples currently buffered
            var fifo = accel.readFifoStatus();                   // Read FIFO status, () → (overflow, watermark, count)
                                                                    // F_STATUS decoded; reading it clears SRC_FIFO
            double[][] samples = accel.readFifo(4);              // Read FIFO samples, (count) → [[g, g, g], ...]
                                                                    // drains up to count samples from the FIFO head
            accel.setFreefallMotion(false, 7, 0.2, 60, true);    // Configure free-fall/motion, (motion, axes=7, thresholdG, debounceMs=0, latch=false, debounceClear=false) → None
                                                                    // FF_MT_CFG / FF_MT_THS / FF_MT_COUNT; 0.063 g per LSB
            int motion = accel.readMotionSource();               // Read motion source, () → int
                                                                    // FF_MT_SRC byte; clears the flags when latched
            accel.setTransient(7, 0.5, 20, false, true);        // Configure transient detector, (axes=7, thresholdG, debounceMs=0, bypassHpf=false, latch=false) → None
                                                                    // TRANSIENT_CFG / TRANSIENT_THS / TRANSIENT_COUNT
            int transient = accel.readTransientSource();         // Read transient source, () → int
                                                                    // TRANSIENT_SRC byte; reading it clears the interrupt
            accel.setPulse(0, 0x04, 0.5, 0.5, 1.0, 40, 100, 300, true, false, true, true);
                                                                    // Configure tap detector, (single, double, thresholdX, thresholdY, thresholdZ, timeLimitMs, latencyMs, windowMs, latch, doubleAbort, lpf, hpf) → None
                                                                    // PULSE_CFG / PULSE_THSx / PULSE_TMLT / PULSE_LTCY / PULSE_WIND
            int pulse = accel.readPulseSource();                 // Read tap source, () → int
                                                                    // PULSE_SRC byte; reading it clears the interrupt
            accel.setOrientation(true, 100, 45, 4, 4, 1);       // Configure orientation, (enabled=true, debounceMs=0, thresholdDeg=45, hysteresis=4, zLock=4, backFront=1) → None
                                                                    // PL_CFG / PL_COUNT / P_L_THS_REG / PL_BF_ZCOMP
            var orientation = accel.readOrientation();           // Read orientation, () → (changed, lockout, portraitLandscape, back)
                                                                    // PL_STATUS decoded; reading it clears NEWLP
            accel.setAutoSleep(true, 12.5, 10, MMA8451QFull.SOURCE_PULSE | MMA8451QFull.SOURCE_FF_MT);
                                                                    // Configure auto-sleep, (enabled, sleepRateHz=50, timeoutS=10, wakeSources=0) → None
                                                                    // SLPE / ASLP_RATE / ASLP_COUNT plus the WAKE_* bits of CTRL_REG3
            accel.setFifoGate(true);                             // Set FIFO gate, (enabled) → None
                                                                    // FIFO_GATE — blocks FIFO input around WAKE/SLEEP transitions
            accel.setInterruptPins(false, false);                // Set interrupt pin config, (activeHigh=false, openDrain=false) → None
                                                                    // IPOL and PP_OD; both INT pins share these settings
            accel.enableInterrupt(MMA8451QFull.SOURCE_ORIENTATION, 1);
                                                                    // Enable interrupt source, (source, pin=1) → None
                                                                    // sets INT_EN_* in CTRL_REG4 and routes it in CTRL_REG5
            accel.enableInterrupt(MMA8451QFull.SOURCE_PULSE, 1);
                                                                    // Enable interrupt source, (source, pin=1) → None
                                                                    // routes the tap detector to INT1
            int irq = accel.pollInterrupt();                     // Poll interrupt status, () → int
                                                                    // INT_SOURCE bits; clears every source flag except SOURCE_DATA_READY
            accel.disableInterrupt(MMA8451QFull.SOURCE_PULSE);   // Disable interrupt source, (source) → None
                                                                    // clears the INT_EN_* bit for that source
            var st = accel.selfTest();                           // Run self-test, () → (ok, dx, dy, dz)
                                                                    // averages samples with and without the electrostatic force applied
            accel.standby();                                     // Enter standby mode, () → None
                                                                    // clears CTRL_REG1.ACTIVE
            accel.activate();                                    // Leave standby mode, () → None
                                                                    // sets ACTIVE and waits 2/ODR + 2 ms
            accel.reset();                                       // Software reset, () → None
                                                                    // CTRL_REG2.RST; the chip returns to STANDBY with reset values

            System.out.printf("offsets=(%.3f, %.3f, %.3f) g raw=(%d, %d, %d)%n",
                    offsets[0], offsets[1], offsets[2], raw[0], raw[1], raw[2]);
            System.out.printf("ready=%s status=0x%02X mode=%d fifoLevel=%d overflow=%s watermark=%s count=%d%n",
                    ready, status, mode, level, fifo.overflow(), fifo.watermark(), fifo.count());
            System.out.printf("fifoSamples=%d motion=0x%02X transient=0x%02X pulse=0x%02X irq=0x%02X%n",
                    samples.length, motion, transient, pulse, irq);
            System.out.printf("orientation changed=%s lockout=%s la=%d back=%s selfTest ok=%s d=(%d, %d, %d)%n",
                    orientation.changed(), orientation.lockout(), orientation.portraitLandscape(),
                    orientation.back(), st.ok(), st.dx(), st.dy(), st.dz());
        }
    }
}
