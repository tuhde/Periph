///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.Si7021Full;

public class Demo {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Si7021Full(connection);                                                // Create Si7021 driver, (connection) → void

            sensor.softReset();                                                                     // Software reset, () → void
            System.out.printf("device type 0x%02X%n", sensor.deviceType());
            // sensor.deviceType() → int
            System.out.printf("serial number 0x%016X%n", sensor.serialNumber());
            // sensor.serialNumber() → long
            System.out.printf("firmware 0x%02X%n", sensor.firmwareRevision());
            // sensor.firmwareRevision() → int
            System.out.printf("VDD low %s%n", sensor.vddLow());
            // sensor.vddLow() → boolean

            // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
            for (int i = 0; i < 15; i++) {
                double[] v = sensor.read();                                                         // Read temperature and humidity, () → double[] {°C, %RH}
                System.out.printf("%.2f C %.1f %%RH dew point %.2f C%n", v[0], v[1], sensor.dewPoint());
                // sensor.dewPoint() → double °C
                Thread.sleep(2000);
            }

            // --- Value accessors and raw codes ---
            System.out.printf("RH %.1f T %.2f T of last RH %.2f%n", sensor.readHumidity(), sensor.readTemperature(), sensor.readTemperaturePrevious());
            System.out.printf("raw %d %d%n", sensor.readRawHumidity(), sensor.readRawTemperature());
            sensor.setClockStretching(false);                                                       // Use hold-master commands, (on) → void
            int reg = sensor.readUserRegister();                                                    // Read USER_REG_1, () → int
            sensor.writeUserRegister(reg);                                                          // Write USER_REG_1, (value) → void

            // --- Resolution comparison ---
            for (int[] p : new int[][]{{12, 14}, {10, 13}, {11, 11}, {8, 12}}) {
                sensor.setResolution(p[0], p[1]);                                                   // Set resolution, (rhBits, temperatureBits) → void
                long t0 = System.nanoTime();
                double[] v = sensor.read();
                double ms = (System.nanoTime() - t0) / 1e6;
                System.out.printf("%d/%d bit: %.2f C %.1f %%RH, %.1f ms (typ. %.1f ms), resolution %s%n", p[0], p[1], v[0], v[1], ms, sensor.conversionTime() * 1000, java.util.Arrays.toString(sensor.getResolution()));
                // sensor.conversionTime() → double s ; sensor.getResolution() → int[]
            }
            sensor.setResolution(12, 14);

            // --- Heater test: 10 s ---
            double rhBefore = sensor.readHumidity();
            double tBefore = sensor.readTemperature();
            sensor.setHeater(true);                                                                 // Switch the heater, (on) → void
            Thread.sleep(10000);
            System.out.printf("heater on %s%n", sensor.isHeaterOn());
            // sensor.isHeaterOn() → boolean
            System.out.printf("RH drop %.1f %%RH, temperature rise %.2f K%n", rhBefore - sensor.readHumidity(), sensor.readTemperature() - tBefore);
            sensor.setHeater(false);
        }
    }
}
