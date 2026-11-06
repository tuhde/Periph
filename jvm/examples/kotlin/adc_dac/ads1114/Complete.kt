///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-kotlin:1.3.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.ADS1114Full

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, ADS1114Full.DEFAULT_ADDRESS).use { connection ->                 // open I²C bus, device address, (bus, address=0x48) → I2CConnection
        val adc = ADS1114Full(connection)                                                   // construct driver, (connection) → ADS1114Full
                                                                                            // no bus traffic; Config word is rebuilt per conversion
        val raw = adc.readRaw()                                                             // Read raw code, () → int
                                                                                            // single-shot conversion, signed 16-bit
        val v = adc.readVoltage()                                                           // Read voltage, () → double V
                                                                                            // raw × lsb()
        println("raw %d, %.5f V".format(raw, v))
        adc.setGain(ADS1114Full.PGA_4_096)                                                  // Set gain, (pga 0–5) → void
                                                                                            // ±4.096 V full scale
        println("gain %d".format(adc.getGain()))
        val fs = adc.fullScale()                                                            // Full scale, () → double V
                                                                                            // ±FSR of the current gain
        val lsb = adc.lsb()                                                                 // LSB, () → double V
                                                                                            // fullScale() / 32768
        println("full scale %.3f V, lsb %.7f V".format(fs, lsb))
        adc.setDataRate(ADS1114Full.DR_250)                                                 // Set data rate, (dr 0–7) → void
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
        adc.setThresholds(0.5, 3.0)                                                         // Set thresholds, (low V, high V) → void
                                                                                            // converted with the current lsb(), clamped
        adc.setLowThresholdRaw(4000)                                                        // Set low threshold, (raw) → void
                                                                                            // LO_THRESH ← raw & 0xFFFF
        adc.setHighThresholdRaw(20000)                                                      // Set high threshold, (raw) → void
                                                                                            // HI_THRESH ← raw & 0xFFFF
        val th = adc.getThresholdsRaw()                                                     // Get thresholds, () → int[] {low, high}
                                                                                            // read back, sign-extended
        println("thresholds %d %d".format(th[0], th[1]))
        adc.configureComparator(ADS1114Full.COMP_WINDOW, ADS1114Full.ACTIVE_LOW, true, ADS1114Full.QUEUE_2)// Configure comparator, (mode, polarity, latch=false, queue=QUEUE_1) → void
                                                                                            // window mode, latching, 2-sample queue
        adc.clearAlert()                                                                    // Clear alert, () → void
                                                                                            // reads CONVERSION to release the latch
        adc.disableComparator()                                                             // Disable comparator, () → void
                                                                                            // COMP_QUE ← 11
        adc.enableInterrupt(ADS1114Full.SOURCE_CONVERSION_READY)                            // Enable interrupt, (source) → void
                                                                                            // ALERT/RDY signals conversion-ready
        val status = adc.pollInterrupt()                                                    // Poll interrupt, () → int source
                                                                                            // enabled source if ALERT/RDY is asserted
        println("poll %d".format(status))
        adc.onInterrupt({ s -> println("alert $s") })                                       // Subscribe, (callback) → void
                                                                                            // pin edge, or a polling thread without an InputPin
        Thread.sleep(1000)
        adc.offInterrupt()                                                                  // Unsubscribe, () → void
                                                                                            // stops delivery
        adc.disableInterrupt(ADS1114Full.SOURCE_CONVERSION_READY)                           // Disable interrupt, (source) → void
                                                                                            // releases ALERT/RDY
        adc.reset()                                                                         // Reset, () → void
                                                                                            // writes the reset values back
    }
}
