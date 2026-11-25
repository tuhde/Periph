///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-kotlin:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Ms5607Full

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x77").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Ms5607Full(connection)                                                                         // Create MS5607 driver, (connection) → Ms5607Full
        sensor.init()                                                                                               // Reset, read PROM, verify CRC-4, () → void

        // --- Calibration data ---
        // Six coefficients and a CRC-4 are factory-programmed into the PROM.
        println("PROM " + sensor.readProm().contentToString())                                                      // Read the PROM words, () → int[8]
        println("CRC ok " + sensor.promCrcOk())                                                                     // Verify the PROM CRC-4, () → boolean
        println("coefficients " + sensor.coefficients().contentToString() + " serial " + sensor.serialCode())

        // --- Barometric altimeter / variometer: 20 s, one reading per second ---
        val reference = sensor.readPressure()                                                                       // Read pressure only, () → double Pa
        var previous = 0.0
        var lowest = 100.0
        for (i in 0 until 20) {
            val m = sensor.read()                                                                                   // Read temperature and pressure, () → double[] {°C, Pa}
            lowest = minOf(lowest, m[0])
            val height = sensor.altitude(reference)                                                                 // Altitude from pressure, (seaLevelPa) → double m
            val climb = if (i == 0) 0.0 else height - previous
            previous = height
            System.out.printf("%.2f C  %.1f Pa  %.2f m  %+.2f m/s%n", m[0], m[1], height, climb)
            Thread.sleep(1000L)
        }

        // --- Noise and time versus oversampling: ten readings each ---
        for (osr in intArrayOf(256, 512, 1024, 2048, 4096)) {
            sensor.setOversampling(osr)                                                                             // Select oversampling ratio, (osr) → void
            val start = System.nanoTime()
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            repeat(10) {
                val v = sensor.readPressure()
                lo = minOf(lo, v)
                hi = maxOf(hi, v)
            }
            System.out.printf("OSR %4d  %.1f ms/read  spread %.1f Pa%n", osr, (System.nanoTime() - start) / 1.0e7, hi - lo)
        }
        sensor.setOversampling(4096)

        // --- Raw codes with and without the second-order correction ---
        val raw = sensor.readRaw()                                                                                  // Read raw ADC codes, () → int[] {d1, d2}
        println("raw " + raw[0] + " " + raw[1])
        val first = sensor.compensate(raw[0], raw[1], false)                                                        // Compensate raw codes, (d1, d2, secondOrder) → double[] {°C, Pa}
        val second = sensor.compensate(raw[0], raw[1])
        System.out.printf("first order  %.2f C %.1f Pa%n", first[0], first[1])
        System.out.printf("second order %.2f C %.1f Pa%n", second[0], second[1])
        System.out.printf("lowest temperature seen %.2f C%n", lowest)

        // --- Non-blocking conversion, then reset ---
        sensor.startConversion(Ms5607Full.PRESSURE)                                                                 // Start a conversion, (kind, osr = null) → void
        Thread.sleep(12L)
        println("D1 " + sensor.readAdc())                                                                           // Read the ADC result, () → int
        sensor.reset()                                                                                              // Reset and reload PROM, () → void
    }
}
