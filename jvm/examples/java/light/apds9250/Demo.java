///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-java:1.5.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.Apds9250Minimal;
import it.uhde.periph.chips.light.Apds9250Full;

public class Demo {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, Apds9250Minimal.DEFAULT_ADDRESS)) {           // open I²C bus, device address, (bus, address=0x52) → I2CConnection
            var sensor = new Apds9250Full(connection);                                              // construct driver, (connection) → Apds9250Full

            // --- Colour sampler ---
            // Prints the part ID and settings, samples R/G/B/IR as a normalised colour,
            // compares ALS/IR mode, steps gain and resolution with normalised counts,
            // arms a variation and a threshold interrupt on the green channel (polled
            // through pollInterrupt), then disables the sensor and soft-resets it.
            System.out.println("part " + (sensor.partId()[0]) + " resolution " + (sensor.getResolution()) + " bit rate " + (sensor.getMeasurementRate()) + " ms gain " + (sensor.getGain()));// Read PART_ID and the settings, () → ints
            for (int i = 0; i < 20; i++) {
                var c = sensor.read();                                                              // Read the four channels, () → Counts
                var top = Math.max(Math.max(c.red, c.green), Math.max(c.blue, 1));
                System.out.println("#" + (String.format("%02x%02x%02x", (long) c.red * 255 / top, (long) c.green * 255 / top, (long) c.blue * 255 / top)) + " IR " + (c.ir));
                Thread.sleep(500);
            }
            sensor.setMode(false);                                                                  // Select ALS/IR mode, (colour) → void
            System.out.println("ALS/IR green " + (sensor.readGreen()) + " ir " + (sensor.readIr()));
            sensor.setMode(true);                                                                   // Back to colour mode, (colour) → void
            for (int gain : new int[]{1, 3, 6, 9, 18}) {
                sensor.setGain(gain);                                                               // Set the gain, (gain) → void
                Thread.sleep(300);
                var g = sensor.readGreen();
                System.out.println("gain " + (gain) + " green " + (g) + " normalized " + (Math.round(sensor.normalized(g))));// Counts per gain-second, (counts) → double
            }
            sensor.setGain(3);
            for (int bits : new int[]{13, 16, 17, 18, 19, 20}) {
                sensor.setResolution(bits);                                                         // Set the resolution, (bits) → void
                Thread.sleep(500);
                var g = sensor.readGreen();
                System.out.println((bits) + " bit full scale " + ((1 << bits) - 1) + " green " + (g) + " normalized " + (Math.round(sensor.normalized(g))));
            }
            sensor.setResolution(18);
            sensor.setMeasurementRate(100);                                                         // Set the rate, (ms) → void
            sensor.setInterruptChannel(Apds9250Full.CHANNEL_GREEN);                                 // Choose the channel, (channel) → void
            sensor.setPersistence(2);                                                               // Set the persistence, (count) → void
            sensor.setVariance(64);                                                                 // Set the variation threshold, (counts) → void
            sensor.enableInterrupt(Apds9250Full.SOURCE_VARIATION);                                  // Enable the variation interrupt, (source) → void
            for (int i = 0; i < 20; i++) {
                Thread.sleep(500);
                var status = sensor.pollInterrupt();                                                // Read and clear MAIN_STATUS, () → int
                if ((status & Apds9250Minimal.STATUS_INTERRUPT) != 0) {
                    System.out.println("variation interrupt, green " + (sensor.readGreen()));
                }
            }
            var base = sensor.readGreen();
            sensor.setThresholds(base - base / 4, base + base / 4);                                 // Set the window, (low, high) → void
            sensor.enableInterrupt(Apds9250Full.SOURCE_THRESHOLD);                                  // Enable the threshold interrupt, (source) → void
            Thread.sleep(5000);
            System.out.println("status " + (sensor.pollInterrupt()));
            sensor.disableInterrupt();                                                              // Disable the interrupt, () → void
            sensor.disable();                                                                       // Standby, () → void
            sensor.softReset();                                                                     // Software reset, () → void
        }
    }
}
