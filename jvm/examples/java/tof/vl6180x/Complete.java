///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.tof.VL6180XFull;
import java.util.Arrays;
import it.uhde.periph.chips.tof.VL6180XMinimal;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, VL6180XMinimal.DEFAULT_ADDRESS, null, null, 2)) {   // open I²C bus, device address, register width 2, (bus, address=0x29, null, null, regBytes=2) → I2CConnection
            var sensor = new VL6180XFull(connection);                                          // Create VL6180X driver, (connection) → VL6180XFull
            System.out.println("range" + " " + sensor.readRange());                       // Single-shot range, () → int mm
                                                                                          // throws IOException on a non-zero range error code; see lastError()
            System.out.println("last error" + " " + sensor.lastError());                  // Last range error code, () → int
            var st = sensor.readRangeStatus();                                            // Range and error code, () → int[] {mm, error}
                                                                                          // never throws on a range error; 0 = valid
            System.out.println("range status" + " " + Arrays.toString(st));
            System.out.println("als raw" + " " + sensor.readAlsRaw());                    // Single-shot ALS count, () → int
            System.out.println("lux" + " " + sensor.readLux());                           // Single-shot ALS in lux, () → double lux
                                                                                          // 0.32 lux/count at gain 1, 100 ms

            sensor.startContinuousRange(50);                                              // Start continuous ranging, (periodMs) → void
                                                                                          // 10-2550 ms in 10 ms steps
            System.out.println("continuous" + " " + sensor.readRangeContinuous());        // Wait for next sample, () → int mm
            sensor.stopContinuousRange();                                                 // Stop continuous ranging, () → void
            sensor.startContinuousAls(100);                                               // Start continuous ALS, (periodMs) → void
            sensor.stopContinuousAls();                                                   // Stop continuous ALS, () → void
            sensor.setInterleaved(false);                                                 // Interleaved ALS + range, (on) → void

            sensor.setAlsGain(6);                                                         // ALS gain code, (code) → void
                                                                                          // 0 = 20 ... 6 = 1, 7 = 40
            sensor.setAlsIntegrationTime(100);                                            // ALS integration time, (ms) → void
                                                                                          // 1-512 ms
            sensor.setAlsScaler(1);                                                       // ALS result scaler, (scaler) → void
            sensor.setRangeThresholds(30, 80);                                            // Range thresholds, (low, high) → void
            sensor.setAlsThresholds(100, 5000);                                           // ALS thresholds, (low, high) → void
            sensor.setMaxConvergenceTime(49);                                             // Max convergence time, (ms) → void
            sensor.setAveragingPeriod(48);                                                // Averaging period, (units) → void
            sensor.setRangeOffset(0);                                                     // Part-to-part offset, (mm) → void
            System.out.println("offset" + " " + sensor.getRangeOffset());                 // Read the offset, () → int mm
            sensor.setCrosstalkRate(0.0);                                                 // Crosstalk rate, (rateMcps) → void
            sensor.recalibrateVhv();                                                      // Recalibrate VHV, () → void

            System.out.println("model" + " " + sensor.modelId());                         // Model ID, () → int
                                                                                          // always 0xB4
            System.out.println("revision" + " " + Arrays.toString(sensor.revision()));    // Model revision, () → int[] {major, minor}
            System.out.println("history" + " " + Arrays.toString(sensor.readHistory()));  // History buffer, () → int[8]

            sensor.enableInterrupt(VL6180XMinimal.SOURCE_RANGE, VL6180XMinimal.MODE_OUT_OF_WINDOW); // Enable an interrupt, (source, mode) → void
            System.out.println("pending" + " " + sensor.pollInterrupt());                 // Read and clear, () → int SOURCE_* mask
            sensor.onInterrupt(status -> System.out.println("irq " + status));            // Subscribe, (callback) → void
                                                                                          // connection.intPin() falling edge, else a 5 ms polling thread
            Thread.sleep(100);
            sensor.offInterrupt();                                                        // Unsubscribe, () → void
            sensor.disableInterrupt(VL6180XMinimal.SOURCE_RANGE);                         // Disable an interrupt, (source) → void
            sensor.enableInterrupt(VL6180XMinimal.SOURCE_RANGE, VL6180XMinimal.MODE_NEW_SAMPLE_READY);
            // sensor.setAddress(0x30)                                                    // Change address (volatile), (address) → void
        }
    }
}
