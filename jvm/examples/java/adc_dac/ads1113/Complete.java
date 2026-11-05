///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.2
//DEPS it.uhde:periph-java:1.3.2

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.ADS1113Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, ADS1113Full.DEFAULT_ADDRESS)) {    // open I²C bus, device address, (bus, address=0x48) → I2CConnection
            var adc = new ADS1113Full(connection);                                          // construct driver, (connection) → ADS1113Full
                                                                                            // no bus traffic; Config word is rebuilt per conversion
            var raw = adc.readRaw();                                                        // Read raw code, () → int
                                                                                            // single-shot conversion, signed 16-bit
            var v = adc.readVoltage();                                                      // Read voltage, () → double V
                                                                                            // raw × lsb()
            System.out.printf("raw %d, %.5f V%n", raw, v);
            var fs = adc.fullScale();                                                       // Full scale, () → double V
                                                                                            // fixed ±2.048 V
            var lsb = adc.lsb();                                                            // LSB, () → double V
                                                                                            // fullScale() / 32768
            System.out.printf("full scale %.3f V, lsb %.7f V%n", fs, lsb);
            adc.setDataRate(ADS1113Full.DR_250);                                            // Set data rate, (dr 0–7) → void
                                                                                            // DR field of the Config word
            var sps = adc.dataRateSps();                                                    // Data rate, () → int SPS
                                                                                            // samples per second of the current setting
            System.out.printf("rate %d SPS%n", sps);
            adc.startContinuous();                                                          // Start continuous, () → void
                                                                                            // MODE ← 0
            Thread.sleep(50);
            var craw = adc.readContinuousRaw();                                             // Read continuous raw, () → int
                                                                                            // CONVERSION without touching Config
            var cv = adc.readContinuous();                                                  // Read continuous, () → double V
                                                                                            // readContinuousRaw() × lsb()
            System.out.printf("continuous %d, %.5f V%n", craw, cv);
            adc.stopContinuous();                                                           // Stop continuous, () → void
                                                                                            // MODE ← 1
            var busy = adc.isConverting();                                                  // Check conversion, () → boolean
                                                                                            // CONFIG.OS == 0
            var cfg = adc.readConfig();                                                     // Read CONFIG, () → int
                                                                                            // raw 16-bit register
            System.out.printf("converting %b, config 0x%04X%n", busy, cfg);
            adc.reset();                                                                    // Reset, () → void
                                                                                            // writes the reset values back
        }
    }
}
