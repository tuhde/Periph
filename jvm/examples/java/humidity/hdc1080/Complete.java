///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.HDC1080Full;

public class Complete {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new HDC1080Full(connection);                                                // Create HDC1080 driver, (connection) → void

            sensor.init();                                                                      // Write default configuration, () → void
            System.out.printf("serial id 0x%010X%n", sensor.serialId());                        // Read the 40-bit serial ID, () → long
            System.out.printf("config 0x%04X%n", sensor.readConfig());                          // Read the configuration register, () → int
            double[] v = sensor.read();                                                         // Read temperature and humidity, () → double[] {°C, %RH}
            System.out.printf("%.2f C, %.1f %%RH%n", v[0], v[1]);
            System.out.printf("temperature %.2f C%n", sensor.readTemperature());
            // sensor.readTemperature() → double °C, single mode
            System.out.printf("humidity %.1f %%RH%n", sensor.readHumidity());
            // sensor.readHumidity() → double %RH, single mode
            int[] raw = sensor.readRaw();                                                       // Read raw words, () → int[] {temperature, humidity}
            System.out.printf("raw T %d raw RH %d%n", raw[0], raw[1]);

            sensor.setResolution(11, 8);                                                        // Set resolution, (temperatureBits, humidityBits) → void
            System.out.printf("conversion time %.2f ms%n", sensor.conversionTime() * 1000.0);
            // sensor.conversionTime() → double s, typical T + RH time
            sensor.setResolution(14, 14);

            sensor.setHeater(true);                                                             // Switch the heater, (on) → void
            System.out.printf("heater on %s%n", sensor.isHeaterOn());
            // sensor.isHeaterOn() → boolean
            sensor.setHeater(false);
            System.out.printf("battery low %s%n", sensor.batteryLow());
            // sensor.batteryLow() → boolean, true when VDD is below 2.8 V
            sensor.softwareReset();                                                             // Software reset, () → void

            System.out.printf("manufacturer 0x%04X%n", sensor.manufacturerId());                // Manufacturer ID (0x5449), () → int
            System.out.printf("device 0x%04X%n", sensor.deviceId());                            // Device ID (0x1050), () → int
        }
    }
}
