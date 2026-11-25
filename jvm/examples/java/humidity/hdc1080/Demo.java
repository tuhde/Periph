///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-java:1.5.4

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.HDC1080Full;

public class Demo {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new HDC1080Full(connection);                                                // Create HDC1080 driver, (connection) → void

            sensor.init();                                                                      // Write default configuration, () → void
            System.out.printf("serial id 0x%010X%n", sensor.serialId());

            // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
            for (int n = 0; n < 15; n++) {
                double[] w = sensor.read();                                                                 // Read temperature and humidity, () → double[] {°C, %RH}
                double g = Math.log(Math.max(w[1], 0.01) / 100.0) + 17.62 * w[0] / (243.12 + w[0]);
                double dew = 243.12 * g / (17.62 - g);                                                  // Magnus dew point, °C
                System.out.printf("%.2f C %.1f %%RH dew point %.2f C%n", w[0], w[1], dew);
                Thread.sleep(2000);
            }

            // --- Heater test: 10 s ---
            double rh0 = sensor.readHumidity();
            double t0 = sensor.readTemperature();
            sensor.setHeater(true);                                                             // Switch the heater, (on) → void
            Thread.sleep(10000);
            double rh1 = sensor.readHumidity();
            double t1 = sensor.readTemperature();
            System.out.printf("RH drop %.1f %%RH, temperature rise %.2f K%n", rh0 - rh1, t1 - t0);
            sensor.setHeater(false);

            // --- Resolution comparison ---
            sensor.setResolution(11, 8);                                                        // Set resolution, (temperatureBits, humidityBits) → void
            long ts = System.currentTimeMillis();
            double[] r = sensor.read();
            System.out.printf("11/8 bit: %.2f C %.1f %%RH in %d ms%n", r[0], r[1], System.currentTimeMillis() - ts);
            sensor.setResolution(14, 14);
            System.out.printf("battery low %s%n", sensor.batteryLow());
            // sensor.batteryLow() → boolean, true when VDD is below 2.8 V
        }
    }
}
