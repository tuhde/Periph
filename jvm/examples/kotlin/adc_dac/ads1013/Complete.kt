///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-kotlin:1.3.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.ADS1013Full

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, ADS1013Full.DEFAULT_ADDRESS).use { connection ->   // open I²C bus, device address, (bus, address=0x48) → I2CConnection
        val adc = ADS1013Full(connection)                                               // construct driver, (connection) → ADS1013Full
                                                                                        // no bus traffic; fixed ±2.048 V, 1600 SPS

        val raw = adc.readRaw()                                                         // Read raw code, () → int (−2048…2047)
                                                                                        // single-shot AIN0 − AIN1, polls OS until idle
        val v = adc.readVoltage()                                                       // Read voltage, () → double V
                                                                                        // raw × 0.001
        println("raw %d voltage %.4f V".format(raw, v))
        println("full scale %.3f V".format(adc.fullScale()))                            // Read full-scale range, () → double V
                                                                                        // always 2.048
        println("lsb %.6f V".format(adc.lsb()))                                         // Read code size, () → double V
                                                                                        // always 0.001

        adc.setDataRate(ADS1013Full.DR_250)                                             // Set data rate, (dr 0–6) → void
                                                                                        // DR_250 = 250 SPS
        println("dr code %d".format(adc.getDataRate()))                                 // Read data-rate code, () → int
                                                                                        // cached DR[2:0]
        println("sps %d".format(adc.dataRateSps()))                                     // Read data rate, () → int SPS
                                                                                        // table lookup of the DR code

        adc.startContinuous()                                                           // Start continuous conversion, () → void
                                                                                        // MODE = 0 at the current data rate
        Thread.sleep(20)
        println("continuous raw %d".format(adc.readContinuousRaw()))                    // Read latest code, () → int
                                                                                        // reads CONVERSION without touching Config
        println("continuous %.4f V".format(adc.readContinuous()))                       // Read latest voltage, () → double V
                                                                                        // raw × lsb()
        adc.stopContinuous()                                                            // Stop continuous conversion, () → void
                                                                                        // MODE = 1, back to single-shot
        println("converting %b".format(adc.isConverting()))                             // Check conversion in progress, () → boolean
                                                                                        // CONFIG.OS = 0 (single-shot only)
        println("config 0x%04X".format(adc.readConfig()))                               // Read CONFIG register, () → int
                                                                                        // raw 16-bit register
        adc.reset()                                                                     // Reset to power-up defaults, () → void
                                                                                        // rewrites CONFIG = 0x8583
    }
}
