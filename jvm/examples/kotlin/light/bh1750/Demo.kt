///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-kotlin:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.Bh1750Full
import it.uhde.periph.chips.light.Bh1750Minimal

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x23").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Bh1750Full(connection)                                                                 // Create BH1750 driver, (connection) → Bh1750Full
        sensor.init()                                                                                       // Power on, () → void

        // --- Light meter ---
        // One reading per mode, ten continuous values per second for 5 s (cover and
        // uncover the sensor), the effect of the lowest and highest sensitivity on the
        // raw count and the measurement time, reset and a power cycle.
        System.out.printf("H  %.2f lx%n", sensor.measureOnce(Bh1750Minimal.MODE_H))                         // One-time measurement, (mode) → double lx
        System.out.printf("H2 %.2f lx%n", sensor.measureOnce(Bh1750Minimal.MODE_H2))
        System.out.printf("L  %.2f lx%n", sensor.measureOnce(Bh1750Minimal.MODE_L))

        sensor.startContinuous(Bh1750Minimal.MODE_H)                                                        // Start continuous mode, (mode) → void
        repeat(50) {
            Thread.sleep(100L)
            System.out.printf("%.1f lx%n", sensor.readContinuous())                                         // Read the latest result, () → double lx
        }

        sensor.setSensitivity(0.45)                                                                         // Set sensitivity, (factor) → void
        System.out.printf("x0.45: MTreg %d, %.2f lx, raw %d, max %.0f ms%n", sensor.getMeasurementTimeRegister(), sensor.measureOnce(Bh1750Minimal.MODE_H), sensor.readRaw(), sensor.measurementTime() * 1000.0)
        sensor.setSensitivity(3.68)
        System.out.printf("x3.68: MTreg %d, %.2f lx, raw %d, max %.0f ms%n", sensor.getMeasurementTimeRegister(), sensor.measureOnce(Bh1750Minimal.MODE_H), sensor.readRaw(), sensor.measurementTime() * 1000.0)
        sensor.setSensitivity(1.0)

        sensor.reset()                                                                                      // Clear the data register, () → void
        sensor.powerDown()                                                                                  // Power down, () → void
        sensor.powerOn()                                                                                    // Power on, () → void
    }
}
