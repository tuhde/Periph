///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-kotlin:1.3.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.ADS1014Full

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, ADS1014Full.DEFAULT_ADDRESS).use { connection ->   // open I²C bus, device address, (bus, address=0x48) → I2CConnection
        val adc = ADS1014Full(connection)                                               // construct driver, (connection) → ADS1014Full
                                                                                        // no bus traffic; defaults ±2.048 V, 1600 SPS

        val raw = adc.readRaw()                                                         // Read raw code, () → int (−2048…2047)
                                                                                        // single-shot AIN0 − AIN1, polls OS until idle
        val v = adc.readVoltage()                                                       // Read voltage, () → double V
                                                                                        // raw × full-scale / 2048
        println("raw %d voltage %.4f V".format(raw, v))

        adc.setGain(ADS1014Full.PGA_1_024)                                              // Set PGA, (pga 0–5) → void
                                                                                        // ±1.024 V range, 0.5 mV per code
        println("gain %d".format(adc.getGain()))                                        // Read PGA code, () → int
                                                                                        // cached PGA[2:0]
        println("full scale %.3f V".format(adc.fullScale()))                            // Read full-scale range, () → double V
                                                                                        // ±FSR of the current gain
        println("lsb %.6f V".format(adc.lsb()))                                         // Read code size, () → double V
                                                                                        // fullScale() / 2048

        adc.setDataRate(ADS1014Full.DR_250)                                             // Set data rate, (dr 0–6) → void
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

        adc.setThresholds(-0.2, 0.5)                                                    // Set both thresholds, (low V, high V) → void
                                                                                        // converted with lsb(), clamped to the code range
        adc.setLowThresholdRaw(-200)                                                    // Set low threshold, (raw −2048…2047) → void
                                                                                        // LO_THRESH = raw << 4
        adc.setHighThresholdRaw(500)                                                    // Set high threshold, (raw −2048…2047) → void
                                                                                        // HI_THRESH = raw << 4
        val th = adc.getThresholdsRaw()                                                 // Read thresholds, () → int[] {low, high}
                                                                                        // sign-extended 12-bit codes
        println("thresholds %d %d".format(th[0], th[1]))

        adc.configureComparator(ADS1014Full.COMP_WINDOW, ADS1014Full.ACTIVE_LOW, true, ADS1014Full.QUEUE_2)  // Configure comparator, (mode, polarity, latch, queue) → void
                                                                                                             // window mode, latching, 2 consecutive conversions
        adc.clearAlert()                                                                // Release latched alert, () → void
                                                                                        // reads CONVERSION
        adc.disableComparator()                                                         // Disable comparator, () → void
                                                                                        // COMP_QUE = 11, ALERT/RDY high-impedance

        adc.enableInterrupt(ADS1014Full.SOURCE_CONVERSION_READY)                        // Route conversion-ready to ALERT/RDY, (source) → void
                                                                                        // HI_THRESH = 0x8000, LO_THRESH = 0, COMP_QUE = 00
        println("status %d".format(adc.pollInterrupt()))                                // Poll ALERT/RDY, () → int source
                                                                                        // INT pin level, else CONFIG.OS fallback
        adc.disableInterrupt(ADS1014Full.SOURCE_CONVERSION_READY)                       // Release ALERT/RDY, (source) → void
                                                                                        // COMP_QUE = 11

        adc.enableInterrupt(ADS1014Full.SOURCE_COMPARATOR)                              // Route comparator to ALERT/RDY, (source) → void
                                                                                        // restores the thresholds set above
        adc.onInterrupt({ status -> println("alert source $status") })                  // Subscribe to ALERT/RDY, (callback) → void
                                                                                        // pin edge via connection.intPin(), else a 5 ms polling thread
        Thread.sleep(1000)
        adc.offInterrupt()                                                              // Unsubscribe, () → void
                                                                                        // stops delivery
        adc.reset()                                                                     // Reset to power-up defaults, () → void
                                                                                        // rewrites CONFIG and both thresholds
    }
}
