///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.ADS1013Full

// Battery-voltage logger: the cell is read through a 2:1 resistor divider
// (AIN0, AIN1 to GND).

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, ADS1013Full.DEFAULT_ADDRESS).use { connection ->   // open I²C bus, device address, (bus, address=0x48) → I2CConnection
        val adc = ADS1013Full(connection)                                               // construct driver, (connection) → ADS1013Full
        val dividerRatio = 2.0

        // --- Slow, low-noise single-shot logging ---
        // 128 SPS gives the lowest noise; one conversion per second keeps the chip
        // in power-down in between.
        adc.setDataRate(ADS1013Full.DR_128)                                             // Set data rate, (dr 0–6) → void
        repeat(10) {
            val raw = adc.readRaw()                                                     // Read raw code, () → int (−2048…2047)
            val v = adc.readVoltage()                                                   // Read voltage, () → double V
            val battery = v * dividerRatio
            println("raw %d divider %.3f V battery %.3f V".format(raw, v, battery))
            Thread.sleep(1000)
        }

        // --- Fast continuous averaging ---
        // At 3300 SPS in continuous mode, 100 samples take about 30 ms.
        adc.setDataRate(ADS1013Full.DR_3300)                                            // Set data rate, (dr 0–6) → void
        adc.startContinuous()                                                           // Start continuous conversion, () → void
        Thread.sleep(10)
        var total = 0.0
        repeat(100) {
            total += adc.readContinuous()                                               // Read latest voltage, () → double V
            Thread.sleep(1)
        }
        adc.stopContinuous()                                                            // Stop continuous conversion, () → void
        println("average battery %.3f V".format(total / 100 * dividerRatio))
    }
}
