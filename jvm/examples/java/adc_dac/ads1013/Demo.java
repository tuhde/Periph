///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-java:1.3.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.ADS1013Full;

// Battery-voltage logger: the cell is read through a 2:1 resistor divider
// (AIN0, AIN1 to GND).

public class Demo {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, ADS1013Full.DEFAULT_ADDRESS)) {   // open I²C bus, device address, (bus, address=0x48) → I2CConnection
            var adc = new ADS1013Full(connection);                                      // construct driver, (connection) → ADS1013Full
            double dividerRatio = 2.0;

            // --- Slow, low-noise single-shot logging ---
            // 128 SPS gives the lowest noise; one conversion per second keeps the chip
            // in power-down in between.
            adc.setDataRate(ADS1013Full.DR_128);                                        // Set data rate, (dr 0–6) → void
            for (int i = 0; i < 10; i++) {
                var raw = adc.readRaw();                                                // Read raw code, () → int (−2048…2047)
                var v = adc.readVoltage();                                              // Read voltage, () → double V
                var battery = v * dividerRatio;
                System.out.printf("raw %d divider %.3f V battery %.3f V%n", raw, v, battery);
                Thread.sleep(1000);
            }

            // --- Fast continuous averaging ---
            // At 3300 SPS in continuous mode, 100 samples take about 30 ms.
            adc.setDataRate(ADS1013Full.DR_3300);                                       // Set data rate, (dr 0–6) → void
            adc.startContinuous();                                                      // Start continuous conversion, () → void
            Thread.sleep(10);
            double total = 0.0;
            for (int i = 0; i < 100; i++) {
                total += adc.readContinuous();                                          // Read latest voltage, () → double V
                Thread.sleep(1);
            }
            adc.stopContinuous();                                                       // Stop continuous conversion, () → void
            System.out.printf("average battery %.3f V%n", total / 100 * dividerRatio);
        }
    }
}
