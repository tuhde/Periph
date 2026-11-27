///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.color.As7262Full;

public class Complete {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x49").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new As7262Full(connection);                                                // Create AS7262 driver, (connection) → void
            sensor.init();                                                                          // Reset and configure defaults, () → void

            var ch = sensor.read();                                                                 // Read calibrated channels, () → Channels
                                                                                                    // one one-shot conversion
            System.out.printf("V %.2f B %.2f G %.2f Y %.2f O %.2f R %.2f%n", ch.violet, ch.blue, ch.green, ch.yellow, ch.orange, ch.red);
            var raw = sensor.readRaw();                                                             // Read raw counts, () → RawChannels
                                                                                                    // 16-bit counts
            System.out.printf("raw V %d R %d%n", raw.violet, raw.red);
            var t = sensor.temperature();                                                           // Read device temperature, () → double °C
            System.out.printf("%.0f C%n", t);
            sensor.measure();                                                                       // Start a one-shot conversion, () → void
                                                                                                    // does not wait
            while (!sensor.dataReady()) Thread.sleep(50);                                           // Check data ready, () → boolean
            var last = sensor.readCalibratedLast();                                                 // Read last calibrated results, () → Channels
            var lastRaw = sensor.readRawLast();                                                     // Read last raw counts, () → RawChannels
            var c5 = sensor.readChannel(5);                                                         // Read one channel, (index) → float
                                                                                                    // 0..5 = V, B, G, Y, O, R
            var c5raw = sensor.readChannelRaw(5);                                                   // Read one raw channel, (index) → int
            System.out.printf("channel 5 %.2f raw %d%n", c5, c5raw);
            sensor.setGain(16.0);                                                                   // Set gain, (gain) → void
                                                                                                    // 0-3 index or 1, 3.7, 16, 64
            System.out.printf("gain %.1f%n", sensor.getGain());
            sensor.setIntegrationTime(165.2);                                                       // Set integration time, (ms) → void
                                                                                                    // 2.8 - 714 ms
            System.out.printf("integration %.1f ms%n", sensor.getIntegrationTime());
            sensor.setMode(3);                                                                      // Set bank mode, (mode) → void
                                                                                                    // 0, 1, 2 continuous; 3 one-shot
            System.out.printf("mode %d%n", sensor.getMode());
            sensor.setContinuous(2);                                                                // Continuous conversions, (mode) → void
            sensor.setMode(3);                                                                      // Back to one-shot, (mode) → void
            sensor.setInterrupt(false);                                                             // Enable/disable INT pin, (enabled) → void
            sensor.setLed(true, 25.0);                                                              // Driver LED, (on, currentMa) → void
                                                                                                    // 12.5, 25, 50 or 100 mA
            sensor.setLed(false, 25.0);                                                             // Driver LED off, (on, currentMa) → void
            sensor.setIndicatorLed(true, 4.0);                                                      // Indicator LED, (on, currentMa) → void
                                                                                                    // 1, 2, 4 or 8 mA
            sensor.setIndicatorLed(false, 4.0);                                                     // Indicator LED off, (on, currentMa) → void
            var hw = sensor.hardwareVersion();                                                      // Hardware version, () → int[] {type, version}
            var fw = sensor.firmwareVersion();                                                      // Firmware version, () → int[] {major, minor, sub}
            System.out.printf("type 0x%X hw 0x%X fw %d.%d.%d%n", sensor.deviceType(), hw[1], fw[0], fw[1], fw[2]);
            var reg = sensor.readVirtual(0x05);                                                     // Read a virtual register, (reg) → int
            sensor.writeVirtual(0x05, 0x3B);                                                        // Write a virtual register, (reg, value) → void
            System.out.printf("INT_T %d%n", reg);
            sensor.setContinuous(2);                                                                // Continuous conversions, (mode) → void
            sensor.onInterrupt(status -> System.out.println("data ready"));                         // Subscribe to INT, (callback) → void
                                                                                                    // falls back to polling without an INT pin
            Thread.sleep(3000);
            System.out.printf("data ready %d%n", sensor.pollInterrupt());
            sensor.offInterrupt();                                                                  // Unsubscribe, () → void
            sensor.softReset();                                                                     // Soft reset, () → void
                                                                                                    // restores defaults
        }
    }
}
