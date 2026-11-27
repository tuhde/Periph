///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.Mpl3115a2Full;

public class Demo {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x60").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Mpl3115a2Full(connection);                                             // Create MPL3115A2 driver, (connection) → Mpl3115a2Full
            sensor.init();                                                                          // Reset and apply defaults, () → void

            // --- Weather and altitude station ---
            // Sets the local sea-level pressure, takes three barometer readings, switches to
            // altimeter mode for three altitude readings, collects 16 samples in the circular
            // FIFO and prints their minimum, maximum and mean, then arms a temperature
            // threshold on INT1 and reports the captured extremes.
            sensor.setSeaLevelPressure(101325.0);                                                   // Set the sea-level reference, (pa) → void
            for (int i = 0; i < 3; i++) {
                var m = sensor.read();                                                              // Read pressure and temperature, () → double[] {Pa, °C}
                System.out.printf("%.2f Pa  %.2f C%n", m[0], m[1]);
                Thread.sleep(5000);
            }
            for (int i = 0; i < 3; i++) {
                double alt = sensor.readAltitude();                                                 // Read altitude, () → double m
                System.out.printf("altitude %.2f m%n", alt);
            }
            sensor.setMode(false);                                                                  // Back to barometer mode, (altimeter) → void
            sensor.setOversample(16);                                                               // Faster conversions, (ratio) → void
            sensor.fifoSetup(Mpl3115a2Full.FIFO_CIRCULAR, 16);                                      // Circular FIFO, watermark 16, (mode, watermark) → void
            sensor.startContinuous(0);                                                              // One sample per second, (stepExp 0-15) → void
            while (sensor.fifoStatus()[1] == 0) {
                Thread.sleep(1000);
            }
            var samples = sensor.fifoRead();                                                        // Drain the FIFO, () → List<double[]> {value Pa, °C}
            double lo = samples.get(0)[0];
            double hi = lo;
            double sum = 0.0;
            for (int i = 0; i < samples.size(); i++) {
                double v = samples.get(i)[0];
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
                sum += v;
            }
            System.out.printf("n=%d min %.2f max %.2f mean %.2f Pa%n", samples.size(), lo, hi, sum / samples.size());
            sensor.setTarget(0.0, 0.0, (int) samples.get(samples.size() - 1)[1] + 1, 0);            // Temperature target, (value, window, temperatureC, temperatureWindow) → void
            sensor.enableInterrupt(Mpl3115a2Full.SOURCE_TTH, 1);                                    // Route temperature threshold to INT1, (source, pin) → void
            int src = sensor.pollInterrupt();                                                       // Read INT_SOURCE, () → int
            System.out.printf("INT_SOURCE 0x%02X%n", src);
            sensor.stopContinuous();                                                                // Back to STANDBY, () → void
            sensor.fifoSetup(Mpl3115a2Full.FIFO_OFF, 0);                                            // FIFO off, (mode, watermark) → void
            var mm = sensor.readMinMax();                                                           // Captured extremes, () → double[] {pMin, pMax, tMin, tMax}
            System.out.printf("min %.2f Pa max %.2f Pa%n", mm[0], mm[1]);
        }
    }
}
