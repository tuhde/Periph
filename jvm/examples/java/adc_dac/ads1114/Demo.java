///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.2
//DEPS it.uhde:periph-java:1.3.2

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.ADS1114Full;

public class Demo {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, ADS1114Full.DEFAULT_ADDRESS)) {    // open I²C bus, device address, (bus, address=0x48) → I2CConnection
            var adc = new ADS1114Full(connection);                                          // construct driver, (connection) → ADS1114Full

            // --- Configure the converter ---
            // ±4.096 V covers a 3.3 V rail with headroom; 250 SPS keeps single-shot
            // reads short while still averaging out most noise.
            adc.setGain(ADS1114Full.PGA_4_096);                                             // Set gain, (pga 0–5) → void
            adc.setDataRate(ADS1114Full.DR_250);                                            // Set data rate, (dr 0–7) → void

            // --- Read the inputs ---
            // Single-shot mode powers the chip down between conversions.
            for (int i = 0; i < 3; i++) {
                var raw = adc.readRaw();                                                    // Read raw code, () → int
                System.out.printf("raw %d, %.5f V%n", raw, raw * adc.lsb());
                Thread.sleep(1000);
            }

            // --- Arm a window comparator ---
            // ALERT/RDY asserts when the input leaves 0.5 V ... 3.0 V for two
            // consecutive conversions; the latch holds it until clearAlert().
            adc.setThresholds(0.5, 3.0);                                                    // Set thresholds, (low V, high V) → void
            adc.configureComparator(ADS1114Full.COMP_WINDOW, ADS1114Full.ACTIVE_LOW, true, ADS1114Full.QUEUE_2);// Configure comparator, (mode, polarity, latch=false, queue=QUEUE_1) → void
            adc.startContinuous();                                                          // Start continuous, () → void

            // --- Report out-of-range events ---
            // With an InputPin on ALERT/RDY the callback fires on the edge; without
            // one the driver polls the register every 5 ms.
            adc.onInterrupt(s -> System.out.println("OUT OF RANGE " + s));                  // Subscribe, (callback) → void
            Thread.sleep(10000);
            adc.offInterrupt();                                                             // Unsubscribe, () → void
            adc.disableComparator();                                                        // Disable comparator, () → void
            adc.stopContinuous();                                                           // Stop continuous, () → void
        }
    }
}
