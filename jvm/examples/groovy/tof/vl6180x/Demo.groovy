///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.tof.VL6180XFull
import it.uhde.periph.chips.tof.VL6180XMinimal

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, VL6180XMinimal.DEFAULT_ADDRESS, null, null, 2)   // open I²C bus, device address, register width 2, (bus, address=0x29, null, null, regBytes=2) → I2CConnection
try {
    def sensor = new VL6180XFull(connection)                                         // Create VL6180X driver, (connection) → VL6180XFull
    // --- Identification ---
    println("model" + " " + sensor.modelId() + " revision " + sensor.revision())  // Model ID, () → int ; () → int[] {major, minor}

    // --- Single-shot ranging at 5 Hz for 10 s ---
    50.times {
        println("mm" + " " + sensor.readRange())                                  // Single-shot range, () → int mm
        Thread.sleep(200)
    }

    // --- Continuous ranging at 50 ms: move a hand towards the sensor ---
    sensor.startContinuousRange(50)                                               // Start continuous ranging, (periodMs) → void
    40.times {
        def st = sensor.readRangeStatus()                                         // Range and error code, () → int[] {mm, error}
        println("mm" + " " + st[0] + " (error " + st[1] + ")")
    }
    sensor.stopContinuousRange()                                                  // Stop continuous ranging, () → void
    Thread.sleep(200)

    // --- Out-of-window interrupt for 30-80 mm ---
    sensor.setRangeThresholds(30, 80)                                             // Range thresholds, (low, high) → void
    sensor.enableInterrupt(VL6180XMinimal.SOURCE_RANGE, VL6180XMinimal.MODE_OUT_OF_WINDOW) // Enable an interrupt, (source, mode) → void
    sensor.startContinuousRange(50)
    50.times {
        if ((sensor.pollInterrupt() & VL6180XMinimal.SOURCE_RANGE) != 0) {        // Read and clear, () → int SOURCE_* mask
            println("range" + " " + "out of window")
        }
        Thread.sleep(100)
    }
    sensor.stopContinuousRange()
    Thread.sleep(200)
    sensor.enableInterrupt(VL6180XMinimal.SOURCE_RANGE, VL6180XMinimal.MODE_NEW_SAMPLE_READY)

    // --- Light meter at gain 1 and 20 ---
    [6, 0].each { int code ->
        sensor.setAlsGain(code)                                                   // ALS gain code, (code) → void
        println("gain code " + code + " " + sensor.readLux())                     // Single-shot ALS in lux, () → double lux
    }
    sensor.setAlsGain(6)

    // --- Interleaved ALS + range for 5 s ---
    sensor.setInterleaved(true)                                                   // Interleaved ALS + range, (on) → void
    sensor.startContinuousAls(100)                                                // Start continuous ALS, (periodMs) → void
    Thread.sleep(5000)
    sensor.stopContinuousAls()                                                    // Stop continuous ALS, () → void
    sensor.setInterleaved(false)

    // --- Calibration and diagnostics ---
    sensor.setRangeOffset(0)                                                      // Part-to-part offset, (mm) → void
    sensor.setCrosstalkRate(0.0)                                                  // Crosstalk rate, (rateMcps) → void
    sensor.setAlsIntegrationTime(100)                                             // ALS integration time, (ms) → void
    sensor.setAlsScaler(1)                                                        // ALS result scaler, (scaler) → void
    sensor.recalibrateVhv()                                                       // Recalibrate VHV, () → void
    println("als raw" + " " + sensor.readAlsRaw() + ", offset " + sensor.getRangeOffset()) // Single-shot ALS count, () → int ; () → int mm
    println("history" + " " + sensor.readHistory())                               // History buffer, () → int[8]

    // --- Change the device address and back (volatile) ---
    sensor.setAddress(0x30)                                                       // Change address, (address) → void
    def moved = new I2CConnection(bus, 0x30, null, null, 2)
    try {
        def sensor2 = new VL6180XFull(moved)
        println "on 0x30: model " + sensor2.modelId()
        sensor2.setAddress(VL6180XMinimal.DEFAULT_ADDRESS)
    } finally {
        moved.close()
    }
} finally {
    connection.close()
}
