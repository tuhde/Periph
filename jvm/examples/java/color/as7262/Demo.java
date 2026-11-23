///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-java:1.5.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.color.As7262Full;

public class Demo {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x49").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new As7262Full(connection);                                                // Create AS7262 driver, (connection) → void
            sensor.init();                                                                          // Reset and configure defaults, () → void

            // --- Spectrometer ---
            // Versions, gain sweep, integration time comparison, LED reflectance difference, continuous mode.
            var hw = sensor.hardwareVersion();                                                      // Hardware version, () → int[] {type, version}
            var fw = sensor.firmwareVersion();                                                      // Firmware version, () → int[] {major, minor, sub}
            System.out.printf("type 0x%X hw 0x%X fw %d.%d.%d%n", sensor.deviceType(), hw[1], fw[0], fw[1], fw[2]);
            System.out.printf("chip %.0f C%n", sensor.temperature());
            for (double gain : new double[]{1.0, 3.7, 16.0, 64.0}) {
                sensor.setGain(gain);                                                               // Set gain, (gain) → void
                var cal = sensor.read();                                                            // Read calibrated, () → Channels
                var raw = sensor.readRaw();                                                         // Read raw, () → RawChannels
                System.out.printf("gain %.1f V %.2f raw V %d%n", sensor.getGain(), cal.violet, raw.violet);
            }
            sensor.setGain(16.0);                                                                   // Set gain, (gain) → void
            for (double ms : new double[]{50.0, 500.0}) {
                sensor.setIntegrationTime(ms);                                                      // Set integration time, (ms) → void
                var t0 = System.currentTimeMillis();
                sensor.read();                                                                      // One-shot read, () → Channels
                System.out.printf("integration %.1f ms took %d ms%n", sensor.getIntegrationTime(), System.currentTimeMillis() - t0);
            }
            sensor.setIntegrationTime(165.2);                                                       // Set integration time, (ms) → void
            sensor.setMode(3);                                                                      // Set bank mode, (mode) → void
            var base = sensor.read().toArray();                                                     // Read calibrated, () → Channels
            sensor.setIndicatorLed(true, 4.0);                                                      // Indicator LED, (on, currentMa) → void
            sensor.setLed(true, 25.0);                                                              // Driver LED, (on, currentMa) → void
            var lit = sensor.read().toArray();                                                      // Read calibrated, () → Channels
            sensor.setLed(false, 25.0);                                                             // Driver LED off, (on, currentMa) → void
            sensor.setIndicatorLed(false, 4.0);                                                     // Indicator LED off, (on, currentMa) → void
            int best = 0;
            for (int i = 1; i < 6; i++) if (lit[i] - base[i] > lit[best] - base[best]) best = i;
            System.out.printf("reflectance dominant channel %d%n", best);
            sensor.measure();                                                                       // Start one-shot, () → void
            while (!sensor.dataReady()) Thread.sleep(50);                                           // Data ready, () → boolean
            var last = sensor.readCalibratedLast();                                                 // Last calibrated results, () → Channels
            var lastRaw = sensor.readRawLast();                                                     // Last raw counts, () → RawChannels
            System.out.printf("last V %.2f raw V %d channel 0 %.2f%n", last.violet, lastRaw.violet, sensor.readChannel(0));
            sensor.setContinuous(2);                                                                // Continuous mode, (mode) → void
            sensor.onInterrupt(status -> {                                                          // Subscribe to INT, (callback) → void
                try {
                    System.out.println(sensor.readCalibratedLast().violet);
                } catch (java.io.IOException e) {
                    System.out.println(e.getMessage());
                }
            });
            Thread.sleep(5000);
            sensor.offInterrupt();                                                                  // Unsubscribe, () → void
            sensor.setMode(3);                                                                      // Back to one-shot, (mode) → void
            sensor.writeVirtual(0x05, 0x3B);                                                        // Write virtual register, (reg, value) → void
            System.out.printf("INT_T %d%n", sensor.readVirtual(0x05));
            sensor.softReset();                                                                     // Soft reset, () → void
        }
    }
}
