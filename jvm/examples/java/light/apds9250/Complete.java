///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.Apds9250Minimal;
import it.uhde.periph.chips.light.Apds9250Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, Apds9250Minimal.DEFAULT_ADDRESS)) {           // open I²C bus, device address, (bus, address=0x52) → I2CConnection
            var sensor = new Apds9250Full(connection);                                              // construct driver, (connection) → Apds9250Full
            System.out.println("part " + (sensor.partId()[0]) + " revision " + (sensor.partId()[1]));// Read PART_ID, () → (part, revision) = (0xB, 0x5)
            var c = sensor.read();                                                                  // Read all channels, () → Counts
            System.out.println((c.red) + " " + (c.green) + " " + (c.blue) + " " + (c.ir));
            System.out.println("red " + (sensor.readRed()));                                        // Read the red channel, () → int
            System.out.println("green " + (sensor.readGreen()));                                    // Read the green / ALS channel, () → int
            System.out.println("blue " + (sensor.readBlue()));                                      // Read the blue channel, () → int
            System.out.println("ir " + (sensor.readIr()));                                          // Read the IR channel, () → int
            System.out.println("ready " + (sensor.dataReady()));                                    // Query LS_DATA, () → boolean (clears the status flags on the chip)
            var block = sensor.readChannels();                                                      // Block-read without waiting, () → Counts

            sensor.setMode(false);                                                                  // Select ALS/IR mode, (colour) → void; false: only green and IR
            sensor.setMode(true);                                                                   // Back to colour mode, (colour) → void
            sensor.disable();                                                                       // Standby, () → void
            sensor.enable();                                                                        // Activate the sensor, () → void

            sensor.setResolution(16);                                                               // Set the ADC resolution, (bits 13/16/17/18/19/20) → void
            sensor.setMeasurementRate(200);                                                         // Set the measurement rate, (ms 25…2000) → void
            sensor.setGain(6);                                                                      // Set the gain, (1/3/6/9/18) → void; restarts the measurement
            System.out.println("resolution " + (sensor.getResolution()) + " bit");                  // Read the resolution, () → int
            System.out.println("rate " + (sensor.getMeasurementRate()) + " ms");                    // Read the rate, () → int
            System.out.println("gain " + (sensor.getGain()));                                       // Read the gain, () → int
            System.out.println("integration " + (sensor.integrationTime()) + " s");                 // Integration time, () → double seconds
            System.out.println("normalized " + (sensor.normalized(sensor.readGreen())));            // Counts per gain-second, (counts) → double

            sensor.setThresholds(1000, 100000);                                                     // Set the threshold window, (low, high) → void; 20-bit counts
            System.out.println("window " + (sensor.getThresholds()[0]) + " - " + (sensor.getThresholds()[1]));// Read the window, () → (low, high)
            sensor.setInterruptChannel(Apds9250Full.CHANNEL_GREEN);                                 // Choose the monitored channel, (channel) → void
            sensor.setPersistence(2);                                                               // Set the persistence, (1–16) → void
            sensor.setVariance(64);                                                                 // Set the variation threshold, (8…1024, power of two) → void
            sensor.enableInterrupt(Apds9250Full.SOURCE_VARIATION);                                  // Enable the interrupt, (source) → void
            sensor.onInterrupt(s -> System.out.println("interrupt " + s));                          // Subscribe, (callback(status), intPin=null) → void; falling edge or 50 ms poll
            Thread.sleep(10000);
            System.out.println("poll " + (sensor.pollInterrupt()));                                 // Read and clear MAIN_STATUS, () → int; 0x20 power-on, 0x10 interrupt, 0x08 data
            sensor.offInterrupt();                                                                  // Unsubscribe, () → void
            sensor.disableInterrupt();                                                              // Disable the interrupt, () → void
            System.out.println("status " + (sensor.status()));                                      // Raw MAIN_STATUS, () → int
            sensor.softReset();                                                                     // Software reset, () → void; all registers to defaults, sensor in standby
        }
    }
}
