///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.0
//DEPS it.uhde:periph-java:1.5.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.HS30xxFull;

public class Demo {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x44").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new HS30xxFull(connection);                                                // Create HS30xx driver, (connection) → void
            // Replace the empty body with code that cuts and restores the sensor supply (MOSFET or GPIO).
            Runnable powerCycle = () -> { };

            // --- Optional: show and change the stored resolution (set showResolution to true) ---
            // The NVM is only reachable within 10 ms after power-up, so this needs powerCycle.
            boolean showResolution = false;
            if (showResolution) {
                int[] res = sensor.getResolution(powerCycle);                                       // Get NVM resolution, (powerCycle) → int[] {humidity bits, temperature bits}
                System.out.printf("resolution %d/%d bit%n", res[0], res[1]);
                sensor.setResolution(12, 12, powerCycle);                                           // Set NVM resolution, (humidityBits, temperatureBits, powerCycle) → void
                sensor.setMeasurementWait(15);                                                      // 12-bit RH + T need about 10 ms, (ms) → void
            }

            // --- Hygrometer: one reading every 2 s for 30 s ---
            // Raw values and status come from the 14-bit data-fetch frame; status 0 means fresh data.
            for (int n = 0; n < 15; n++) {
                int[] raw = sensor.readRaw();                                                       // Read raw values, () → int[] {humidity, temperature, status}
                double[] v = sensor.read();                                                         // Read temperature and humidity, () → double[] {°C, %RH}
                double dew = sensor.dewPoint();                                                     // Dew point, () → double °C
                System.out.printf("%d s: RH raw %d T raw %d status %d | %.2f C %.1f %%RH dew %.2f C%n", n * 2, raw[0], raw[1], raw[2], v[0], v[1], dew);
                Thread.sleep(2000);
            }
        }
    }
}
