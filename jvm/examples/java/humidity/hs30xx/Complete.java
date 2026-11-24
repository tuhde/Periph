///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-java:1.5.4

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.HS30xxFull;

public class Complete {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x44").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new HS30xxFull(connection);                                                // Create HS30xx driver, (connection) → void
            // Replace the empty body with code that cuts and restores the sensor supply (MOSFET or GPIO).
            Runnable powerCycle = () -> { };

            double[] v = sensor.read();                                                             // Read temperature and humidity, () → double[] {°C, %RH}
                                                                                                    // request, 40 ms wait, 4-byte fetch; one retry on stale data
            System.out.printf("%.2f C, %.1f %%RH%n", v[0], v[1]);
            double t = sensor.readTemperature();                                                    // Read temperature only, () → double °C
            double rh = sensor.readHumidity();                                                      // Read humidity only, () → double %RH
                                                                                                    // 2-byte fetch
            int[] raw = sensor.readRaw();                                                           // Read raw values, () → int[] {humidity, temperature, status}
                                                                                                    // 14-bit values; status 0 = valid, 1 = stale
            System.out.printf("raw RH %d T %d status %d%n", raw[0], raw[1], raw[2]);
            sensor.setMeasurementWait(60);                                                          // Override the post-request wait, (ms) → void
                                                                                                    // default 40 ms; lower resolutions need less
            double dew = sensor.dewPoint();                                                         // Compute dew point, () → double °C
                                                                                                    // one measurement, Magnus formula
            System.out.printf("dew point %.2f C%n", dew);

            // NVM access only works within 10 ms after power-up, so it needs the powerCycle helper above.
            int[] res = sensor.getResolution(powerCycle);                                           // Get NVM resolution, (powerCycle) → int[] {humidity bits, temperature bits}
            System.out.printf("resolution %d/%d bit%n", res[0], res[1]);
            sensor.setResolution(12, 12, powerCycle);                                               // Set NVM resolution, (humidityBits, temperatureBits, powerCycle) → void
                                                                                                    // 8, 10, 12 or 14 bit; null leaves a channel unchanged
            long id = sensor.readId(powerCycle);                                                    // Read the 32-bit sensor ID, (powerCycle) → long
            System.out.printf("id 0x%08X%n", id);
            sensor.enterProgrammingMode();                                                          // Enter programming mode, () → void
                                                                                                    // only within 10 ms after power-up
            int reg = sensor.readNvm(0x06);                                                         // Read an NVM register, (reg) → int
                                                                                                    // 0x06 humidity / 0x11 temperature resolution, 0x1E / 0x1F ID
            System.out.printf("NVM 0x06 = 0x%04X%n", reg);
            sensor.exitProgrammingMode();                                                           // Leave programming mode, () → void
        }
    }
}
