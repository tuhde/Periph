///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.Si7021Full;

public class Complete {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Si7021Full(connection);                                                // Create Si7021 driver, (connection) → void

            double[] v = sensor.read();                                                             // Read temperature and humidity, () → double[] {°C, %RH}
                                                                                                    // RH without hold master, temperature of the same conversion
            System.out.printf("%.2f C, %.1f %%RH%n", v[0], v[1]);
            System.out.printf("humidity %.1f %%RH%n", sensor.readHumidity());
            // sensor.readHumidity() → double %RH
            System.out.printf("temperature %.2f C%n", sensor.readTemperature());
            // sensor.readTemperature() → double °C, new conversion
            System.out.printf("previous %.2f C%n", sensor.readTemperaturePrevious());
            // sensor.readTemperaturePrevious() → double °C, command 0xE0, no new conversion
            System.out.printf("raw %d %d%n", sensor.readRawHumidity(), sensor.readRawTemperature());
            // sensor.readRawHumidity() / readRawTemperature() → int, 16-bit codes with status bits

            sensor.setResolution(11, 11);                                                           // Set resolution, (rhBits, temperatureBits) → void
                                                                                                    // (12, 14) / (8, 12) / (10, 13) / (11, 11)
            System.out.printf("resolution %s%n", java.util.Arrays.toString(sensor.getResolution()));
            // sensor.getResolution() → int[] {rhBits, temperatureBits}
            System.out.printf("conversion time %.4f s%n", sensor.conversionTime());
            // sensor.conversionTime() → double s, typical RH + temperature time
            sensor.setResolution(12, 14);

            sensor.setHeater(true);                                                                 // Switch the heater, (on) → void
                                                                                                    // about 3.1 mA; readings drift while on
            System.out.printf("heater on %s%n", sensor.isHeaterOn());
            // sensor.isHeaterOn() → boolean
            sensor.setHeater(false);
            System.out.printf("VDD low %s%n", sensor.vddLow());
            // sensor.vddLow() → boolean, true when VDD is below about 1.9 V
            int reg = sensor.readUserRegister();                                                    // Read USER_REG_1, () → int
                                                                                                    // 0x3A after reset
            sensor.writeUserRegister(reg);                                                          // Write USER_REG_1, (value) → void
                                                                                                    // reserved bits are preserved
            sensor.setClockStretching(false);                                                       // Use hold-master commands, (on) → void
                                                                                                    // needs bus-layer support; off polls the NACKed read header

            System.out.printf("serial number 0x%016X%n", sensor.serialNumber());
            // sensor.serialNumber() → long
            System.out.printf("device type 0x%02X%n", sensor.deviceType());
            // sensor.deviceType() → int, 0x15 = Si7021
            System.out.printf("firmware 0x%02X%n", sensor.firmwareRevision());
            // sensor.firmwareRevision() → int, 0xFF = firmware 1.0
            System.out.printf("dew point %.2f C%n", sensor.dewPoint());
            // sensor.dewPoint() → double °C, one measurement, Magnus formula
            sensor.softReset();                                                                     // Software reset, () → void
                                                                                                    // 0xFE, waits 15 ms
        }
    }
}
