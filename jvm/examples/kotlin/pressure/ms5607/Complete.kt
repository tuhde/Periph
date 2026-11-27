///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Ms5607Full

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x77").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Ms5607Full(connection)                                                                         // Create MS5607 driver, (connection) → Ms5607Full
        sensor.init()                                                                                               // Reset, read PROM, verify CRC-4, () → void
        val m = sensor.read()                                                                                       // Read temperature and pressure, () → double[] {°C, Pa}
                                                                                                                    // OSR 4096, second-order compensation applied
        System.out.printf("%.2f C, %.1f Pa%n", m[0], m[1])
        System.out.printf("pressure %.1f Pa%n", sensor.readPressure())                                              // Read pressure only, () → double Pa
        System.out.printf("temperature %.2f C%n", sensor.readTemperature())                                         // Read temperature only, () → double °C
                                                                                                                    // converts D2 only
        val raw = sensor.readRaw()                                                                                  // Read raw ADC codes, () → int[] {d1, d2}
                                                                                                                    // 24-bit pressure and temperature codes
        System.out.printf("raw D1 %d D2 %d%n", raw[0], raw[1])
        val c = sensor.compensate(raw[0], raw[1])                                                                   // Compensate raw codes, (d1, d2, secondOrder = true) → double[] {°C, Pa}
                                                                                                                    // pure computation, no bus access
        val c1 = sensor.compensate(raw[0], raw[1], false)
        System.out.printf("second order %.1f Pa, first order %.1f Pa%n", c[1], c1[1])
        sensor.setOversampling(1024)                                                                                // Select oversampling ratio, (osr) → void
                                                                                                                    // 256, 512, 1024, 2048 or 4096
        println("osr " + sensor.getOversampling())                                                                  // Current oversampling ratio, () → int
        println("conversion time " + sensor.conversionTime() + " s")                                                // Max conversion time, (osr = null) → double s
        sensor.startConversion(Ms5607Full.TEMPERATURE)                                                              // Start a conversion, (kind, osr = null) → void
                                                                                                                    // PRESSURE = D1, TEMPERATURE = D2; non-blocking
        Thread.sleep(12L)
        println("adc " + sensor.readAdc())                                                                          // Read the ADC result, () → int
                                                                                                                    // only after the conversion time, 24-bit
        sensor.setOversampling(4096)
        println("prom " + sensor.readProm().contentToString())                                                      // Read the PROM words, () → int[8]
        println("coefficients " + sensor.coefficients().contentToString())                                          // Calibration coefficients, () → int[] {C1 .. C6}
        println("crc ok " + sensor.promCrcOk())                                                                     // Verify the PROM CRC-4, () → boolean
        println("serial " + sensor.serialCode())                                                                    // Serial code, () → int
                                                                                                                    // PROM word 7 bits 15:4
        System.out.printf("altitude %.2f m%n", sensor.altitude())                                                   // Altitude from pressure, (seaLevelPa = 101325.0) → double m
                                                                                                                    // international barometric formula
        sensor.reset()                                                                                              // Reset and reload PROM, () → void
                                                                                                                    // 0x1E, 3 ms wait
    }
}
