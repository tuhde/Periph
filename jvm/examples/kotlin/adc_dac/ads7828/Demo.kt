///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.ADS7828Full

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, ADS7828Full.DEFAULT_ADDRESS).use { connection ->              // open I²C bus, device address, (bus, address=0x48) → I2CConnection
        val adc = ADS7828Full(connection)                                                           // construct driver, (connection, vref=null → internal 2.5 V) → ADS7828Full
        adc.init()                                                                                  // Enable internal reference, () → Unit
        val divider = 2.0   // inputs sit behind 2:1 dividers (rails up to 5 V)
        val channels = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7)

        // --- Eight-channel supply monitor ---
        // Scan CH0–CH7 with the internal 2.5 V reference, print raw codes and rail
        // voltages (after the divider ratio) and keep a running minimum/maximum per channel.
        val vmin = DoubleArray(8) { 99.0 }
        val vmax = DoubleArray(8)
        repeat(5) {
            val raws = adc.readScan(channels)                                                       // Scan channels, (channels int[]) → int[]
            for (ch in 0 until 8) {
                val volts = raws[ch] * adc.lsb() * divider
                vmin[ch] = minOf(vmin[ch], volts)
                vmax[ch] = maxOf(vmax[ch], volts)
                println("ch$ch raw=${raws[ch]} $volts V")
            }
            Thread.sleep(1000)
        }
        println("min ${vmin.toList()} max ${vmax.toList()}")

        // --- Read a bridge sensor on CH0/CH1 ---
        // The signed differential read does two conversions (normal and inverted
        // polarity) so the sign of the bridge output is preserved.
        val mv = adc.readSignedDifferential(0) * 1000.0                                             // Read pair 0 signed, (pair 0–3) → double V
        println("bridge $mv mV")

        // --- Second scan against an external 5.0 V reference ---
        // Switching to a 5 V external reference gives full-scale range up to VDD.
        adc.setReference(5.0)                                                                       // Use external reference, (vref Double V; null = internal) → Unit
        val ext = adc.readScan(channels)                                                            // Scan channels, (channels int[]) → int[]
        println("ext 5 V scan ${ext.toList()}")

        // --- Finish in full power-down ---
        adc.powerDown()                                                                             // Power down completely, () → Unit
    }
}
