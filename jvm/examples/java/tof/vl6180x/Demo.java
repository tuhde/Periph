///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.tof.VL6180XFull;
import java.util.Arrays;
import it.uhde.periph.chips.tof.VL6180XMinimal;

public class Demo {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, VL6180XMinimal.DEFAULT_ADDRESS, null, null, 2)) {   // open I²C bus, device address, register width 2, (bus, address=0x29, null, null, regBytes=2) → I2CConnection
            var sensor = new VL6180XFull(connection);                                          // Create VL6180X driver, (connection) → VL6180XFull
            // --- Identification ---
            System.out.println("model" + " " + sensor.modelId() + " revision " + Arrays.toString(sensor.revision())); // Model ID, () → int ; () → int[] {major, minor}

            // --- Single-shot ranging at 5 Hz for 10 s ---
            for (int i = 0; i < 50; i++) {
                System.out.println("mm" + " " + sensor.readRange());                      // Single-shot range, () → int mm
                Thread.sleep(200);
            }

            // --- Continuous ranging at 50 ms: move a hand towards the sensor ---
            sensor.startContinuousRange(50);                                              // Start continuous ranging, (periodMs) → void
            for (int i = 0; i < 40; i++) {
                var st = sensor.readRangeStatus();                                        // Range and error code, () → int[] {mm, error}
                System.out.println("mm" + " " + st[0] + " (error " + st[1] + ")");
            }
            sensor.stopContinuousRange();                                                 // Stop continuous ranging, () → void
            Thread.sleep(200);

            // --- Out-of-window interrupt for 30-80 mm ---
            sensor.setRangeThresholds(30, 80);                                            // Range thresholds, (low, high) → void
            sensor.enableInterrupt(VL6180XMinimal.SOURCE_RANGE, VL6180XMinimal.MODE_OUT_OF_WINDOW); // Enable an interrupt, (source, mode) → void
            sensor.startContinuousRange(50);
            for (int i = 0; i < 50; i++) {
                if ((sensor.pollInterrupt() & VL6180XMinimal.SOURCE_RANGE) != 0) {        // Read and clear, () → int SOURCE_* mask
                    System.out.println("range" + " " + "out of window");
                }
                Thread.sleep(100);
            }
            sensor.stopContinuousRange();
            Thread.sleep(200);
            sensor.enableInterrupt(VL6180XMinimal.SOURCE_RANGE, VL6180XMinimal.MODE_NEW_SAMPLE_READY);

            // --- Light meter at gain 1 and 20 ---
            for (int code : new int[] {6, 0}) {
                sensor.setAlsGain(code);                                                  // ALS gain code, (code) → void
                System.out.println("gain code " + code + " " + sensor.readLux());         // Single-shot ALS in lux, () → double lux
            }
            sensor.setAlsGain(6);

            // --- Interleaved ALS + range for 5 s ---
            sensor.setInterleaved(true);                                                  // Interleaved ALS + range, (on) → void
            sensor.startContinuousAls(100);                                               // Start continuous ALS, (periodMs) → void
            Thread.sleep(5000);
            sensor.stopContinuousAls();                                                   // Stop continuous ALS, () → void
            sensor.setInterleaved(false);

            // --- Calibration and diagnostics ---
            sensor.setRangeOffset(0);                                                     // Part-to-part offset, (mm) → void
            sensor.setCrosstalkRate(0.0);                                                 // Crosstalk rate, (rateMcps) → void
            sensor.setAlsIntegrationTime(100);                                            // ALS integration time, (ms) → void
            sensor.setAlsScaler(1);                                                       // ALS result scaler, (scaler) → void
            sensor.recalibrateVhv();                                                      // Recalibrate VHV, () → void
            System.out.println("als raw" + " " + sensor.readAlsRaw() + ", offset " + sensor.getRangeOffset()); // Single-shot ALS count, () → int ; () → int mm
            System.out.println("history" + " " + Arrays.toString(sensor.readHistory()));  // History buffer, () → int[8]

            // --- Change the device address and back (volatile) ---
            sensor.setAddress(0x30);                                                      // Change address, (address) → void
            try (var moved = new I2CConnection(bus, 0x30, null, null, 2)) {
                var sensor2 = new VL6180XFull(moved);
                System.out.println("on 0x30: model " + sensor2.modelId());
                sensor2.setAddress(VL6180XMinimal.DEFAULT_ADDRESS);
            }
        }
    }
}
