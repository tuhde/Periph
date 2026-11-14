///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.HTU21DFFull;

public class Complete {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new HTU21DFFull(connection);                                                // Create HTU21D-F driver, (connection) → void

            sensor.init();                                                                          // Soft reset, () → void
                                                                                                    // 15 ms power-up wait, soft reset, 15 ms
            double[] v = sensor.read();                                                             // Read temperature and humidity, () → double[] {°C, %RH}
                                                                                                    // no hold master, CRC checked, one retry on a mismatch
            System.out.printf("%.2f C, %.1f %%RH%n", v[0], v[1]);
            System.out.printf("humidity %.1f %%RH%n", sensor.readHumidity());
            // sensor.readHumidity() → double %RH, not clamped
            System.out.printf("temperature %.2f C%n", sensor.readTemperature());
            // sensor.readTemperature() → double °C
            System.out.printf("compensated humidity %.1f %%RH%n", sensor.readHumidityCompensated());
            // sensor.readHumidityCompensated() → double %RH, -0.15 %RH/°C relative to 25 °C
            int[] raw = sensor.readRaw();                                                           // Read raw codes, () → int[] {temperature, humidity}
                                                                                                    // status bits cleared
            System.out.printf("raw T %d raw RH %d%n", raw[0], raw[1]);

            sensor.setResolution(3);                                                                // Set resolution, (res) → void
                                                                                                    // 0 = 12/14 bit, 1 = 8/12, 2 = 10/13, 3 = 11/11
            System.out.printf("resolution setting %d%n", sensor.getResolution());
            // sensor.getResolution() → int
            sensor.setResolution(0);

            sensor.setHeater(true);                                                                 // Switch the heater, (on) → void
                                                                                                    // about 5.5 mW; readings drift while on
            System.out.printf("heater on %s%n", sensor.heater());
            // sensor.heater() → boolean
            sensor.setHeater(false);
            System.out.printf("battery low %s%n", sensor.batteryLow());
            // sensor.batteryLow() → boolean, true when VDD is below about 2.25 V
            int reg = sensor.readUserRegister();                                                    // Read the user register, () → int
                                                                                                    // 0x02 after reset
            System.out.printf("user register 0x%02X%n", reg);
            sensor.setHoldMaster(false);                                                            // Use hold-master commands, (on) → void
                                                                                                    // needs bus-layer clock stretching; off polls the NACKed read header
            System.out.printf("dew point %.2f C%n", sensor.dewPoint());
            // sensor.dewPoint() → double °C, one measurement
            sensor.softReset();                                                                     // Soft reset, () → void
                                                                                                    // 0xFE, waits 15 ms; the heater bit survives
        }
    }
}
