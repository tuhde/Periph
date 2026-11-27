///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.color.Isl29125Minimal;
import it.uhde.periph.chips.color.Isl29125Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, Isl29125Minimal.DEFAULT_ADDRESS)) {           // open I²C bus, device address, (bus, address=0x44) → I2CConnection
            var sensor = new Isl29125Full(connection);                                              // construct driver, (connection) → Isl29125Full
            System.out.println("device id " + (sensor.deviceId()));                                 // Read DEVICE_ID, () → int; 0x7D for the ISL29125
            var c = sensor.read();                                                                  // Read the three channels, () → Counts
            System.out.println((c.red) + " " + (c.green) + " " + (c.blue));
            System.out.println("red " + (sensor.readRed()));                                        // Read the red channel, () → int
            System.out.println("green " + (sensor.readGreen()));                                    // Read the green channel, () → int
            System.out.println("blue " + (sensor.readBlue()));                                      // Read the blue channel, () → int
            var block = sensor.readRgb();                                                           // Block-read without waiting, () → Counts
            System.out.println("ready " + (sensor.dataReady()));                                    // Query CONVENF, () → boolean (clears the status flags on the chip)

            sensor.setMode(Isl29125Full.MODE_GREEN);                                                // Select the channels, (mode 0-7) → void; MODE_POWER_DOWN … MODE_GREEN_BLUE
            sensor.setMode(Isl29125Full.MODE_RGB);                                                  // Back to all three channels, (mode) → void
            sensor.setRange(375);                                                                   // Set the full-scale range, (lux 375 or 10000) → void
            sensor.setRange(10000);
            sensor.setResolution(12);                                                               // Set the ADC resolution, (bits 12 or 16) → void
            sensor.setResolution(16);
            sensor.setSync(false);                                                                  // Select the conversion start, (external) → void; true: rising edge of INT starts the conversion
            sensor.setIrCompensation(true, 0x3F);                                                   // Set the IR compensation, (offset, adjust 0-63) → void; offset selects codes 106-169

            sensor.setThresholds(1000, 50000);                                                      // Set the threshold window, (low, high) → void; 16-bit counts
            System.out.println("window " + (sensor.getThresholds()[0]) + " - " + (sensor.getThresholds()[1]));// Read the window, () → (low, high)
            sensor.setInterruptChannel(Isl29125Full.CHANNEL_GREEN);                                 // Choose the monitored channel, (channel) → void; CHANNEL_NONE, _GREEN, _RED or _BLUE
            sensor.setPersistence(2);                                                               // Set the persistence, (1, 2, 4 or 8 cycles) → void
            sensor.enableInterrupt(Isl29125Full.SOURCE_THRESHOLD);                                  // Enable the interrupt, (source) → void; SOURCE_THRESHOLD or SOURCE_CONVERSION
            sensor.onInterrupt(s -> System.out.println("interrupt " + s));                          // Subscribe, (callback(status), intPin=null) → void; falling edge or 50 ms poll
            Thread.sleep(5000);
            sensor.offInterrupt();                                                                  // Unsubscribe, () → void
            System.out.println("poll " + (sensor.pollInterrupt()));                                 // Read and clear STATUS, () → int; 0x04 brown-out, 0x02 conversion, 0x01 threshold
            sensor.disableInterrupt(Isl29125Full.SOURCE_THRESHOLD);                                 // Disable the interrupt, (source) → void
            System.out.println("status " + (sensor.status()));                                      // Raw STATUS, () → int
            sensor.setMode(Isl29125Full.MODE_POWER_DOWN);
            sensor.softReset();                                                                     // Software reset, () → void; all registers to defaults
        }
    }
}
