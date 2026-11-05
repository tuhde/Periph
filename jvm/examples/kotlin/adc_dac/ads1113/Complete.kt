///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.2
//DEPS it.uhde:periph-kotlin:1.3.2

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.ADS1113Full

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, ADS1113Full.DEFAULT_ADDRESS).use { connection ->                 // open I²C bus, device address, (bus, address=0x48) → I2CConnection
        val adc = ADS1113Full(connection)                                                   // construct driver, (connection) → ADS1113Full
                                                                                            // no bus traffic; Config word is rebuilt per conversion
        val raw = adc.readRaw()                                                             // Read raw code, () → int
                                                                                            // single-shot conversion, signed 16-bit
        val v = adc.readVoltage()                                                           // Read voltage, () → double V
                                                                                            // raw × lsb()
        println("raw %d, %.5f V".format(raw, v))
        val fs = adc.fullScale()                                                            // Full scale, () → double V
                                                                                            // fixed ±2.048 V
        val lsb = adc.lsb()                                                                 // LSB, () → double V
                                                                                            // fullScale() / 32768
        println("full scale %.3f V, lsb %.7f V".format(fs, lsb))
        adc.setDataRate(ADS1113Full.DR_250)                                                 // Set data rate, (dr 0–7) → void
                                                                                            // DR field of the Config word
        val sps = adc.dataRateSps()                                                         // Data rate, () → int SPS
                                                                                            // samples per second of the current setting
        println("rate %d SPS".format(sps))
        adc.startContinuous()                                                               // Start continuous, () → void
                                                                                            // MODE ← 0
        Thread.sleep(50)
        val craw = adc.readContinuousRaw()                                                  // Read continuous raw, () → int
                                                                                            // CONVERSION without touching Config
        val cv = adc.readContinuous()                                                       // Read continuous, () → double V
                                                                                            // readContinuousRaw() × lsb()
        println("continuous %d, %.5f V".format(craw, cv))
        adc.stopContinuous()                                                                // Stop continuous, () → void
                                                                                            // MODE ← 1
        val busy = adc.isConverting()                                                       // Check conversion, () → boolean
                                                                                            // CONFIG.OS == 0
        val cfg = adc.readConfig()                                                          // Read CONFIG, () → int
                                                                                            // raw 16-bit register
        println("converting %b, config 0x%04X".format(busy, cfg))
        adc.reset()                                                                         // Reset, () → void
                                                                                            // writes the reset values back
    }
}
