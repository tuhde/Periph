///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.tof.VL6180XFull
import java.util.function.IntConsumer
import it.uhde.periph.chips.tof.VL6180XMinimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, VL6180XMinimal.DEFAULT_ADDRESS, null, null, 2).use { connection ->  // open I²C bus, device address, register width 2, (bus, address=0x29, null, null, regBytes=2) → I2CConnection
        val sensor = VL6180XFull(connection)                                              // Create VL6180X driver, (connection) → VL6180XFull
        println("range" + " " + sensor.readRange())                                   // Single-shot range, () → int mm
                                                                                      // throws IOException on a non-zero range error code; see lastError()
        println("last error" + " " + sensor.lastError())                              // Last range error code, () → int
        val st = sensor.readRangeStatus()                                             // Range and error code, () → int[] {mm, error}
                                                                                      // never throws on a range error; 0 = valid
        println("range status" + " " + st.contentToString())
        println("als raw" + " " + sensor.readAlsRaw())                                // Single-shot ALS count, () → int
        println("lux" + " " + sensor.readLux())                                       // Single-shot ALS in lux, () → double lux
                                                                                      // 0.32 lux/count at gain 1, 100 ms

        sensor.startContinuousRange(50)                                               // Start continuous ranging, (periodMs) → void
                                                                                      // 10-2550 ms in 10 ms steps
        println("continuous" + " " + sensor.readRangeContinuous())                    // Wait for next sample, () → int mm
        sensor.stopContinuousRange()                                                  // Stop continuous ranging, () → void
        sensor.startContinuousAls(100)                                                // Start continuous ALS, (periodMs) → void
        sensor.stopContinuousAls()                                                    // Stop continuous ALS, () → void
        sensor.setInterleaved(false)                                                  // Interleaved ALS + range, (on) → void

        sensor.setAlsGain(6)                                                          // ALS gain code, (code) → void
                                                                                      // 0 = 20 ... 6 = 1, 7 = 40
        sensor.setAlsIntegrationTime(100)                                             // ALS integration time, (ms) → void
                                                                                      // 1-512 ms
        sensor.setAlsScaler(1)                                                        // ALS result scaler, (scaler) → void
        sensor.setRangeThresholds(30, 80)                                             // Range thresholds, (low, high) → void
        sensor.setAlsThresholds(100, 5000)                                            // ALS thresholds, (low, high) → void
        sensor.setMaxConvergenceTime(49)                                              // Max convergence time, (ms) → void
        sensor.setAveragingPeriod(48)                                                 // Averaging period, (units) → void
        sensor.setRangeOffset(0)                                                      // Part-to-part offset, (mm) → void
        println("offset" + " " + sensor.getRangeOffset())                             // Read the offset, () → int mm
        sensor.setCrosstalkRate(0.0)                                                  // Crosstalk rate, (rateMcps) → void
        sensor.recalibrateVhv()                                                       // Recalibrate VHV, () → void

        println("model" + " " + sensor.modelId())                                     // Model ID, () → int
                                                                                      // always 0xB4
        println("revision" + " " + sensor.revision().contentToString())               // Model revision, () → int[] {major, minor}
        println("history" + " " + sensor.readHistory().contentToString())             // History buffer, () → int[8]

        sensor.enableInterrupt(VL6180XMinimal.SOURCE_RANGE, VL6180XMinimal.MODE_OUT_OF_WINDOW) // Enable an interrupt, (source, mode) → void
        println("pending" + " " + sensor.pollInterrupt())                             // Read and clear, () → int SOURCE_* mask
        sensor.onInterrupt(IntConsumer { println("irq " + it) })                      // Subscribe, (callback) → void
                                                                                      // connection.intPin() falling edge, else a 5 ms polling thread
        Thread.sleep(100)
        sensor.offInterrupt()                                                         // Unsubscribe, () → void
        sensor.disableInterrupt(VL6180XMinimal.SOURCE_RANGE)                          // Disable an interrupt, (source) → void
        sensor.enableInterrupt(VL6180XMinimal.SOURCE_RANGE, VL6180XMinimal.MODE_NEW_SAMPLE_READY)
        // sensor.setAddress(0x30)                                                    // Change address (volatile), (address) → void
    }
}
