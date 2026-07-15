///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.gas.Ens160Full;

/**
 * Indoor air quality monitor: 60-second run with human-readable AQI labels.
 *
 * The demo waits for sensor warm-up (VALIDITY_FLAG=0), then reads AQI, TVOC,
 * and eCO2 every second. AQI 1-2 is acceptable for occupied spaces; AQI 3+
 * suggests ventilation.
 */
public class Demo {

    private static final String[] AQI_LABELS = {"", "Excellent", "Good", "Moderate", "Poor", "Unhealthy"};

    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x52)) {       // open I²C bus 1, device 0x52 (ADDR low), (bus, address=0x52) → I2CTransport
            var sensor = new Ens160Full(transport);                     // construct driver, verifies PART_ID, starts STANDARD mode, (transport) → Ens160Full

            // --- Wait for sensor warm-up ---
            // The ENS160 requires ~3 minutes after power-on or idle before VALIDITY_FLAG
            // reaches 0. During warm-up, readings are unreliable. The driver surfaces the
            // status so the application can display progress to the user.
            System.out.println("Waiting for sensor warm-up...");
            while (true) {                                              // Wait for valid data, () → blocks until warm
                try { sensor.readAirQuality(); break; } catch (Exception e) {
                    int s = sensor.status();
                    if (s == 1) System.out.println("Warm-up in progress...");
                    else if (s == 2) System.out.println("Initial start-up (first power-on, up to 1 hour)...");
                    else System.out.println("No valid output");
                    Thread.sleep(1000);
                }
            }
            System.out.println("Sensor ready!");

            // --- Set compensation from external sensor ---
            // If an external temperature/humidity sensor is available, feeding its readings
            // to the ENS160 improves accuracy outside the 20-80%RH range. Here we use a
            // fixed 22C/45%RH as an example.
            sensor.setCompensation(22.0, 45.0);                         // set compensation, (tempCelsius=22.0, rhPercent=45.0) → void

            // --- Indoor air quality monitoring loop ---
            // Reads AQI, TVOC, and eCO2 every second and prints a human-readable label.
            // AQI 1-2 is acceptable for occupied spaces; AQI 3+ suggests ventilation.
            for (int n = 0; n < 60; n++) {
                double[] data = sensor.readAirQuality();                // read air quality, () → double[] {aqi, tvocPpb, eco2Ppm}
                int aqi = (int) data[0];
                String label = (aqi >= 1 && aqi <= 5) ? AQI_LABELS[aqi] : "Unknown";
                System.out.printf("%ds: AQI=%d (%s) TVOC=%.0f ppb eCO2=%.0f ppm%n",
                    n, aqi, label, data[1], data[2]);
                Thread.sleep(1000);
            }
        }
    }
}
