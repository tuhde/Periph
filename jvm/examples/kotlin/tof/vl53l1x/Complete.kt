///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-kotlin:1.2.0

// Exercises every method in the VL53L1X Full API, and finally moves the sensor to another I²C
// address and back to 0x29.

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.tof.VL53L1XFull
import it.uhde.periph.chips.tof.VL53L1XMinimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, VL53L1XMinimal.DEFAULT_ADDRESS).use { connection ->                  // open I²C bus, device address, (bus, address=0x29) → I2CConnection
        val s = VL53L1XFull(connection)                                                   // Create VL53L1X Full driver, (connection) → VL53L1XFull
                                                                                          // runs init: boot poll, ID check, ULD default config

        println("model 0x%02X".format(s.modelId()))                                       // Read model ID, () → Int
                                                                                          // IDENTIFICATION__MODEL_ID, always 0xEA
        println("module 0x%02X".format(s.moduleType()))                                   // Read module type, () → Int
                                                                                          // IDENTIFICATION__MODULE_TYPE, always 0xCC
        println("revision 0x%02X".format(s.revisionId()))                                 // Read revision ID, () → Int
                                                                                          // mask revision, 0x10

        val d = s.distance()                                                              // Measure distance, () → Int mm
                                                                                          // single shot; blocks for about one timing budget
        println("distance $d mm, valid ${s.rangeValid()}")                                // Check last measurement, () → Boolean
                                                                                          // mapped range status == 0
        println("range status ${s.rangeStatus()}")                                        // Read last range status, () → Int
                                                                                          // 0 = valid, 2 = signal fail, 4 = out of bounds
        val m = s.readMeasurement()                                                       // Read result block, () → Measurement
                                                                                          // distance, status, signal/ambient MCPS, SPAD count
        println("signal %.2f MCPS, ambient %.2f MCPS, %.1f SPADs".format(m.signalRateMcps, m.ambientRateMcps, m.effectiveSpadCount))

        println("mode ${s.distanceMode()}")                                               // Read distance mode, () → DistanceMode
                                                                                          // from PHASECAL_CONFIG__TIMEOUT_MACROP
        s.setDistanceMode(VL53L1XFull.DistanceMode.SHORT)                                 // Set distance mode, (mode) → Unit
                                                                                          // ~1.3 m, robust in sunlight; keeps the budget
        println("budget ${s.timingBudget()} us")                                          // Read timing budget, () → Int µs
                                                                                          // decoded from the range timeout A register
        s.setTimingBudget(33000)                                                          // Set timing budget, (budgetUs µs) → Unit
                                                                                          // ULD table values 15000 (short only) … 500000
        println("short mode ${s.distance()} mm")                                          // Measure distance, () → Int mm
                                                                                          // 33 ms single shot
        s.setDistanceMode(VL53L1XFull.DistanceMode.LONG)                                  // Set distance mode, (mode) → Unit
                                                                                          // back to up to 4 m in the dark
        s.setTimingBudget(100000)                                                         // Set timing budget, (budgetUs µs) → Unit
                                                                                          // default 100 ms

        s.setInterMeasurement(200)                                                        // Set inter-measurement period, (periodMs ms) → Unit
                                                                                          // must be ≥ the timing budget
        println("period ${s.interMeasurement()} ms")                                      // Read inter-measurement period, () → Int ms
                                                                                          // oscillator ticks scaled by the PLL calibration
        s.startContinuous(200)                                                            // Start continuous ranging, (periodMs=0 ms) → Unit
                                                                                          // timed mode; 0 = as fast as the budget allows
        repeat(5) {
            println("continuous ${s.readContinuous()} mm")                                // Read next continuous result, () → Int mm
                                                                                          // waits for data ready, then clears it
        }
        while (!s.dataReady()) Thread.sleep(10)                                           // Check for a result, () → Boolean
                                                                                          // GPIO1 line asserted
        println("record ${s.readMeasurement().distanceMm} mm")                            // Read result block, () → Measurement
                                                                                          // non-blocking; clears the interrupt
        s.stopContinuous()                                                                // Stop continuous ranging, () → Unit
                                                                                          // does not wait for a running measurement
        Thread.sleep(250)

        println("signal limit ${s.signalRateLimit()} MCPS")                               // Read signal-rate limit, () → Double MCPS
                                                                                          // 9.7 fixed point, default 1.0
        s.setSignalRateLimit(0.5)                                                         // Set signal-rate limit, (limitMcps MCPS) → Unit
                                                                                          // lower = longer range, more noise
        s.setSignalRateLimit(1.0)                                                         // Set signal-rate limit, (limitMcps MCPS) → Unit
                                                                                          // restore the default
        println("sigma ${s.sigmaThreshold()} mm")                                         // Read sigma threshold, () → Int mm
                                                                                          // 14.2 fixed point, default 90
        s.setSigmaThreshold(60)                                                           // Set sigma threshold, (sigmaMm mm) → Unit
                                                                                          // stricter repeatability filter
        s.setSigmaThreshold(90)                                                           // Set sigma threshold, (sigmaMm mm) → Unit
                                                                                          // restore the default

        val centre = s.opticalCenter()                                                    // Read optical-centre SPAD, () → Int
                                                                                          // factory NVM value for this part's lens
        println("optical centre $centre")
        s.setRoi(8, 8)                                                                    // Set ROI size, (width SPADs, height SPADs) → Unit
                                                                                          // 4–16 each; narrows the field of view
        s.setRoiCenter(centre)                                                            // Set ROI centre, (spad) → Unit
                                                                                          // align the narrow ROI with the lens
        println("roi ${s.roi()} centre ${s.roiCenter()}")                                 // Read ROI size, () → Pair<Int, Int>; Read ROI centre, () → Int
                                                                                          // (width, height) in SPADs
        s.setRoi(16, 16)                                                                  // Set ROI size, (width SPADs, height SPADs) → Unit
                                                                                          // full array; re-centres on SPAD 199

        val original = s.offset()                                                         // Read range offset, () → Double mm
                                                                                          // NVM factory value, 0.25 mm steps
        s.setOffset(original - 5.0)                                                       // Set range offset, (offsetMm mm) → Unit
                                                                                          // volatile override, −1024.0 to 1023.75 mm
        println("offset ${s.offset()} mm")                                                // Read range offset, () → Double mm
                                                                                          // 13-bit two's complement × 0.25
        s.setCrosstalkCompensation(0.01)                                                  // Set crosstalk compensation, (rateMcps MCPS) → Unit
                                                                                          // per-SPAD rate, 7.9 kcps register
        println("crosstalk ${s.crosstalkCompensation()} MCPS")                            // Read crosstalk compensation, () → Double MCPS
                                                                                          // 0 = off
        println("calibrated offset ${s.calibrateOffset(140)} mm")                         // Calibrate offset, (targetMm mm) → Double mm
                                                                                          // 50 samples against a target at 140 mm; applies it
        println("calibrated crosstalk ${s.calibrateCrosstalk(600)} MCPS")                 // Calibrate crosstalk, (targetMm mm) → Double MCPS
                                                                                          // 50 samples against a target at 600 mm; applies it
        s.setOffset(original)                                                             // Set range offset, (offsetMm mm) → Unit
                                                                                          // restore the factory value
        s.setCrosstalkCompensation(0.0)                                                   // Set crosstalk compensation, (rateMcps MCPS) → Unit
                                                                                          // compensation off

        s.recalibrate()                                                                   // Run temperature update, () → Unit
                                                                                          // full VHV; after a > 8 °C change, not while ranging

        s.setInterruptThresholds(100, 800)                                                // Set distance thresholds, (lowMm mm, highMm mm) → Unit
                                                                                          // 1 mm resolution
        println("thresholds ${s.interruptThresholds()}")                                  // Read distance thresholds, () → Pair<Int, Int> mm
                                                                                          // (low, high)
        s.enableInterrupt(VL53L1XFull.SOURCE_IN_WINDOW)                                   // Select interrupt source, (source) → Unit
                                                                                          // fires while 100 mm ≤ range ≤ 800 mm
        s.disableInterrupt(VL53L1XFull.SOURCE_IN_WINDOW)                                  // Disable interrupt source, (source) → Unit
                                                                                          // reverts to new-sample-ready (no disabled state)
        s.enableInterrupt(VL53L1XFull.SOURCE_NEW_SAMPLE_READY)                            // Select interrupt source, (source) → Unit
                                                                                          // the default data-ready source

        s.onInterrupt({ status -> println("interrupt, source $status") })                 // Subscribe to GPIO1, (callback, intPin=connection.intPin()) → Unit
                                                                                          // interrupt is cleared before the callback
        s.startContinuous(200)                                                            // Start continuous ranging, (periodMs=0 ms) → Unit
                                                                                          // timed mode feeds the subscription
        Thread.sleep(1000)
        s.stopContinuous()                                                                // Stop continuous ranging, () → Unit
                                                                                          // no more samples
        s.offInterrupt()                                                                  // Unsubscribe, () → Unit
                                                                                          // detaches the pin handler or stops the polling thread
        println("pending ${s.pollInterrupt()}")                                           // Read and clear interrupt, () → Int
                                                                                          // active SOURCE_* value, 0 = nothing pending

        s.setAddress(0x30)                                                                // Change I²C address, (address) → Unit
                                                                                          // volatile; this driver instance is now unusable
        I2CConnection(bus, 0x30).use { moved ->                                           // open I²C bus, device address, (bus, address=0x30) → I2CConnection
            val m2 = VL53L1XFull(moved)                                                   // Create VL53L1X Full driver, (connection) → VL53L1XFull
                                                                                          // re-init at the new address is safe
            println("at 0x30 ${m2.distance()} mm")                                        // Measure distance, () → Int mm
                                                                                          // same sensor, new address
            m2.setAddress(VL53L1XMinimal.DEFAULT_ADDRESS)                                    // Change I²C address, (address) → Unit
                                                                                          // back to the power-on 0x29
        }
    }
}
