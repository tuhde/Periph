///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.TSL2561Full;

import java.util.Arrays;

/*
 * Light meter with alarm: prints the ID, takes an auto-ranged lux reading every
 * 2 s for 30 s with gain, integration time and both channel counts, then sets a
 * ±50 % channel-0 window with persistence 2, reports pollInterrupt() while you
 * cover the sensor, releases the interrupt and powers the chip down.
 */
public class Demo {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, TSL2561Full.DEFAULT_ADDRESS)) {               // open I²C bus, device address, (bus, address=0x39) → I2CConnection
            var sensor = new TSL2561Full(connection);                                              // construct driver, (connection) → TSL2561Full
            System.out.printf("ID 0x%02X%n", sensor.readId());                                     // Read the ID register, () → int

            /* Light meter: auto-range picks the most sensitive setting that does not saturate. */
            for (int i = 0; i < 15; i++) {
                double lux = sensor.autoRange();                                                   // Auto-range and read lux, () → double lux
                int[] raw = sensor.readRaw();                                                      // Read both channels, () → int[] {ch0, ch1}
                System.out.printf("%9.1f lx  gain %2dx  %3d ms  ch0 %5d  ch1 %5d%n",
                        lux, sensor.getGain(), sensor.getIntegrationTime(), raw[0], raw[1]);       // Current settings, () → int
                Thread.sleep(2000);
            }

            /* Alarm: a window of ±50 % around the current channel-0 count. */
            int ch0 = sensor.readRaw()[0];
            sensor.setThresholds(ch0 / 2, Math.min(65535, ch0 * 3 / 2));                           // Set channel-0 window, (low, high) → void
            sensor.configureInterrupt(1, 2);                                                       // Level interrupt, 2 cycles outside, (mode, persist) → void
            System.out.println("window " + Arrays.toString(sensor.getThresholds()) + " - cover the sensor now");  // Read the window, () → int[]
            for (int i = 0; i < 10; i++) {
                System.out.println("outside window: " + sensor.pollInterrupt());                   // Check ch0 against the window, () → boolean
                Thread.sleep(1000);
            }

            sensor.clearInterrupt();                                                               // Release the level interrupt, () → void
            sensor.configureInterrupt(0, 0);
            sensor.powerDown();                                                                    // Power down, () → void
            System.out.println("powered: " + sensor.isPowered());                                  // Power state, () → boolean
        }
    }
}
