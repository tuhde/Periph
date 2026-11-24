///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-kotlin:1.5.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.Si1132Full

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x60").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Si1132Full(connection)                                                                   // Create Si1132 driver, (connection) → void


        // --- Identify the chip and show the factory calibration ---
        // The calibration bytes belong to the Silicon Labs Si114x calibration
        // structure; they let you correct part-to-part variation of the UV index.
        val cal = sensor.readCalibration()                                                                    // Read factory calibration, () → int[12]
        System.out.println("calibration first byte 0x" + Integer.toHexString(cal[0]))

        // --- UV and light monitor: 20 readings, 1 s apart ---
        // Index, visible and infrared counts side by side show whether the
        // light is sunlight (high infrared share) or artificial.
        repeat(20) {
            val m = sensor.read()                                                                             // Read UV index and ALS counts, () → double[]
            System.out.printf("UV %.2f  vis %.0f  ir %.0f%n", m[0], m[1], m[2])
            val raw = sensor.readRaw()                                                                        // Read data registers, () → int[]
            System.out.println("raw " + raw[0] + " " + raw[1] + " " + raw[2])
            Thread.sleep(1000)
        }

        // --- Effect of gain and of the high-range bits on the same light ---
        // Each gain step doubles the integration time (and the count); the high
        // range divides the sensitivity by 14.5 for direct sunlight.
        for (gain in intArrayOf(0, 2, 4)) {
            sensor.setGain(gain, gain)                                                                        // Set ADC gain, (visible 0–7, infrared 0–7) → void
            val g = sensor.read()                                                                             // Read UV index and ALS counts, () → double[]
            System.out.println("gain " + gain + " visible " + g[1] + " infrared " + g[2])
        }
        sensor.setGain(0, 0)
        sensor.setHighRange(true, true)                                                                       // Set high signal range, (visible, infrared) → void
        val hr = sensor.read()
        System.out.println("high range visible " + hr[1] + " infrared " + hr[2])
        sensor.setHighRange(false, false)
        sensor.setAlignment(false, false)                                                                     // Set data alignment, (visibleLow, infraredLow) → void
        sensor.setUvCoefficients(intArrayOf(0x7B, 0x6B, 0x01, 0x00))                                          // Set UV coefficients, (ucoef int[4]) → void

        // --- Auxiliary channels ---
        // The temperature value is relative only; the VDD count tracks the supply.
        System.out.println("temperature raw " + sensor.readTemperatureRaw())                                  // Read die temperature, () → int
        System.out.println("vdd raw " + sensor.readVddRaw())                                                  // Read supply voltage, () → int

        // --- Autonomous mode at 0.5 s with the interrupt ---
        // Every completed measurement raises INT; the callback prints the status.
        sensor.startAutonomous(0.5)                                                                           // Start autonomous mode, (interval 0–2.05 s) → void
        sensor.onInterrupt({ s -> System.out.println("INT 0x" + Integer.toHexString(s)) })                    // Subscribe to measurements, (callback) → void
        Thread.sleep(5000)
        sensor.offInterrupt()                                                                                 // Unsubscribe, () → void
        sensor.pause()                                                                                        // Pause autonomous mode, () → void
        System.out.println("ready " + sensor.measurementReady())                                              // Check measurement done, () → boolean
        val resp = sensor.readResponse()                                                                      // Read command response, () → int[] {counter, error}
        System.out.println("response " + resp[0] + " error " + resp[1])
        sensor.enableInterrupt()                                                                              // Enable the INT pin, () → void
        sensor.force()                                                                                        // Start a forced measurement, () → void
        Thread.sleep(50)
        System.out.println("poll " + sensor.pollInterrupt())                                                  // Poll interrupt status, () → int
        sensor.disableInterrupt()                                                                             // Disable the INT pin, () → void

        // --- UV only, then shut down ---
        // Dropping the ALS channels shortens each measurement.
        sensor.setChannels(true, false, false, false)                                                         // Select channels, (uv, visible, infrared, aux) → void
        System.out.println("UV only " + sensor.readUvIndex())                                                 // Read UV index, () → double
        sensor.setI2cAddress(0x60)                                                                            // Set I²C address, (address 7-bit) → void
        sensor.reset()                                                                                        // Reset the chip, () → void
    }
}
