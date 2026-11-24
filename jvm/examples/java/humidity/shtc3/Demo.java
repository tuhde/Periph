///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-java:1.5.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.Shtc3Full;

public class Demo {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x70").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Shtc3Full(connection);                                                            // Create SHTC3 driver, (connection) → Shtc3Full
            sensor.init();                                                                                      // Soft reset and ID check, () → void

            // --- Identify the chip ---
            // The ID word carries the SHTC3 product code in bits 11 and 5:0.
            System.out.printf("id 0x%04X%n", sensor.readId());                                                  // Read the ID word, () → int

            // --- Battery-friendly climate logger: 6 readings, 10 s apart ---
            // The sensor sleeps (0.3 uA) between the readings; every read wakes it.
            for (int i = 0; i < 6; i++) {
                double[] m = sensor.read();                                                                     // Read temperature and humidity, () → double[] {°C, %RH}
                System.out.printf("%.2f C  %.1f %%RH%n", m[0], m[1]);
                Thread.sleep(10000);
            }

            // --- Compare normal and low-power mode ---
            // Low power measures in 0.7 ms instead of 10.8 ms at slightly higher noise.
            double[] n = sensor.read();                                                                         // Read temperature and humidity, () → double[] {°C, %RH}
            System.out.printf("normal    %.2f C %.1f %%RH%n", n[0], n[1]);
            sensor.setLowPower(true);                                                                           // Select low-power mode, (on) → void
            double[] l = sensor.read();
            System.out.printf("low power %.2f C %.1f %%RH%n", l[0], l[1]);
            sensor.setLowPower(false);

            // --- Dew point and raw words, then finish asleep ---
            System.out.printf("dew point %.2f C%n", sensor.dewPoint());                                         // Compute dew point, () → double °C
            int[] raw = sensor.readRaw();                                                                       // Read raw codes, () → int[] {raw temperature, raw humidity}
            System.out.printf("raw T %d raw RH %d%n", raw[0], raw[1]);
            sensor.sleep();                                                                                     // Sleep command, () → void
        }
    }
}
