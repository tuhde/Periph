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

        val m = sensor.read()                                                                               // Read temperature and humidity, () → double[] {°C, %RH}
                                                                                                            // OSR 3 / 3 by default, both CRCs verified, humidity clamped 0-100
        System.out.printf("%.2f C, %.1f %%RH%n", m[0], m[1])
        System.out.printf("humidity %.1f %%RH%n", sensor.readHumidity())                                    // Read humidity only, () → double %RH
                                                                                                            // command 0x10
        System.out.printf("temperature %.2f C%n", sensor.readTemperature())                                 // Read temperature only, () → double °C
        val raw = sensor.readRaw()                                                                          // Read raw codes, () → int[] {t_raw, rh_raw}
                                                                                                            // 16-bit unsigned
        System.out.printf("raw T %d raw RH %d%n", raw[0], raw[1])

        sensor.setResolution(1, 2)                                                                          // Set oversampling, (osrRh 0-3, osrT 0-3) → void
                                                                                                            // higher OSR = finer resolution, longer conversion
        val osr = sensor.getResolution()                                                                    // Get oversampling, () → int[] {osrRh, osrT}
        System.out.printf("osr rh %d osr t %d%n", osr[0], osr[1])
        System.out.printf("conversion %.4f s%n", sensor.conversionTime())                                   // Typical conversion time, () → double s
        sensor.setResolution(3, 3)

        sensor.startConversion()                                                                            // Start a conversion, () → void
                                                                                                            // non-blocking; wait conversionTime() before reading
        Thread.sleep(30L)
        val result = sensor.readResult()                                                                    // Read last conversion result, () → double[] {°C, %RH}
        System.out.printf("result %.2f C %.1f %%RH%n", result[0], result[1])

        sensor.setHeater(true)                                                                              // Switch the heater, (on) → void
                                                                                                            // 0x04; readings drift while the heater runs
        System.out.printf("heater on %b%n", sensor.isHeaterOn())                                            // Heater state, () → boolean
        sensor.setHeater(false)
        System.out.printf("diagnostic 0x%02X%n", sensor.diagnostic())                                       // Read the diagnostic byte, () → int
                                                                                                            // test against the DIAG_* masks
        System.out.printf("flags %s%n", sensor.diagnosticFlags())                                           // Named diagnostic flags, () → Map<String, Boolean>
        System.out.printf("serial 0x%06X%n", sensor.serialNumber())                                         // Read the serial number, () → int
                                                                                                            // 24-bit, CRC verified
        System.out.printf("dew point %.2f C%n", sensor.dewPoint())                                          // Compute dew point, () → double °C
                                                                                                            // one measurement, Magnus formula
        sensor.softReset()                                                                                  // Soft reset, () → void
                                                                                                            // 0x1E, waits 15 ms; heater off, OSR 3 / 3
    }
}
