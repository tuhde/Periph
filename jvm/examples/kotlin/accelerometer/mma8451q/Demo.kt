///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.chips.accelerometer.MMA8451QFull
import it.uhde.periph.connection.I2CConnection

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toIntOrNull() ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { Integer.parseInt(it.replaceFirst("^0[xX]".toRegex(), ""), 16) } ?: 0x1C
    I2CConnection(bus, addr).use { connection ->
        val accel = MMA8451QFull(connection)         // Create MMA8451Q driver, (connection)

        // --- Configure ±4 g / 100 Hz with high-resolution oversampling ---
        // ±4 g leaves headroom for the ±1.25 g orientation lockout limit
        // while the high-resolution oversampling mode lowers the noise
        // floor enough to detect a gentle tap.
        accel.setRange(4)                            // Set measurement range, (rangeG) → g
                                                        // selects ±4 g; scale changes from 4096 to 2048 counts/g
        accel.setDataRate(100)                       // Set output data rate, (rateHz) → Hz
                                                        // 100 Hz matches the debounce steps used below
        accel.setOversampling(2)                    // Set oversampling mode, (mode, sleepMode=-1) → None
                                                        // mode 2 = high resolution; lower noise, no ODR penalty

        // --- Run the electrostatic self-test once ---
        // The self-test applies an internal force to each axis and checks
        // that the measured delta is large enough. It is a one-off check
        // of the mechanics, so it runs before the detectors are armed.
        val st = accel.selfTest()                    // Run self-test, () → (ok, dx, dy, dz)
                                                        // averages samples with and without the electrostatic force applied
        println("self-test: ok=${st.ok} delta=(${st.dx}, ${st.dy}, ${st.dz}) counts")

        // --- Arm the orientation, double-tap and free-fall detectors ---
        // Orientation freezes above 1.25 g, so the debounce counter keeps
        // the reported orientation stable while the board is being moved.
        accel.setOrientation(true, 100)              // Configure orientation, (enabled=true, debounceMs=0, thresholdDeg=45, hysteresis=4, zLock=4, backFront=1) → None
                                                        // 100 ms debounce; 45° trip angle with ±14° hysteresis
        accel.setPulse(0, 0x04, 0.5, 0.5, 1.0, 40, 100, 300, true, false, true, true)
                                                        // Configure tap detector, (single, double, thresholdX, thresholdY, thresholdZ, timeLimitMs, latencyMs, windowMs, latch, doubleAbort, lpf, hpf) → None
                                                        // double tap on Z only; latched so the event is not missed
        accel.setFreefallMotion(false, 7, 0.2, 60, true)  // Configure free-fall/motion, (motion, axes=7, thresholdG, debounceMs=0, latch=false, debounceClear=false) → None
                                                        // free-fall (AND mode) below 0.2 g on all axes for 60 ms

        // --- Let the chip idle into SLEEP after 10 s ---
        // The tap and free-fall detectors are the only wake sources;
        // data-ready and the FIFO cannot wake the device.
        accel.setAutoSleep(true, 12.5, 10, MMA8451QFull.SOURCE_PULSE or MMA8451QFull.SOURCE_FF_MT)
                                                        // Configure auto-sleep, (enabled, sleepRateHz=50, timeoutS=10, wakeSources=0) → None
                                                        // sleep rate drops to 12.5 Hz; tap or free-fall wakes the chip

        // --- Route the three detectors to INT1 ---
        // All three sources are routed to the same pin; the driver reports
        // which source fired through the INT_SOURCE bits.
        accel.enableInterrupt(MMA8451QFull.SOURCE_ORIENTATION, 1)  // Enable interrupt source, (source, pin=1) → None
        accel.enableInterrupt(MMA8451QFull.SOURCE_PULSE, 1)         // Enable interrupt source, (source, pin=1) → None
        accel.enableInterrupt(MMA8451QFull.SOURCE_FF_MT, 1)          // Enable interrupt source, (source, pin=1) → None

        // --- Buffer the samples in a circular FIFO with a 16-sample watermark ---
        // The FIFO keeps the last 32 samples so a late reader still sees
        // what happened just before the event.
        accel.setFifo(1, 16)                         // Set FIFO mode, (mode, watermark=0) → None
                                                        // mode 1 = circular; the oldest sample is discarded

        // --- 60-second monitor loop ---
        // The board should be tilted, tapped and dropped during this
        // window. Every 250 ms the current acceleration and the system
        // mode are printed; an interrupt tells us which detector fired.
        val start = System.currentTimeMillis()
        var lastPrint = 0L
        while (System.currentTimeMillis() - start < 60_000) {
            val now = System.currentTimeMillis()
            if (now - lastPrint >= 250) {
                val xyz = accel.read()                // Read 3-axis acceleration, () → [g, g, g]
                val mode = accel.readSystemMode()      // Read system mode, () → int
                                                        // SYSMOD bits 1:0 — 0 standby, 1 wake, 2 sleep
                println("%5d  x=%+.3f  y=%+.3f  z=%+.3f g  %s".format((now - start) / 1000, xyz[0], xyz[1], xyz[2], if (mode == 2) "SLEEP" else "WAKE"))
                lastPrint = now
            }
            val irq = accel.pollInterrupt()           // Poll interrupt status, () → int
                                                        // INT_SOURCE bits; clears every source flag except SOURCE_DATA_READY
            if ((irq and MMA8451QFull.SOURCE_ORIENTATION) != 0) {
                val o = accel.readOrientation()        // Read orientation, () → (changed, lockout, portraitLandscape, back)
                                                        // PL_STATUS decoded; reading it clears NEWLP
                println("%5d  ORIENTATION: la=%d back=%s lockout=%s".format((now - start) / 1000, o.portraitLandscape, o.back, o.lockout))
            }
            if ((irq and MMA8451QFull.SOURCE_PULSE) != 0) {
                val pulse = accel.readPulseSource()    // Read tap source, () → int
                                                        // PULSE_SRC byte; reading it clears the interrupt
                println("%5d  DOUBLE TAP (src=0x%02X)".format((now - start) / 1000, pulse))
            }
            if ((irq and MMA8451QFull.SOURCE_FF_MT) != 0) {
                val motion = accel.readMotionSource()  // Read motion source, () → int
                                                        // FF_MT_SRC byte; clears the flags when latched
                println("%5d  FREE FALL (src=0x%02X)".format((now - start) / 1000, motion))
            }
            Thread.sleep(10)
        }

        // --- Drain the FIFO and report the mean acceleration ---
        // The circular FIFO still holds the samples that were collected
        // while the loop was running; their mean shows the average
        // orientation of the board over the last 32 samples.
        val samples = accel.readFifo()                // Read FIFO samples, (count=-1) → [[g, g, g], ...]
                                                        // drains every buffered sample
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (s in samples) { sx += s[0]; sy += s[1]; sz += s[2] }
        val n = maxOf(1, samples.size)
        println("FIFO: %d samples, mean=(%.3f, %.3f, %.3f) g".format(samples.size, sx / n, sy / n, sz / n))
    }
}
