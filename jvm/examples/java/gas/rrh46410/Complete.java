///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-java:1.5.4

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.gas.Rrh46410Full;

import java.util.HexFormat;

public class Complete {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x38").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Rrh46410Full(connection);                                              // Create RRH46410 driver, (connection) → Rrh46410Full
            // The module has the fixed address 0x38 and stretches the I²C clock; no bus traffic yet.
            sensor.init();                                                                          // Check product ID, start IAQ 2nd Gen mode, () → void
            // Retries Get Product ID for up to 1 s, verifies 0x2310, selects IAQ 2nd Gen (3 s samples).

            System.out.println(sensor.productId());                                                 // Product ID and firmware, () → ProductId {productId, major, minor, patch}
            // The product ID is always 0x2310.
            System.out.println(HexFormat.of().formatHex(sensor.trackingNumber()));                  // ASIC tracking number, () → byte[6]
            // Unique per module.
            System.out.println(sensor.getMode());                                                   // Operation mode, () → int
            // 1 = IAQ 2nd Gen after init.

            sensor.setHumidity(45.0);                                                               // Ambient humidity, (percent %RH) → void
            // Sent as round(percent / 100 × 255); the module assumes 50 %RH otherwise.
            System.out.println(sensor.read());                                                      // Next sample, () → Measurement
            // Waits for the sample counter to change; values are fixed during the warm-up.
            System.out.println(sensor.waitForSample(4.0));                                          // Wait for a new sample, (timeout s) → boolean
            // True when the counter changed (or INT went high) within the timeout.
            System.out.println(sensor.readResults());                                               // Latest result, () → Measurement or null
            // Does not wait; null before the first sample of the mode.
            System.out.println(sensor.lastResult());                                                // Last decoded result, () → Measurement
            // The sample stored by read, readResults or waitForSample.
            System.out.println(Rrh46410Full.airQualityLabel(2.4));                                  // UBA rating, (iaq) → String
            // "very good", "good", "medium", "poor" or "bad".
            System.out.println(sensor.samplePeriod() + " s, " + sensor.warmupSamples());            // Mode timing, (mode=current) → double s / int
            // 3 s and 100 samples in IAQ 2nd Gen mode.
            System.out.println(sensor.status());                                                    // Last status byte, () → Status
            // Error bits and the operation-mode error code (0xA = sensor damaged).

            sensor.onInterrupt(r -> System.out.println("sample " + r.sample() + " IAQ " + r.iaq())); // Subscribe, (callback, intPin=connection.intPin()) → void
            // Rising edge on INT, or a 200 ms poll comparing the sample counter.
            Thread.sleep(7000);
            sensor.offInterrupt();                                                                  // Unsubscribe, () → void
            // Stops the pin handler or polling thread.
            System.out.println(sensor.pollInterrupt());                                             // New sample pending, () → boolean
            // INT level if wired, otherwise a counter comparison.

            sensor.setMode(Rrh46410Full.MODE_PBAQ);                                                 // Operation mode, (mode) → void
            // PBAQ: TVOC and ethanol only, every 5 s; restarts the warm-up.
            System.out.println(sensor.read());                                                      // Next sample, () → Measurement
            // PBAQ results have a finer resolution (raw / 1000); IAQ and eCO2 are 0.
            sensor.setMode(Rrh46410Full.MODE_ULP);                                                  // Operation mode, (mode) → void
            // ULP: one sample every 90 s at 0.23 mW.
            System.out.println(sensor.samplePeriod(Rrh46410Full.MODE_ULP) + " s, " + sensor.warmupSamples(Rrh46410Full.MODE_ULP)); // Mode timing, (mode) → double s / int
            // 90 s and 10 samples.

            sensor.configGpio(0x03);                                                                // GPIO directions, (directions) → void
            // Bit 0 = GPIO1 output, bit 1 = GPIO2 output.
            sensor.setGpio(0x01);                                                                   // Drive high, (mask) → void
            // GPIO1 high.
            System.out.println(Integer.toBinaryString(sensor.getGpio()));                           // GPIO levels, () → int
            // Bits 0–3 = GPIO1–GPIO4.
            sensor.clearGpio(0x01);                                                                 // Drive low, (mask) → void
            // GPIO1 low.

            System.out.println(sensor.sendCommand(0x10, new byte[0], 1)[0]);                       // Raw request, (command, params, responseLen) → byte[]
            // Checksums added and verified; here Get Operation Mode.
            // sensor.cleanSensor(true);                                                            // One-time cleaning, (confirm) → void
            // Runs for one minute, once in the sensor's lifetime; left disabled here.
            sensor.suspend();                                                                       // Suspend mode, () → void
            // Stops measuring, about 0.7 µA.
            sensor.reset();                                                                         // Reset, () → void
            // Waits 500 ms and verifies the product ID; the module restarts in SUSPEND.
        }
    }
}
