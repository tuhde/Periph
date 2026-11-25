///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-kotlin:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.humidity.Htu31dFull

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Htu31dFull(connection)                                                                 // Create HTU31D driver, (connection) → Htu31dFull
        sensor.init()                                                                                       // Soft reset, () → void

        // --- Identify the chip and check its health ---
        // The serial number is unique per device; the diagnostic flags report NVM
        // and out-of-range errors.
        System.out.printf("serial 0x%06X%n", sensor.serialNumber())                                         // Read the serial number, () → int
        System.out.printf("diagnostic %s%n", sensor.diagnosticFlags())                                      // Named diagnostic flags, () → Map<String, Boolean>

        // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
        // The dew point shows how close the air is to condensing on a surface.
        for (i in 0 until 15) {
            val m = sensor.read()                                                                           // Read temperature and humidity, () → double[] {°C, %RH}
            System.out.printf("%.2f C  %.1f %%RH  dew %.2f C%n", m[0], m[1], sensor.dewPoint())             // Compute dew point, () → double °C
            Thread.sleep(2000L)
        }

        // --- Compare the four oversampling settings ---
        // Higher OSR gives finer resolution at the cost of a longer conversion.
        for (osr in 0 until 4) {
            sensor.setResolution(osr, osr)                                                                  // Set oversampling, (osrRh 0-3, osrT 0-3) → void
            val r = sensor.read()
            System.out.printf("OSR %d typ %.2f ms: %.2f C %.1f %%RH%n", osr, sensor.conversionTime() * 1000.0, r[0], r[1])
        }
        sensor.setResolution(3, 3)

        // --- Heater: drive off condensation for 10 s ---
        // Humidity falls and temperature rises while the heater runs; measure
        // before and after, never during, then let the sensor cool.
        val before = sensor.read()                                                                          // Read temperature and humidity, () → double[] {°C, %RH}
        sensor.setHeater(true)                                                                              // Switch the heater, (on) → void
        System.out.printf("heater on %b diagnostic 0x%02X%n", sensor.isHeaterOn(), sensor.diagnostic())     // Heater state and diagnostic byte, () → boolean / int
        Thread.sleep(10000L)
        sensor.setHeater(false)                                                                             // Switch the heater, (on) → void
        val after = sensor.read()
        System.out.printf("before %.2f C %.1f %%RH, after %.2f C %.1f %%RH%n", before[0], before[1], after[0], after[1])

        // --- Non-blocking conversion ---
        // Start the conversion, do other work, then collect the result.
        sensor.startConversion()                                                                            // Start a conversion, () → void
        Thread.sleep(30L)
        val result = sensor.readResult()                                                                    // Read last conversion result, () → double[] {°C, %RH}
        System.out.printf("result %.2f C %.1f %%RH%n", result[0], result[1])

        sensor.softReset()                                                                                  // Soft reset, () → void
    }
}
