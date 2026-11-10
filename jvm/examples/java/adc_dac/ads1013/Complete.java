///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.ADS1013Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, ADS1013Full.DEFAULT_ADDRESS)) {   // open I²C bus, device address, (bus, address=0x48) → I2CConnection
            var adc = new ADS1013Full(connection);                                      // construct driver, (connection) → ADS1013Full
                                                                                        // no bus traffic; fixed ±2.048 V, 1600 SPS

            var raw = adc.readRaw();                                                    // Read raw code, () → int (−2048…2047)
                                                                                        // single-shot AIN0 − AIN1, polls OS until idle
            var v = adc.readVoltage();                                                  // Read voltage, () → double V
                                                                                        // raw × 0.001
            System.out.printf("raw %d voltage %.4f V%n", raw, v);
            System.out.printf("full scale %.3f V%n", adc.fullScale());                  // Read full-scale range, () → double V
                                                                                        // always 2.048
            System.out.printf("lsb %.6f V%n", adc.lsb());                               // Read code size, () → double V
                                                                                        // always 0.001

            adc.setDataRate(ADS1013Full.DR_250);                                        // Set data rate, (dr 0–6) → void
                                                                                        // DR_250 = 250 SPS
            System.out.printf("dr code %d%n", adc.getDataRate());                       // Read data-rate code, () → int
                                                                                        // cached DR[2:0]
            System.out.printf("sps %d%n", adc.dataRateSps());                           // Read data rate, () → int SPS
                                                                                        // table lookup of the DR code

            adc.startContinuous();                                                      // Start continuous conversion, () → void
                                                                                        // MODE = 0 at the current data rate
            Thread.sleep(20);
            System.out.printf("continuous raw %d%n", adc.readContinuousRaw());          // Read latest code, () → int
                                                                                        // reads CONVERSION without touching Config
            System.out.printf("continuous %.4f V%n", adc.readContinuous());             // Read latest voltage, () → double V
                                                                                        // raw × lsb()
            adc.stopContinuous();                                                       // Stop continuous conversion, () → void
                                                                                        // MODE = 1, back to single-shot
            System.out.printf("converting %b%n", adc.isConverting());                   // Check conversion in progress, () → boolean
                                                                                        // CONFIG.OS = 0 (single-shot only)
            System.out.printf("config 0x%04X%n", adc.readConfig());                     // Read CONFIG register, () → int
                                                                                        // raw 16-bit register
            adc.reset();                                                                // Reset to power-up defaults, () → void
                                                                                        // rewrites CONFIG = 0x8583
        }
    }
}
