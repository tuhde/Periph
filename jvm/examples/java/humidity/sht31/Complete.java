///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.SHT31Full;

public class Complete {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x44").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new SHT31Full(connection);                                         // Create SHT31 driver, (connection) → void

            double[] v = sensor.read();                                                     // Read temperature and humidity, () → double[] {°C, %RH}
                                                                                            // single shot, high repeatability, CRC checked
            System.out.printf("%.2f C, %.1f %%RH%n", v[0], v[1]);
            System.out.printf("temperature %.2f C%n", sensor.readTemperature());
            // sensor.readTemperature() → double °C
            System.out.printf("humidity %.1f %%RH%n", sensor.readHumidity());
            // sensor.readHumidity() → double %RH
            int[] raw = sensor.readRaw();                                                   // Read raw words, () → int[] {temperature, humidity}
                                                                                            // 16-bit ticks
            System.out.printf("raw %s%n", java.util.Arrays.toString(raw));
            sensor.setRepeatability("low");                                                 // Select repeatability, (level) → void
                                                                                            // "low" / "medium" / "high" (4 / 6 / 15 ms)
            System.out.printf("low repeatability: %s%n", java.util.Arrays.toString(sensor.read()));
            sensor.setRepeatability("high");                                                // Select repeatability, (level) → void
            sensor.setClockStretching(false);                                               // Use clock-stretching commands, (on) → void
                                                                                            // needs bus-layer support; off polls the NACKed read header
            sensor.startPeriodic(2.0, "high", false);                                       // Start periodic mode, (rate, repeatability, art) → void
                                                                                            // rate 0.5 / 1 / 2 / 4 / 10 mps; art = true selects the 4 mps ART mode
            Thread.sleep(1000);
            double[] fetched = sensor.fetch();                                              // Fetch the latest periodic result, () → double[] or null
                                                                                            // null while the read header is NACKed
            System.out.printf("fetch %s%n", java.util.Arrays.toString(fetched));
            sensor.stopPeriodic();                                                          // Stop periodic mode, () → void
                                                                                            // break command 0x3093, waits 1 ms
            sensor.setHeater(true);                                                         // Switch the heater, (on) → void
                                                                                            // about 5-25 mW; readings are unusable while on
            int status = sensor.readStatus();                                               // Read status register, () → int
                                                                                            // CRC verified; bit 13 = heater on
            System.out.printf("status 0x%04X%n", status);
            sensor.setHeater(false);                                                        // Switch the heater, (on) → void
            sensor.clearStatus();                                                           // Clear status flags, () → void
                                                                                            // command 0x3041
            System.out.printf("dew point %.2f C%n", sensor.dewPoint());
            // sensor.dewPoint() → double °C, one measurement, Magnus formula
            sensor.softReset();                                                             // Soft reset, () → void
                                                                                            // 0x30A2, waits 1 ms, back to single shot
        }
    }
}
