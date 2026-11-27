///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.HS40xxFull;

public class Complete {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x54").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new HS40xxFull(connection);                                                // Create HS40xx driver, (connection) → void

            double[] v = sensor.read();                                                             // Read temperature and humidity, () → double[] {°C, %RH}
                                                                                                    // no-hold 0xF5, 5 bytes, CRC verified; one retry on mismatch
            System.out.printf("%.2f C, %.1f %%RH%n", v[0], v[1]);
            double t = sensor.readTemperature();                                                    // Read temperature only, () → double °C
                                                                                                    // no-hold 0xF3, 3 bytes
            double rh = sensor.readHumidity();                                                      // Read humidity only, () → double %RH
            double[] hold = sensor.readHold();                                                      // Read with clock stretching, () → double[] {°C, %RH}
                                                                                                    // 0xE5; needs a stretching controller and SCL >= 200 kHz
            int[] raw = sensor.readRaw();                                                           // Read raw values, () → int[] {humidity, temperature}
                                                                                                    // 14-bit values, CRC verified
            System.out.printf("raw RH %d T %d%n", raw[0], raw[1]);
            sensor.setResolution(12, 12);                                                           // Set resolution, (humidityBits, temperatureBits) → void
                                                                                                    // 8, 10, 12 or 14 bit; null leaves a channel unchanged
            int[] res = sensor.getResolution();                                                     // Get resolution, () → int[] {humidity bits, temperature bits}
            System.out.printf("resolution %d/%d bit%n", res[0], res[1]);
            int reg = sensor.readRegister(0x00);                                                    // Read a register, (reg) → int
            sensor.writeRegister(0x00, 0x0F);                                                       // Write a register, (reg, value) → void
                                                                                                    // 0x0F = 14 bit for both channels (default)
            sensor.setAlertThresholds(60.0, 55.0, null, null, 40.0, 38.0, null, null);              // Set alert thresholds, (rhHigh, rhHighReset, rhLow, rhLowReset, tempHigh, tempHighReset, tempLow, tempLowReset) → void
                                                                                                    // %RH / °C; null leaves a threshold unchanged
            sensor.setAlertPolarity(true);                                                          // Set ALERT polarity, (activeHigh) → void
            sensor.enableInterrupt(HS40xxFull.SOURCE_RH_HIGH | HS40xxFull.SOURCE_TEMP_HIGH);        // Enable alert sources, (source) → void
            sensor.startPeriodic(1.0);                                                              // Start periodic mode, (periodS) → void
                                                                                                    // 0.5, 1 or 2.5 s
            Thread.sleep(1500);
            double[] p = sensor.readPeriodic();                                                     // Fetch the latest periodic result, () → double[] {°C, %RH, status}
            System.out.printf("periodic %.2f C %.1f %%RH status %d%n", p[0], p[1], (int) p[2]);
            int status = sensor.pollInterrupt();                                                    // Read alert status, () → int
                                                                                                    // SOURCE_* bitmask
            sensor.onInterrupt(s -> System.out.println("alert " + s));                              // Subscribe to the ALERT pin, (callback) → void
                                                                                                    // polls at the periodic interval without a pin
            Thread.sleep(5000);
            sensor.offInterrupt();                                                                  // Unsubscribe, () → void
            sensor.disableInterrupt(HS40xxFull.SOURCE_RH_HIGH | HS40xxFull.SOURCE_TEMP_HIGH);       // Disable alert sources, (source) → void
            sensor.stopPeriodic();                                                                  // Stop periodic mode, () → void
            long id = sensor.readId();                                                              // Read the 32-bit sensor ID, () → long
            System.out.printf("id 0x%08X%n", id);
            double dew = sensor.dewPoint();                                                         // Compute dew point, () → double °C
                                                                                                    // one measurement, Magnus formula
            System.out.printf("dew point %.2f C%n", dew);
            sensor.softReset();                                                                     // Soft reset, () → void
                                                                                                    // stops periodic mode, waits 500 ms
        }
    }
}
