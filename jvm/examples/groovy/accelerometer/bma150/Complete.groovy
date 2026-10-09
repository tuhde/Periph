///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.Bma150Full

def bus  = System.getenv("I2C_BUS")?.toInteger() ?: 1
def addr = System.getenv("I2C_ADDR")?.replaceFirst("^0[xX]", "")?.toInteger(16) ?: 0x38
new I2CConnection(bus, addr).withCloseable { connection ->
    def accel = new Bma150Full(connection)         // Create BMA150 Full driver, (connection)
    accel.setRange(8)                              // Set measurement range, (rangeG) → g
                                                   // selects ±8 g; LSB scale changes from 256 to 64 LSB/g
    accel.setBandwidth(190)                        // Set bandwidth, (bandwidthHz) → Hz
                                                   // picks nearest valid value (190 Hz)
    def raw = accel.readRaw()                      // Read raw 10-bit counts, () → [int, int, int]
                                                   // signed 10-bit two's-complement acceleration counts
    def temp = accel.readTemperature()             // Read temperature, () → °C
                                                   // 0.5 °C/LSB, 0x00 maps to −30 °C
    def ready = accel.newDataAvailable()           // Check new data, () → boolean
                                                   // true once all three new_data_X/Y/Z bits are set
    accel.setShadow(false)                         // Set shadow mode, (enabled) → None
                                                   // keep LSB-then-MSB ordering (shadow_dis=0)
    accel.setLowG(0.4, 40)                        // Configure low-g, (thresholdG, durationMs, hysteresisG=0, counter=0) → g, ms
                                                   // 0.4 g threshold, 40 ms duration; enables SOURCE_LOW_G
    accel.setHighG(4.0, 2)                        // Configure high-g, (thresholdG, durationMs, hysteresisG=0, counter=0) → g, ms
                                                   // 4.0 g threshold, 2 ms duration; enables SOURCE_HIGH_G
    accel.setAnyMotion(0.5, 3)                    // Configure any-motion, (thresholdG, samples=1) → g, samples
                                                   // 0.5 g threshold, 3 consecutive samples; enables SOURCE_ANY_MOTION
    accel.setAlert(false)                          // Toggle alert mode, (enabled) → None
                                                   // mutually exclusive with any-motion; not used here
    accel.setLatch(true)                           // Set latched interrupts, (enabled) → None
                                                   // latched until clearInterrupt(); latch_INT=1
    def status = accel.pollInterrupt()             // Read STATUS, () → int
                                                   // STATUS byte; does not clear latched bits
    accel.clearInterrupt()                         // Clear latched interrupts, () → None
                                                   // writes reset_INT to CTRL (cleared on next sample)
    accel.setWakeUp(true, 80)                      // Set self-wake-up, (enabled, pauseMs=20) → ms
                                                   // 80 ms sleep portion of the cycle

    def xyz = accel.read()                          // Read 3-axis acceleration, () → [g, g, g]
                                                   // burst read of 0x02–0x07, scale 64 LSB/g
    def v = accel.readVersion()                    // Read version, () → [int, int]
                                                   // (al_version, ml_version) from VERSION register
    def c1 = accel.readCustomer(0)                 // Read scratch byte, (index) → int
                                                   // 0 → CUSTOMER_1, 1 → CUSTOMER_2
    accel.writeCustomer(0, 0xA5)                   // Write scratch byte, (index, value) → None
                                                   // 0xA5 into CUSTOMER_1
    def st = accel.selfTest()                       // Run self-test, () → boolean
                                                   // electrostatic self-test; reads STATUS.st_result
    accel.softReset()                              // Soft reset, () → None
                                                   // CTRL.soft_reset=1; 30 ms wait; range/bandwidth restored
    accel.sleep()                                   // Enter sleep mode, () → None
    accel.wake()                                    // Leave sleep mode, () → None
                                                   // 1.5 ms settle wait

    printf("raw=(%d,%d,%d) temp=%.1f status=0x%02X al=%d ml=%d c1=0x%02X st=%s%n",
            raw[0], raw[1], raw[2], temp, status, v[0], v[1], c1, st ? "PASS" : "FAIL")
    printf("xyz=(%.3f, %.3f, %.3f) ready=%s%n", xyz[0], xyz[1], xyz[2], ready)
}
