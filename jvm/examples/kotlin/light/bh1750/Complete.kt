///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-kotlin:1.5.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.Bh1750Full
import it.uhde.periph.chips.light.Bh1750Minimal

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x23").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Bh1750Full(connection)                                                                 // Create BH1750 driver, (connection) → Bh1750Full
        sensor.init()                                                                                       // Power on, () → void
                                                                                                            // opcode 0x01; the chip powers down by itself after each one-time measurement

        val lux = sensor.read()                                                                             // Measure illuminance, () → double lx
                                                                                                            // one-time H-resolution (1 lx), waits 180 ms
        System.out.printf("illuminance %.1f lx%n", lux)
        System.out.printf("raw %d%n", sensor.readRaw())                                                     // Read the raw count, () → int
                                                                                                            // 16-bit, one one-time measurement in the current mode
        System.out.printf("H2 %.2f lx%n", sensor.measureOnce(Bh1750Minimal.MODE_H2))                        // One-time measurement, (mode MODE_H|MODE_H2|MODE_L) → double lx
                                                                                                            // MODE_H2 = 0.5 lx steps, MODE_L = 4 lx steps and 24 ms
        System.out.printf("measurement time %.3f s%n", sensor.measurementTime())                            // Maximum measurement time, (mode=MODE_H) → double s
        System.out.printf("lux per count %.4f%n", sensor.luxPerCount())                                     // Lux per raw count, (mode=MODE_H) → double

        sensor.startContinuous(Bh1750Minimal.MODE_H)                                                        // Start continuous mode, (mode=MODE_H) → void
                                                                                                            // first result after measurementTime()
        Thread.sleep(200L)
        System.out.printf("continuous %.1f lx%n", sensor.readContinuous())                                  // Read the latest result, () → double lx
                                                                                                            // no wait

        sensor.setMeasurementTime(138)                                                                      // Set MTreg, (mtreg 31-254) → void
                                                                                                            // default 69; larger = more sensitive and slower
        System.out.printf("mtreg %d%n", sensor.getMeasurementTimeRegister())                                // Get MTreg, () → int
                                                                                                            // cached, the chip has no read-back
        sensor.setSensitivity(1.0)                                                                          // Set sensitivity, (factor 0.45-3.68) → void
                                                                                                            // MTreg = round(69 x factor)

        sensor.powerDown()                                                                                  // Power down, () → void
        sensor.powerOn()                                                                                    // Power on, () → void
        sensor.reset()                                                                                      // Clear the data register, () → void
                                                                                                            // powers on first; MTreg and mode are kept
    }
}
