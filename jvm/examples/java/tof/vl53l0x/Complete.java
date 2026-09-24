///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

// Exercises every method in the VL53L0X Full API, and finally moves the sensor
// to another I²C address and back to 0x29.

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.tof.VL53L0XFull;

import java.util.Arrays;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, VL53L0XFull.DEFAULT_ADDRESS)) {      // open I²C bus, device address, (bus, address=0x29) → I2CConnection
            var sensor = new VL53L0XFull(connection);                                     // Create VL53L0X Full driver, (connection) → VL53L0XFull
                                                                                          // runs init: ID check, tuning, SPADs, VHV + phase calibration

            System.out.printf("model 0x%02X%n", sensor.modelId());                        // Read model ID, () → int
                                                                                          // IDENTIFICATION_MODEL_ID, always 0xEE
            System.out.printf("revision 0x%02X%n", sensor.revisionId());                  // Read revision ID, () → int
                                                                                          // IDENTIFICATION_REVISION_ID, 0x10 on current silicon

            int d = sensor.distance();                                                    // Measure distance, () → int mm
                                                                                          // single shot; blocks for about one timing budget
            System.out.println("distance " + d + " mm, valid " + sensor.rangeValid());    // Check last measurement, () → boolean
                                                                                          // device range status == 11 (range complete)
            System.out.println("range status " + sensor.rangeStatus());                   // Read last range status, () → int 0–15
                                                                                          // 11 = valid, 4 = no target
            var m = sensor.readMeasurement();                                             // Read result block, () → Measurement
                                                                                          // distance, status, signal/ambient MCPS, SPAD count
            System.out.printf("signal %.2f MCPS, ambient %.2f MCPS%n", m.signalRateMcps(), m.ambientRateMcps());

            sensor.startContinuous();                                                     // Start continuous ranging, (periodMs=0 ms) → void
                                                                                          // 0 = back-to-back measurements
            for (int i = 0; i < 5; i++) {
                System.out.println("continuous " + sensor.readContinuous() + " mm");      // Read next continuous result, () → int mm
                                                                                          // waits for a fresh data-ready, then clears it
            }
            sensor.stopContinuous();                                                      // Stop continuous ranging, () → void
                                                                                          // does not wait for a running measurement

            sensor.startContinuous(100);                                                  // Start continuous ranging, (periodMs=0 ms) → void
                                                                                          // timed mode: one measurement every 100 ms
            while (!sensor.dataReady()) {                                                 // Check for a result, () → boolean
                Thread.sleep(10);                                                         // RESULT_INTERRUPT_STATUS bits 2:0 non-zero
            }
            System.out.println("timed " + sensor.readMeasurement().distanceMm() + " mm"); // Read result block, () → Measurement
                                                                                          // non-blocking; clears the interrupt
            sensor.stopContinuous();                                                      // Stop continuous ranging, () → void
                                                                                          // back to software standby

            System.out.println("budget " + sensor.timingBudget() + " us");                // Read timing budget, () → int µs
                                                                                          // computed from the sequence-step timeouts
            sensor.setTimingBudget(50000);                                                // Set timing budget, (budgetUs µs) → void
                                                                                          // longer budget = lower noise, ≥ 20000 µs
            System.out.println("signal limit " + sensor.signalRateLimit() + " MCPS");     // Read signal-rate limit, () → double MCPS
                                                                                          // 9.7 fixed point
            sensor.setSignalRateLimit(0.1);                                               // Set signal-rate limit, (limitMcps MCPS) → void
                                                                                          // lower = longer range, more noise
            sensor.setVcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE, 18);        // Set VCSEL period, (type, pclks) → void
                                                                                          // pre-range 12/14/16/18; redoes phase calibration
            sensor.setVcselPulsePeriod(VL53L0XFull.VcselPeriodType.FINAL_RANGE, 14);      // Set VCSEL period, (type, pclks) → void
                                                                                          // final-range 8/10/12/14
            System.out.println("vcsel " + sensor.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE)   // Read VCSEL period, (type) → int PCLKs
                    + "/" + sensor.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.FINAL_RANGE));            // (reg + 1) × 2
            sensor.setProfile(VL53L0XFull.Profile.DEFAULT);                               // Apply ranging profile, (profile) → void
                                                                                          // 0.25 MCPS, 14/10 PCLKs, 33 ms

            double original = sensor.offset();                                            // Read range offset, () → double mm
                                                                                          // NVM factory value, 0.25 mm steps
            sensor.setOffset(original - 5.0);                                             // Set range offset, (offsetMm mm) → void
                                                                                          // volatile override, −512.0 to 511.75 mm
            System.out.println("offset " + sensor.offset() + " mm");                      // Read range offset, () → double mm
                                                                                          // 12-bit two's complement × 0.25
            sensor.setOffset(original);                                                   // Set range offset, (offsetMm mm) → void
                                                                                          // restore the factory value
            sensor.setCrosstalkCompensation(0.0);                                         // Set crosstalk compensation, (rateMcps MCPS) → void
                                                                                          // 0 = compensation off

            sensor.recalibrate();                                                         // Rerun reference calibration, () → void
                                                                                          // VHV + phase; needed after a > 8 °C change

            sensor.setInterruptThresholds(100, 800);                                      // Set distance thresholds, (lowMm mm, highMm mm) → void
                                                                                          // 2 mm resolution
            System.out.println("thresholds " + Arrays.toString(sensor.interruptThresholds()));  // Read distance thresholds, () → int[] {low mm, high mm}
                                                                                          // decoded from SYSTEM_THRESH_LOW/HIGH
            sensor.enableInterrupt(VL53L0XFull.SOURCE_OUT_OF_WINDOW);                     // Select interrupt source, (source) → void
                                                                                          // replaces the active source (mutually exclusive)
            sensor.disableInterrupt(VL53L0XFull.SOURCE_OUT_OF_WINDOW);                    // Disable interrupt source, (source) → void
                                                                                          // only if it is the active one
            sensor.enableInterrupt(VL53L0XFull.SOURCE_NEW_SAMPLE_READY);                  // Select interrupt source, (source) → void
                                                                                          // back to the default data-ready source

            sensor.onInterrupt(status -> System.out.println("interrupt, source " + status));  // Subscribe to GPIO1, (callback) → void
                                                                                          // status is read and cleared before the callback
            sensor.startContinuous(200);                                                  // Start continuous ranging, (periodMs=0 ms) → void
                                                                                          // timed mode feeds the subscription
            Thread.sleep(1000);
            sensor.stopContinuous();                                                      // Stop continuous ranging, () → void
                                                                                          // no more samples
            sensor.offInterrupt();                                                        // Unsubscribe, () → void
                                                                                          // detaches the pin handler or stops the polling thread
            System.out.println("pending " + sensor.pollInterrupt());                      // Read and clear status, () → int
                                                                                          // SOURCE_* value that fired, 0 = nothing pending

            sensor.setAddress(0x30);                                                      // Change I²C address, (address) → void
                                                                                          // volatile; this driver instance is now unusable
        }
        try (var moved = new I2CConnection(bus, 0x30)) {                                  // open I²C bus, device address, (bus, address=0x30) → I2CConnection
            var sensor = new VL53L0XFull(moved);                                          // Create VL53L0X Full driver, (connection) → VL53L0XFull
                                                                                          // re-init at the new address is safe
            System.out.println("at 0x30 " + sensor.distance() + " mm");                   // Measure distance, () → int mm
                                                                                          // same sensor, new address
            sensor.setAddress(VL53L0XFull.DEFAULT_ADDRESS);                               // Change I²C address, (address) → void
                                                                                          // back to the power-on 0x29
        }
    }
}
