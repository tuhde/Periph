///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.accelerometer.LSM303Full;

import java.util.Arrays;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var accelConn = new I2CConnection(bus, 0x19);
             var magConn   = new I2CConnection(bus, 0x1E)) {
            var sensor = new LSM303Full(accelConn, magConn);                 // construct driver and run init, (accelConnection, magConnection) → LSM303Full
                                                                             // verifies magnetometer ID 0x483433 and leaves ±2 g, 100 Hz, ±130 µT, 15 Hz

            sensor.setRange(4);                                              // Set full-scale range, (rangeG=2|4|8|16) → None g
                                                                             // selects ±4 g; the cached sensitivity updates to 2 mg/LSB
            sensor.setDataRate(50);                                          // Set accelerometer ODR, (rateHz) → None Hz
                                                                             // 50 Hz is valid in every mode
            sensor.setMode(2);                                               // Set accelerometer mode, (mode=0 low-power|1 normal|2 high-resolution) → None
                                                                             // LPen=0, HR=1; high-resolution 12-bit output
            sensor.setAxes(true, true, true);                                // Enable accelerometer axes, (x, y, z) → None
                                                                             // X, Y and Z enabled (Xen/Yen/Zen in CTRL_REG1_A)
            double[] a = sensor.readAcceleration();                          // Read acceleration, () → double[3] g
                                                                             // burst-reads OUT_X_L_A…OUT_Z_H_A with auto-increment
            int[] raw = sensor.readAccelerationRaw();                        // Read raw acceleration, () → int[3] counts
                                                                             // signed counts at the current resolution (raw16 >> 4 in HR mode)
            boolean fresh = sensor.newDataAvailable();                       // Check new sample, () → boolean
                                                                             // true when STATUS_REG_A.ZYXDA is set
            int status = sensor.readStatus();                                // Read accelerometer status, () → int
                                                                             // STATUS_REG_A byte: ZYXOR…XDA flags
            System.out.printf("a=%s raw=%s fresh=%b status=0x%02X%n", Arrays.toString(a), Arrays.toString(raw), fresh, status);

            sensor.setHighPass(1, 0, false, true, false, false);             // Configure high-pass filter, (mode, cutoff, filterOutput, onIa1, onIa2, onClick) → None
                                                                             // reference-filter mode; filter applied to generator 1 only
            sensor.setReference(0x00);                                       // Set high-pass reference, (value=0–255) → None
                                                                             // REFERENCE_A value used when HPM = 01
            int ref = sensor.resetHighPass();                                // Read REFERENCE_A, () → int
                                                                             // reading REFERENCE_A resets the filter in HPM = 00 mode
            System.out.printf("reference=%d%n", ref);

            sensor.setFifo(2, 16, false);                                    // Configure FIFO, (mode=0–3, watermark=0–31, triggerInt2=False) → None
                                                                             // stream mode with a 16-sample watermark; FIFO_EN set in CTRL_REG5_A
            Thread.sleep(300);
            int level = sensor.fifoLevel();                                  // Read FIFO level, () → int samples
                                                                             // FSS<4:0>, number of unread sample sets
            double[][] samples = sensor.readFifo(4);                         // Read FIFO samples, (count=None) → list of [x, y, z] g
                                                                             // reads up to 4 sets, one 6-byte burst each
            int[] fifoStatus = sensor.readFifoStatus();                      // Read FIFO status, () → int[4] (watermark, overrun, empty, level)
                                                                             // decodes FIFO_SRC_REG_A
            System.out.printf("fifo level=%d read=%d status=%s%n", level, samples.length, Arrays.toString(fifoStatus));
            sensor.setFifo(0, 0, false);                                     // Return FIFO to bypass, (mode=0) → None
                                                                             // bypass clears the buffer; FIFO_EN cleared

            sensor.setMotionInterrupt(1, 0x3F, 1.0, 100, false);             // Configure motion interrupt, (generator, events, threshold_g, duration_ms, andMode) → None
                                                                             // OR of all six axis events, 1 g threshold, 100 ms minimum duration
            sensor.setFreeFall(2, 0.35, 30);                                 // Configure free-fall, (generator, threshold_g, duration_ms) → None
                                                                             // AND of low X/Y/Z events on generator 2
            sensor.setOrientation(1, false, false);                          // Configure 6D orientation, (generator, position, fourD) → None
                                                                             // movement recognition on generator 1
            sensor.setLatch(1, true);                                        // Latch interrupt generator, (generator, enabled) → None
                                                                             // LIR_INT1 set: IA stays high until INT1_SOURCE_A is read
            int src = sensor.readInterruptSource(1);                         // Read interrupt source, (generator) → int
                                                                             // INT1_SOURCE_A byte; clears the latched interrupt
            System.out.printf("int1 source=0x%02X%n", src);

            sensor.setClick(0x04, 0x04, 0.8, 20, 40, 200);                   // Configure click detection, (single, double, threshold_g, time_limit_ms, latency_ms, window_ms) → None
                                                                             // Z-axis single and double click, 0.8 g threshold
            int click = sensor.readClickSource();                            // Read click source, () → int
                                                                             // CLICK_SRC_A byte; clears the click interrupt
            System.out.printf("click source=0x%02X%n", click);

            sensor.setInterruptPolarity(false);                              // Set INT polarity, (activeLow) → None
                                                                             // H_LACTIVE=0: INT1/INT2 active high
            sensor.enableInterrupt(LSM303Full.SOURCE_CLICK, 1);              // Route interrupt source, (source, pin=1) → None
                                                                             // click routed to INT1 (CTRL_REG3_A I1_CLICK)
            sensor.enableInterrupt(LSM303Full.SOURCE_IA1, 2);                // Route interrupt source, (source, pin=2) → None
                                                                             // generator 1 routed to INT2 (CTRL_REG6_A I2_INT1)
            sensor.disableInterrupt(LSM303Full.SOURCE_IA1, 2);               // Remove interrupt source, (source, pin=2) → None
                                                                             // generator 1 removed from INT2

            sensor.onInterrupt(s -> System.out.printf("interrupt status=0x%02X%n", s), 1); // Subscribe to INT1, (callback, pin=1) → None
                                                                             // no INT1 pin wired, so a 5 ms polling thread delivers events
            Thread.sleep(200);
            sensor.offInterrupt(1);                                          // Unsubscribe from INT1, (pin=1) → None
                                                                             // stops the polling thread
            int pending = sensor.pollInterrupt();                            // Poll interrupt sources, () → int
                                                                             // status bitmask of enabled sources; clears latched generator and click flags
            System.out.printf("pending=0x%04X%n", pending);

            sensor.setMagRange(810);                                         // Set magnetometer range, (rangeUt=130|190|250|400|470|560|810) → None
                                                                             // GN=111, ±8.1 gauss; cached gains updated
            sensor.setMagDataRate(15);                                       // Set magnetometer ODR, (rateHz) → None Hz
                                                                             // DO=100 (15 Hz); TEMP_EN kept
            sensor.setMagMode(0);                                            // Set magnetometer mode, (mode=0 continuous|1 single|2 sleep) → None
                                                                             // continuous conversion
            int[] magRaw = sensor.readMagneticRaw();                         // Read raw magnetic field, () → int[3] counts
                                                                             // X, Y, Z counts after reordering from the X, Z, Y wire order
            boolean drdy = sensor.magDataReady();                            // Check magnetometer data ready, () → boolean
                                                                             // true when SR_REG_M.DRDY is set
            int[] magStatus = sensor.readMagStatus();                        // Read magnetometer status, () → int[2] (drdy, lock)
                                                                             // decodes SR_REG_M
            byte[] id = sensor.readMagIdentity();                            // Read magnetometer ID, () → byte[3]
                                                                             // IRA/IRB/IRC: 0x48 0x34 0x33 ("H43")
            System.out.printf("mag raw=%s drdy=%b status=%s id=%s%n",
                    Arrays.toString(magRaw), drdy, Arrays.toString(magStatus), Arrays.toString(id));

            sensor.enableTemperature(true);                                  // Enable temperature sensor, (enabled) → None
                                                                             // sets TEMP_EN in CRA_REG_M
            double dT = sensor.readTemperatureDelta();                       // Read temperature delta, () → double °C
                                                                             // relative only (8 LSB/°C), no absolute reference
            double[] m = sensor.readMagnetic();                              // Read magnetic field, () → double[3] µT
                                                                             // X, Y, Z in µT with the per-axis gain
            double hFlat = sensor.heading(false);                            // Compass heading (flat), (tiltCompensate=False) → double degrees
                                                                             // atan2(my, mx), 0–360
            double hTilt = sensor.heading(true);                             // Compass heading (tilt-compensated), (tiltCompensate=True) → double degrees
                                                                             // reads the accelerometer for roll and pitch
            System.out.printf("dT=%.3f C  m=%s  heading flat=%.1f tilt=%.1f%n",
                    dT, Arrays.toString(m), hFlat, hTilt);

            sensor.powerDown();                                              // Power down accelerometer, () → None
                                                                             // ODR=0000; register contents retained
            sensor.setDataRate(100);                                         // Resume accelerometer, (rateHz) → None Hz
                                                                             // back to 100 Hz after power-down
            sensor.setMagMode(2);                                            // Put magnetometer to sleep, (mode=2) → None
                                                                             // MD=10 sleep mode
            sensor.reboot();                                                 // Reboot accelerometer memory, () → None
                                                                             // BOOT=1, waits 5 ms; construct the driver again to re-run init
        }
    }
}
