///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-groovy:1.2.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.tof.VL53L1XFull

import java.util.Arrays

// Exercises every method in the VL53L1X Full API, and finally moves the sensor to another I²C
// address and back to 0x29.
public class Complete {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"))

        try (var connection = new I2CConnection(bus, VL53L1XFull.DEFAULT_ADDRESS)) {   // open I²C bus, device address, (bus, address=0x29) → I2CConnection
            var s = new VL53L1XFull(connection)                                         // Create VL53L1X Full driver, (connection) → VL53L1XFull
                                                                                        // runs init: boot poll, ID check, ULD default config

            System.out.printf("model 0x%02X%n", s.modelId())                            // Read model ID, () → int
                                                                                        // IDENTIFICATION__MODEL_ID, always 0xEA
            System.out.printf("module 0x%02X%n", s.moduleType())                        // Read module type, () → int
                                                                                        // IDENTIFICATION__MODULE_TYPE, always 0xCC
            System.out.printf("revision 0x%02X%n", s.revisionId())                      // Read revision ID, () → int
                                                                                        // mask revision, 0x10

            int d = s.distance()                                                        // Measure distance, () → int mm
                                                                                        // single shot; blocks for about one timing budget
            System.out.println("distance " + d + " mm, valid " + s.rangeValid())        // Check last measurement, () → boolean
                                                                                        // mapped range status == 0
            System.out.println("range status " + s.rangeStatus())                       // Read last range status, () → int
                                                                                        // 0 = valid, 2 = signal fail, 4 = out of bounds
            var m = s.readMeasurement()                                                 // Read result block, () → Measurement
                                                                                        // distance, status, signal/ambient MCPS, SPAD count
            System.out.printf("signal %.2f MCPS, ambient %.2f MCPS, %.1f SPADs%n",
                    m.signalRateMcps(), m.ambientRateMcps(), m.effectiveSpadCount())

            System.out.println("mode " + s.distanceMode())                              // Read distance mode, () → DistanceMode
                                                                                        // from PHASECAL_CONFIG__TIMEOUT_MACROP
            s.setDistanceMode(VL53L1XFull.DistanceMode.SHORT)                           // Set distance mode, (mode) → void
                                                                                        // ~1.3 m, robust in sunlight; keeps the budget
            System.out.println("budget " + s.timingBudget() + " us")                    // Read timing budget, () → int µs
                                                                                        // decoded from the range timeout A register
            s.setTimingBudget(33000)                                                    // Set timing budget, (budgetUs µs) → void
                                                                                        // ULD table values 15000 (short only) … 500000
            System.out.println("short mode " + s.distance() + " mm")                    // Measure distance, () → int mm
                                                                                        // 33 ms single shot
            s.setDistanceMode(VL53L1XFull.DistanceMode.LONG)                            // Set distance mode, (mode) → void
                                                                                        // back to up to 4 m in the dark
            s.setTimingBudget(100000)                                                   // Set timing budget, (budgetUs µs) → void
                                                                                        // default 100 ms

            s.setInterMeasurement(200)                                                  // Set inter-measurement period, (periodMs ms) → void
                                                                                        // must be ≥ the timing budget
            System.out.println("period " + s.interMeasurement() + " ms")                // Read inter-measurement period, () → int ms
                                                                                        // oscillator ticks scaled by the PLL calibration
            s.startContinuous(200)                                                      // Start continuous ranging, (periodMs=0 ms) → void
                                                                                        // timed mode; 0 = as fast as the budget allows
            for (int i = 0; i < 5; i++) {
                System.out.println("continuous " + s.readContinuous() + " mm")          // Read next continuous result, () → int mm
                                                                                        // waits for data ready, then clears it
            }
            while (!s.dataReady()) Thread.sleep(10)                                     // Check for a result, () → boolean
                                                                                        // GPIO1 line asserted
            System.out.println("record " + s.readMeasurement().distanceMm() + " mm")    // Read result block, () → Measurement
                                                                                        // non-blocking; clears the interrupt
            s.stopContinuous()                                                          // Stop continuous ranging, () → void
                                                                                        // does not wait for a running measurement
            Thread.sleep(250)

            System.out.println("signal limit " + s.signalRateLimit() + " MCPS")         // Read signal-rate limit, () → double MCPS
                                                                                        // 9.7 fixed point, default 1.0
            s.setSignalRateLimit(0.5)                                                   // Set signal-rate limit, (limitMcps MCPS) → void
                                                                                        // lower = longer range, more noise
            s.setSignalRateLimit(1.0)                                                   // Set signal-rate limit, (limitMcps MCPS) → void
                                                                                        // restore the default
            System.out.println("sigma " + s.sigmaThreshold() + " mm")                   // Read sigma threshold, () → int mm
                                                                                        // 14.2 fixed point, default 90
            s.setSigmaThreshold(60)                                                     // Set sigma threshold, (sigmaMm mm) → void
                                                                                        // stricter repeatability filter
            s.setSigmaThreshold(90)                                                     // Set sigma threshold, (sigmaMm mm) → void
                                                                                        // restore the default

            int centre = s.opticalCenter()                                              // Read optical-centre SPAD, () → int
                                                                                        // factory NVM value for this part's lens
            System.out.println("optical centre " + centre)
            s.setRoi(8, 8)                                                              // Set ROI size, (width SPADs, height SPADs) → void
                                                                                        // 4–16 each; narrows the field of view
            s.setRoiCenter(centre)                                                      // Set ROI centre, (spad) → void
                                                                                        // align the narrow ROI with the lens
            System.out.println("roi " + Arrays.toString(s.roi()) + " centre " + s.roiCenter())   // Read ROI size, () → int[]; Read ROI centre, () → int
                                                                                        // {width, height} in SPADs
            s.setRoi(16, 16)                                                            // Set ROI size, (width SPADs, height SPADs) → void
                                                                                        // full array; re-centres on SPAD 199

            double original = s.offset()                                                // Read range offset, () → double mm
                                                                                        // NVM factory value, 0.25 mm steps
            s.setOffset(original - 5.0)                                                 // Set range offset, (offsetMm mm) → void
                                                                                        // volatile override, −1024.0 to 1023.75 mm
            System.out.println("offset " + s.offset() + " mm")                          // Read range offset, () → double mm
                                                                                        // 13-bit two's complement × 0.25
            s.setCrosstalkCompensation(0.01)                                            // Set crosstalk compensation, (rateMcps MCPS) → void
                                                                                        // per-SPAD rate, 7.9 kcps register
            System.out.println("crosstalk " + s.crosstalkCompensation() + " MCPS")      // Read crosstalk compensation, () → double MCPS
                                                                                        // 0 = off
            System.out.println("calibrated offset " + s.calibrateOffset(140) + " mm")   // Calibrate offset, (targetMm mm) → double mm
                                                                                        // 50 samples against a target at 140 mm; applies it
            System.out.println("calibrated crosstalk " + s.calibrateCrosstalk(600))     // Calibrate crosstalk, (targetMm mm) → double MCPS
                                                                                        // 50 samples against a target at 600 mm; applies it
            s.setOffset(original)                                                       // Set range offset, (offsetMm mm) → void
                                                                                        // restore the factory value
            s.setCrosstalkCompensation(0.0)                                             // Set crosstalk compensation, (rateMcps MCPS) → void
                                                                                        // compensation off

            s.recalibrate()                                                             // Run temperature update, () → void
                                                                                        // full VHV; after a > 8 °C change, not while ranging

            s.setInterruptThresholds(100, 800)                                          // Set distance thresholds, (lowMm mm, highMm mm) → void
                                                                                        // 1 mm resolution
            System.out.println("thresholds " + Arrays.toString(s.interruptThresholds()))   // Read distance thresholds, () → int[] mm
                                                                                        // {low, high}
            s.enableInterrupt(VL53L1XFull.SOURCE_IN_WINDOW)                             // Select interrupt source, (source) → void
                                                                                        // fires while 100 mm ≤ range ≤ 800 mm
            s.disableInterrupt(VL53L1XFull.SOURCE_IN_WINDOW)                            // Disable interrupt source, (source) → void
                                                                                        // reverts to new-sample-ready (no disabled state)
            s.enableInterrupt(VL53L1XFull.SOURCE_NEW_SAMPLE_READY)                      // Select interrupt source, (source) → void
                                                                                        // the default data-ready source

            s.onInterrupt(status -> System.out.println("interrupt, source " + status))  // Subscribe to GPIO1, (callback) → void
                                                                                        // interrupt is cleared before the callback
            s.startContinuous(200)                                                      // Start continuous ranging, (periodMs=0 ms) → void
                                                                                        // timed mode feeds the subscription
            Thread.sleep(1000)
            s.stopContinuous()                                                          // Stop continuous ranging, () → void
                                                                                        // no more samples
            s.offInterrupt()                                                            // Unsubscribe, () → void
                                                                                        // detaches the pin handler or stops the polling thread
            System.out.println("pending " + s.pollInterrupt())                          // Read and clear interrupt, () → int
                                                                                        // active SOURCE_* value, 0 = nothing pending

            s.setAddress(0x30)                                                          // Change I²C address, (address) → void
                                                                                        // volatile; this driver instance is now unusable
            try (var moved = new I2CConnection(bus, 0x30)) {                            // open I²C bus, device address, (bus, address=0x30) → I2CConnection
                var m2 = new VL53L1XFull(moved)                                         // Create VL53L1X Full driver, (connection) → VL53L1XFull
                                                                                        // re-init at the new address is safe
                System.out.println("at 0x30 " + m2.distance() + " mm")                  // Measure distance, () → int mm
                                                                                        // same sensor, new address
                m2.setAddress(VL53L1XFull.DEFAULT_ADDRESS)                              // Change I²C address, (address) → void
                                                                                        // back to the power-on 0x29
            }
        }
    }
}
