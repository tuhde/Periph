///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.accelerometer.Lis3dhFull;
import it.uhde.periph.chips.accelerometer.Lis3dhMinimal;
import it.uhde.periph.chips.accelerometer.Lis3dhSource;

import java.util.List;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x18").replaceFirst("^0[xX]", ""), 16);
        try (var connection = new I2CConnection(bus, addr)) {
            var accel = new Lis3dhFull(connection);                     // Create LIS3DH Full driver, (connection)
                                                                       // runs the datasheet init sequence (±2 g, 100 Hz, 12-bit HR)
            double[] xyz = accel.read();                                // Read 3-axis acceleration, () → [g, g, g]
                                                                       // burst read of 0x28–0x2D with auto-increment, 1 mg/LSB
            accel.setRange(4);                                          // Set full-scale range, (rangeG) → g
                                                                       // ±4 g; the sensitivity changes from 1 to 2 mg/LSB
            accel.setMode(Lis3dhMinimal.MODE_NORMAL);                  // Set operating mode, (mode) → None
                                                                       // 10-bit mode (4 mg/LSB at ±2 g); the driver waits for the turn-on time
            accel.setDataRate(50);                                     // Set output data rate, (rateHz) → Hz
                                                                       // 50 Hz; the driver waits for the turn-on time
            accel.setAxes(true, true, true);                           // Set axis enables, (x, y, z) → None
                                                                       // Xen / Yen / Zen in CTRL_REG1
            int[] raw = accel.readRaw();                               // Read raw counts, () → [int, int, int]
                                                                       // signed counts at the current resolution (10-bit here)
            boolean ready = accel.newDataAvailable();                   // Check new sample set, () → boolean
                                                                       // STATUS_REG.ZYXDA
            int status = accel.readStatus();                           // Read STATUS_REG, () → int
                                                                       // overrun and data-available flags
            accel.enableAdc(true);                                     // Enable the auxiliary ADC, (enabled) → None
                                                                       // ADC_EN in TEMP_CFG_REG
            double volts = accel.readAdc(1);                           // Read an auxiliary ADC channel, (channel) → V
                                                                       // 1.200 V centre, ±400 mV around it
            int temp = accel.readTemperatureDelta();                   // Read the relative temperature, () → °C
                                                                       // 1 digit/°C, relative to an unspecified reference
            accel.setHighPass(1, 1, true, true, false, false);         // Configure the high-pass filter, (mode, cutoff, filterOutput, onIa1, onIa2, onClick) → None
                                                                       // HPM = 01 (reference signal), HPCF = 01, filtered data on the outputs
            int reference = accel.resetHighPass();                     // Reset the high-pass filter, () → int
                                                                       // reading REFERENCE resets the filter in HPM = 00
            accel.setReference(0x10);                                  // Set the high-pass reference, (value) → None
                                                                       // REFERENCE = 0x10, used with HPM = 01
            accel.setFifo(1, 8, false);                                // Configure the FIFO, (mode, watermark, triggerInt2) → None
                                                                       // FIFO mode with a watermark of 8 samples
            int level = accel.fifoLevel();                             // Read the FIFO level, () → int
                                                                       // number of unread sample sets
            List<double[]> samples = accel.readFifo();                 // Drain the FIFO, () → [[g, g, g], …]
                                                                       // one 6-byte burst per sample set, oldest first
            Lis3dhFull.FifoStatus fifo = accel.readFifoStatus();        // Read the FIFO status, () → FifoStatus
                                                                       // watermark / overrun / empty flags plus the level
            accel.setMotionInterrupt(1, 0x35, 0.5, 10, true);          // Configure motion interrupt 1, (generator, events, thresholdG, durationMs, andMode) → None
                                                                       // all six axis events, 0.5 g threshold, 10 ms, AND combination
            accel.setFreeFall(1, 0.35, 60);                            // Configure free fall, (generator, thresholdG, durationMs) → None
                                                                       // all three axes low, AND mode, 0.35 g for 60 ms
            accel.setOrientation(1, false, false);                     // Configure 6D orientation, (generator, position, fourD) → None
                                                                       // 6D movement recognition (a one-ODR pulse)
            accel.setLatch(1, true);                                   // Latch a generator interrupt, (generator, enabled) → None
                                                                       // LIR_INT1: the INT line stays high until INT1_SRC is read
            int intSrc = accel.readInterruptSource(1);                 // Read the generator source, (generator) → int
                                                                       // INT1_SRC — reading it clears the interrupt
            accel.setClick(0, 0x04, 0.5, 10, 20, 50, false);           // Configure click detection, (single, doubleClick, thresholdG, timeLimitMs, latencyMs, windowMs, latch) → None
                                                                       // double click on Z with a 0.5 g threshold
            int clickSrc = accel.readClickSource();                     // Read the click source, () → int
                                                                       // CLICK_SRC — reading it clears the click interrupt
            accel.setActivity(0.5, 100);                               // Configure the activity function, (thresholdG, durationMs) → None
                                                                       // sleep-to-wake: drops to 10 Hz low power below 0.5 g
            accel.setInterruptPolarity(false);                         // Set the INT polarity, (activeLow) → None
                                                                       // INT_POLARITY = 0 (active high, the reset default)
            accel.enableInterrupt(Lis3dhSource.SOURCE_CLICK, 1);       // Route a source to an INT pin, (source, pin) → None
                                                                       // click events on INT1 (CTRL_REG3.I1_CLICK)
            accel.enableInterrupt(Lis3dhSource.SOURCE_IA1, 1);          // Route a source to an INT pin, (source, pin) → None
                                                                       // generator 1 events on INT1 (CTRL_REG3.I1_IA1)
            int combined = accel.pollInterrupt();                      // Poll the interrupt status, () → int
                                                                       // bitmask of every enabled source that fired
            accel.disableInterrupt(Lis3dhSource.SOURCE_IA1, 1);        // Remove a source from an INT pin, (source, pin) → None
                                                                       // clears CTRL_REG3.I1_IA1
            accel.onInterrupt(s -> System.out.printf("INT status=0x%02X%n", s));
                                                                       // Subscribe to the INT line, (callback) → None
                                                                       // the callback receives the combined status int
            accel.offInterrupt();                                      // Unsubscribe from the INT line, () → None
                                                                       // removes the edge handler
            accel.setSdoPullup(true);                                  // Connect the SDO pull-up, (connected) → None
                                                                       // SDO_PU_DISC = 0 in CTRL_REG0
            boolean st = accel.selfTest(1);                            // Run the self-test, (mode) → boolean
                                                                       // electrostatic self-test 0; every axis must change by 17..360 LSb
            accel.powerDown();                                         // Stop the conversions, () → None
                                                                       // ODR = 0000; the registers keep their content
            accel.reboot();                                            // Reboot the memory, () → None
                                                                       // BOOT = 1, reloads the factory trim and re-runs the init sequence

            System.out.printf("xyz=(%.3f, %.3f, %.3f) raw=(%d,%d,%d) status=0x%02X ready=%s%n",
                    xyz[0], xyz[1], xyz[2], raw[0], raw[1], raw[2], status, ready);
            System.out.printf("temp=%d adc=%.3f V fifoLevel=%d fifoEmpty=%s samples=%d reference=0x%02X%n",
                    temp, volts, level, fifo.empty, samples.size(), reference);
            System.out.printf("intSrc=0x%02X clickSrc=0x%02X combined=0x%02X selfTest=%s%n",
                    intSrc, clickSrc, combined, st ? "PASS" : "FAIL");
        }
    }
}
