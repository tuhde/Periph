///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

// Exercises every method in the VL53L0X Full API, and finally moves the sensor
// to another I²C address and back to 0x29.

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.tof.VL53L0XFull
import it.uhde.periph.chips.tof.VL53L0XMinimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, VL53L0XMinimal.DEFAULT_ADDRESS).use { connection ->                // open I²C bus, device address, (bus, address=0x29) → I2CConnection
        val sensor = VL53L0XFull(connection)                                              // Create VL53L0X Full driver, (connection) → VL53L0XFull
                                                                                          // runs init: ID check, tuning, SPADs, VHV + phase calibration

        println("model 0x%02X".format(sensor.modelId()))                                  // Read model ID, () → Int
                                                                                          // IDENTIFICATION_MODEL_ID, always 0xEE
        println("revision 0x%02X".format(sensor.revisionId()))                            // Read revision ID, () → Int
                                                                                          // IDENTIFICATION_REVISION_ID, 0x10 on current silicon

        val d = sensor.distance()                                                         // Measure distance, () → Int mm
                                                                                          // single shot; blocks for about one timing budget
        println("distance $d mm, valid ${sensor.rangeValid()}")                           // Check last measurement, () → Boolean
                                                                                          // device range status == 11 (range complete)
        println("range status ${sensor.rangeStatus()}")                                   // Read last range status, () → Int 0–15
                                                                                          // 11 = valid, 4 = no target
        val m = sensor.readMeasurement()                                                  // Read result block, () → Measurement
                                                                                          // distance, status, signal/ambient MCPS, SPAD count
        println("signal %.2f MCPS, ambient %.2f MCPS".format(m.signalRateMcps, m.ambientRateMcps))

        sensor.startContinuous()                                                          // Start continuous ranging, (periodMs=0 ms) → Unit
                                                                                          // 0 = back-to-back measurements
        repeat(5) {
            println("continuous ${sensor.readContinuous()} mm")                           // Read next continuous result, () → Int mm
                                                                                          // waits for a fresh data-ready, then clears it
        }
        sensor.stopContinuous()                                                           // Stop continuous ranging, () → Unit
                                                                                          // does not wait for a running measurement

        sensor.startContinuous(100)                                                       // Start continuous ranging, (periodMs=0 ms) → Unit
                                                                                          // timed mode: one measurement every 100 ms
        while (!sensor.dataReady()) {                                                     // Check for a result, () → Boolean
            Thread.sleep(10)                                                              // RESULT_INTERRUPT_STATUS bits 2:0 non-zero
        }
        println("timed ${sensor.readMeasurement().distanceMm} mm")                        // Read result block, () → Measurement
                                                                                          // non-blocking; clears the interrupt
        sensor.stopContinuous()                                                           // Stop continuous ranging, () → Unit
                                                                                          // back to software standby

        println("budget ${sensor.timingBudget()} us")                                     // Read timing budget, () → Int µs
                                                                                          // computed from the sequence-step timeouts
        sensor.setTimingBudget(50000)                                                     // Set timing budget, (budgetUs µs) → Unit
                                                                                          // longer budget = lower noise, ≥ 20000 µs
        println("signal limit ${sensor.signalRateLimit()} MCPS")                          // Read signal-rate limit, () → Double MCPS
                                                                                          // 9.7 fixed point
        sensor.setSignalRateLimit(0.1)                                                    // Set signal-rate limit, (limitMcps MCPS) → Unit
                                                                                          // lower = longer range, more noise
        sensor.setVcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE, 18)             // Set VCSEL period, (type, pclks) → Unit
                                                                                          // pre-range 12/14/16/18; redoes phase calibration
        sensor.setVcselPulsePeriod(VL53L0XFull.VcselPeriodType.FINAL_RANGE, 14)           // Set VCSEL period, (type, pclks) → Unit
                                                                                          // final-range 8/10/12/14
        println("vcsel ${sensor.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE)}/" +     // Read VCSEL period, (type) → Int PCLKs
            "${sensor.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.FINAL_RANGE)}")               // (reg + 1) × 2
        sensor.setProfile(VL53L0XFull.Profile.DEFAULT)                                    // Apply ranging profile, (profile) → Unit
                                                                                          // 0.25 MCPS, 14/10 PCLKs, 33 ms

        val original = sensor.offset()                                                    // Read range offset, () → Double mm
                                                                                          // NVM factory value, 0.25 mm steps
        sensor.setOffset(original - 5.0)                                                  // Set range offset, (offsetMm mm) → Unit
                                                                                          // volatile override, −512.0 to 511.75 mm
        println("offset ${sensor.offset()} mm")                                           // Read range offset, () → Double mm
                                                                                          // 12-bit two's complement × 0.25
        sensor.setOffset(original)                                                        // Set range offset, (offsetMm mm) → Unit
                                                                                          // restore the factory value
        sensor.setCrosstalkCompensation(0.0)                                              // Set crosstalk compensation, (rateMcps MCPS) → Unit
                                                                                          // 0 = compensation off

        sensor.recalibrate()                                                              // Rerun reference calibration, () → Unit
                                                                                          // VHV + phase; needed after a > 8 °C change

        sensor.setInterruptThresholds(100, 800)                                           // Set distance thresholds, (lowMm mm, highMm mm) → Unit
                                                                                          // 2 mm resolution
        println("thresholds ${sensor.interruptThresholds()}")                             // Read distance thresholds, () → Pair<Int mm, Int mm>
                                                                                          // decoded from SYSTEM_THRESH_LOW/HIGH
        sensor.enableInterrupt(VL53L0XFull.SOURCE_OUT_OF_WINDOW)                          // Select interrupt source, (source) → Unit
                                                                                          // replaces the active source (mutually exclusive)
        sensor.disableInterrupt(VL53L0XFull.SOURCE_OUT_OF_WINDOW)                         // Disable interrupt source, (source) → Unit
                                                                                          // only if it is the active one
        sensor.enableInterrupt(VL53L0XFull.SOURCE_NEW_SAMPLE_READY)                       // Select interrupt source, (source) → Unit
                                                                                          // back to the default data-ready source

        sensor.onInterrupt({ status -> println("interrupt, source $status") })            // Subscribe to GPIO1, (callback, intPin=connection.intPin()) → Unit
                                                                                          // status is read and cleared before the callback
        sensor.startContinuous(200)                                                       // Start continuous ranging, (periodMs=0 ms) → Unit
                                                                                          // timed mode feeds the subscription
        Thread.sleep(1000)
        sensor.stopContinuous()                                                           // Stop continuous ranging, () → Unit
                                                                                          // no more samples
        sensor.offInterrupt()                                                             // Unsubscribe, () → Unit
                                                                                          // detaches the pin handler or stops the polling thread
        println("pending ${sensor.pollInterrupt()}")                                      // Read and clear status, () → Int
                                                                                          // SOURCE_* value that fired, 0 = nothing pending

        sensor.setAddress(0x30)                                                           // Change I²C address, (address) → Unit
                                                                                          // volatile; this driver instance is now unusable
    }
    I2CConnection(bus, 0x30).use { moved ->                                               // open I²C bus, device address, (bus, address=0x30) → I2CConnection
        val sensor = VL53L0XFull(moved)                                                   // Create VL53L0X Full driver, (connection) → VL53L0XFull
                                                                                          // re-init at the new address is safe
        println("at 0x30 ${sensor.distance()} mm")                                        // Measure distance, () → Int mm
                                                                                          // same sensor, new address
        sensor.setAddress(VL53L0XMinimal.DEFAULT_ADDRESS)                                 // Change I²C address, (address) → Unit
                                                                                          // back to the power-on 0x29
    }
}
