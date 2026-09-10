///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.accelerometer.Adxl345Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x53").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var accel = new Adxl345Full(connection);                                         // construct driver and verify DEVID, (connection) → Adxl345Full

            accel.setRange(4);                                                                // Set measurement range, (range_g) → g
                                                                                                   // selects ±4 g; FULL_RES preserved so scale stays 3.9 mg/LSB
            accel.setDataRate(200);                                                            // Set output data rate, (rate_hz) → Hz
                                                                                                   // picks the nearest supported value (200 Hz)
            accel.setLowPower(false);                                                          // Set low-power mode, (enabled) → None
                                                                                                   // normal-power mode; LOW_POWER bit in BW_RATE cleared
            accel.calibrateOffset(0.0, 0.0, 1.0, 64);                                          // Calibrate offsets, (target_x=0 g, target_y=0 g, target_z=1 g, samples=64) → g, g, g
                                                                                                   // averages 64 samples with Z axis up and writes OFSX/OFSY/OFSZ
            accel.setTapDetection(0.5, 10.0, 0x07, false);                                     // Configure single-tap, (threshold_g, duration_ms, axes=0x07, suppress=false) → g, ms
                                                                                                   // 0.5 g threshold, 10 ms duration, all axes, no suppress
            accel.setDoubleTap(50.0, 200.0);                                                   // Configure double-tap, (latency_ms, window_ms) → ms, ms
                                                                                                   // 50 ms latency, 200 ms window between taps
            accel.setFifoMode(Adxl345Full.FIFO_STREAM, 16);                                   // Configure FIFO, (mode, samples=16) → None
                                                                                                   // stream mode, watermark 16 entries
            accel.setInterrupt(Adxl345Full.INT_WATERMARK, true, 1);                            // Configure interrupt, (source, enabled, pin=1) → None
                                                                                                   // enable watermark interrupt on INT1

            double[] xyz = accel.read();                                                       // Read 3-axis acceleration, () → double[3] g, g, g
                                                                                                   // single-shot burst read of all 6 data bytes
            double[][] samples = accel.readFifo(32);                                           // Drain the FIFO, (max_samples) → double[][] g
                                                                                                   // up to 32 (x, y, z) samples in *g*
            int count = accel.fifoCount();                                                      // FIFO entries available, () → int
                                                                                                   // from FIFO_STATUS register
            int src = accel.readInterruptSource();                                              // Read interrupt source, () → int
                                                                                                   // bitmask of active INT_* sources; clears latches

            accel.selfTest(false);                                                              // Toggle self-test, (enabled) → None
                                                                                                   // SELF_TEST bit in DATA_FORMAT cleared
            accel.setSleep(false, 8);                                                           // Set sleep mode, (enabled, wakeup_hz=8) → None, Hz
                                                                                                   // wake up; no further state changes
            accel.setLinkMode(false);                                                           // Set activity/inactivity link, (enabled) → None
                                                                                                   // Link bit in POWER_CTL cleared
            accel.setAutoSleep(false);                                                          // Set auto-sleep, (enabled) → None
                                                                                                   // AUTO_SLEEP bit cleared

            System.out.printf("x=%.3f y=%.3f z=%.3f g%n", xyz[0], xyz[1], xyz[2]);
            System.out.printf("fifo_count=%d interrupts=0x%02X%n", count, src);
            System.out.printf("samples=%d%n", samples.length);
        }
    }
}