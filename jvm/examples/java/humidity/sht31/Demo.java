///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.SHT31Full;

public class Demo {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x44").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new SHT31Full(connection);                                         // Create SHT31 driver, (connection) → void

            sensor.softReset();                                                             // Soft reset, () → void
            int status = sensor.readStatus();                                               // Read status register, () → int
            System.out.printf("status 0x%04X%n", status);
            // --- Single shots at all three repeatability levels ---
            for (String level : new String[]{"low", "medium", "high"}) {
                sensor.setRepeatability(level);                                                 // Select repeatability, (level) → void
                long t0 = System.nanoTime();
                double[] v = sensor.read();                                                     // Read temperature and humidity, () → double[]
                System.out.printf("%-6s %.2f C %.1f %%RH (%.1f ms)%n", level, v[0], v[1], (System.nanoTime() - t0) / 1e6);
            }
            sensor.setClockStretching(false);                                               // Use clock-stretching commands, (on) → void
            System.out.printf("raw %s T %.2f RH %.1f%n", java.util.Arrays.toString(sensor.readRaw()), sensor.readTemperature(), sensor.readHumidity());
            // --- Periodic mode at 2 mps: ten samples ---
            sensor.startPeriodic(2.0, "high", false);                                       // Start periodic mode, (rate, repeatability, art) → void
            for (int got = 0; got < 10; ) {
                double[] res = sensor.fetch();                                                  // Fetch the latest periodic result, () → double[] or null
                if (res == null) { Thread.sleep(50); continue; }
                got++;
                System.out.printf("periodic %d: %.2f C %.1f %%RH%n", got, res[0], res[1]);
            }
            sensor.stopPeriodic();                                                          // Stop periodic mode, () → void
            // --- Heater test: 10 s ---
            double before = sensor.readTemperature();                                       // Read temperature only, () → double °C
            sensor.setHeater(true);                                                         // Switch the heater, (on) → void
            Thread.sleep(10000);
            System.out.printf("heater status bit: %s%n", (sensor.readStatus() & 0x2000) != 0);
            System.out.printf("temperature rise: %.2f K%n", sensor.readTemperature() - before);
            sensor.setHeater(false);                                                        // Switch the heater, (on) → void
            sensor.clearStatus();                                                           // Clear status flags, () → void
            System.out.printf("status 0x%04X, dew point %.2f C%n", sensor.readStatus(), sensor.dewPoint());
        }
    }
}
