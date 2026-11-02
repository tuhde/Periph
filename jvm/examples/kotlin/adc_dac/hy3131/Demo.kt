///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.adc_dac.Hy3131Full

fun main() {
    SPIConnection(0, 0, 0, 1_000_000).use { connection ->                                           // Open SPI bus 0, CS 0, Mode 0, 1 MHz, (bus, device, mode=0, maxSpeedHz=1_000_000) → SPIConnection
        val adc = Hy3131Full(connection, 1.2f)                                                      // Create HY3131 driver, (connection, vref=1.2 V) → Hy3131Full
        // --- Bench voltmeter with on-chip temperature and AC analysis ---
        // A 0–1.2 V source sits between PB2 and AGND. AD1 runs at ~40 Hz with the chopper on
        // for offset-free DC readings; AD2 + LPF + RMS + peak hold watch the same node for AC.
        adc.configureAd1(4, 2, 0, 0, 0, 0)                                                          // Configure AD1, (osr=7, chop=0, inputGain=0, refGain=0, offset=0, buffers=0) → void

        // --- One-point temperature calibration ---
        // Run the board at 25 °C (or replace with a stored constant) and remember the code.
        val tcode25 = adc.readTemperatureCode()                                                     // Read temperature code, () → int

        // --- Enable AC measurement chain on the same input ---
        adc.enableAd2(5, 1, 0, false, false, 2, false)                                              // Enable AD2, (osr=5, inputGain=1, refGain=0, chop=false, clkDiv4=false, source=0, useAd3=false) → void
        adc.enableLpf(3)                                                                            // Enable low-pass filter, (bw=0) → void
        adc.enableRms()                                                                             // Enable RMS converter, () → void
        adc.enablePeakHold(0)                                                                       // Enable peak hold, (source=0) → void

        while (true) {
            val v = adc.readVoltage()                                                               // Read AD1 voltage, () → float V
            val t = adc.readTemperature(tcode25)                                                    // Read die temperature, (tcode25) → float °C
            val rms = adc.readRms()                                                                 // Read RMS, () → float LSB
            val peak = adc.readPeak()                                                               // Read peak hold, () → int[2] {min, max} 19-bit
            println("V=%.4f V  T=%.1f C  rms=%.0f  p-p=%d".format(v, t, rms, peak[1] - peak[0]))
            Thread.sleep(1000)
        }
    }
}
