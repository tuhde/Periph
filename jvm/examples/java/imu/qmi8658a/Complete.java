///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-java:1.3.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.imu.QMI8658AFull;

public class Complete {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x6B").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var imu = new QMI8658AFull(connection);                           // Create QMI8658A driver, (connection) → void

            double[] a = imu.accel(); // Read 3-axis acceleration, () → double[] m/s²
                                                                            // converts raw counts at 16384 LSB/g (±2 g)
            double[] g = imu.gyro(); // Read 3-axis angular rate, () → double[] rad/s
                                                                            // converts raw counts at 128 LSB/dps (±256 dps)
            int[] rawA = imu.accelRaw(); // Read raw accel counts, () → int[]
                                                                            // one 6-byte burst, signed 16-bit
            int[] rawG = imu.gyroRaw(); // Read raw gyro counts, () → int[]
                                                                            // one 6-byte burst, signed 16-bit
            var sample = imu.readAll(); // Read one coherent sample, () → Sample (accel m/s², gyro rad/s, tempC °C)
                                                                            // one 14-byte burst of registers 0x33…0x40
            double t = imu.temperature(); // Read die temperature, () → double °C
                                                                            // signed 8.8 fixed point, raw / 256
            int ts = imu.timestamp(); // Read sample counter, () → int
                                                                            // 24-bit counter, wraps at 0xFFFFFF
            boolean ready = imu.dataReady(); // Check for new data, () → boolean
                                                                            // STATUS0 aDA / gDA
            imu.configureAccel(1, 5); // Configure accelerometer, (fullScale, odr) → void
                                                                            // CTRL2: 0=±2, 1=±4, 2=±8, 3=±16 g; ODR code 5 = 224.2 Hz
            imu.configureGyro(5, 5); // Configure gyroscope, (fullScale, odr) → void
                                                                            // CTRL3: 0=±16 … 7=±2048 dps
            imu.configureFilters(true, 3, true, 3); // Configure low-pass filters, (accelFilter, accelMode, gyroFilter, gyroMode) → void
                                                                            // CTRL5: mode 3 = 13.37 % of ODR
            imu.setSensors(true, true, false); // Enable sensors, (accel, gyro, gyroSnooze) → void
                                                                            // CTRL7 aEN / gEN / gSN
            imu.configureFifo(2, 1, 16); // Configure FIFO, (mode, size, watermark) → void
                                                                            // stream mode, 32 samples, watermark 16
            Thread.sleep(500);
            int fifoFlags = imu.fifoStatus(); // Read FIFO flags, () → int
                                                                            // raw FIFO_STATUS byte
            int fifoBytes = imu.fifoCount(); // Read FIFO fill, () → int bytes
                                                                            // two bytes per sample word
            byte[] data = imu.readFifo(-1); // Drain the FIFO, (count) → byte[]
                                                                            // REQ_FIFO, chunked FIFO_DATA reads, FIFO_RD_MODE cleared
            imu.resetFifo(); // Reset the FIFO, () → void
                                                                            // CTRL9 RST_FIFO
            imu.configureFifo(0, 3, 0); // Back to bypass, (mode, size, watermark) → void
                                                                            // bypass re-enables data-ready
            imu.configureAnyMotion(0.25, 3, 0x07, false); // Configure any-motion, (thresholdG, window, axes, logicAnd) → void
                                                                            // two CTRL9 CONFIGURE_MOTION sets
            imu.configureNoMotion(0.125, 30, 0x07, true); // Configure no-motion, (thresholdG, window, axes, logicAnd) → void
                                                                            // same command, no-motion fields
            imu.configureSignificantMotion(150, 600); // Configure significant motion, (waitWindow, confirmWindow) → void
                                                                            // wait and confirm windows in samples
            imu.enableMotion(true, true, true); // Enable motion engines, (anyMotion, noMotion, significant) → void
                                                                            // CTRL8 bits 1–3
            imu.enableMotion(false, false, false); // Disable motion engines, (anyMotion, noMotion, significant) → void
                                                                            // clears CTRL8 bits 1–3
            imu.configureTap(0, 20, 50, 250, 0.0625, 0.25, 0.8, 0.4); // Configure tap engine, (priority, peakWindow, tapWindow, doubleTapWindow, alpha, gamma, peakThreshold, quietThreshold) → void
                                                                            // two CTRL9 CONFIGURE_TAP sets
            imu.enableTap(true); // Enable tap engine, (enabled) → void
                                                                            // CTRL8.Tap_EN
            var tap = imu.tapStatus(); // Read tap event, () → TapStatus (count, axis, negative)
                                                                            // count 0/1/2, axis 0–3, polarity
            imu.enableTap(false); // Disable tap engine, (enabled) → void
                                                                            // clears CTRL8.Tap_EN
            imu.configurePedometer(50, 200.0, 100.0, 200, 20, 10, 0, 4); // Configure pedometer, (sampleCount, peakToPeakMg, peakMg, timeUp, timeLow, entryCount, precision, reportEvery) → void
                                                                            // two CTRL9 CONFIGURE_PEDOMETER sets (50 Hz values)
            imu.enablePedometer(true); // Enable pedometer, (enabled) → void
                                                                            // CTRL8.Pedo_EN; 0→1 resets the count
            int steps = imu.stepCount(); // Read step count, () → int
                                                                            // 24-bit count
            imu.resetStepCount(); // Clear step count, () → void
                                                                            // CTRL9 RESET_PEDOMETER
            imu.enablePedometer(false); // Disable pedometer, (enabled) → void
                                                                            // clears CTRL8.Pedo_EN
            imu.setAccelOffset(0.0, 0.0, 0.0); // Set accel offset, (x, y, z m/s²) → void
                                                                            // signed 4.12 g via CTRL9
            imu.setGyroOffset(0.0, 0.0, 0.0); // Set gyro offset, (x, y, z rad/s) → void
                                                                            // signed 11.5 dps via CTRL9
            var cal = imu.calibrateGyro(); // Calibrate gyroscope, () → Calibration (success, codStatus)
                                                                            // calibration-on-demand, ~1.5 s, keep still
            int[] gains = imu.getGyroGains(); // Read gyro gains, () → int[]
                                                                            // raw 16-bit gains from dVX…dVZ
            imu.setGyroGains(gains[0], gains[1], gains[2]); // Restore gyro gains, (x, y, z) → void
                                                                            // CTRL9 APPLY_GYRO_GAINS
            var accelTest = imu.selfTestAccel(); // Run accel self-test, () → SelfTestResult (passed, values m/s²)
                                                                            // passes when every axis exceeds 200 mg
            var gyroTest = imu.selfTestGyro(); // Run gyro self-test, () → SelfTestResult (passed, values rad/s)
                                                                            // passes when every axis exceeds 300 dps
            var info = imu.deviceInfo(); // Read device info, () → DeviceInfo (revision, firmware, usid)
                                                                            // CTRL9 COPY_USID: revision, firmware, usid
            imu.setPullups(true, true, true, true); // Control pull-ups, (aux, sdx, cs, i2c) → void
                                                                            // CTRL9 SET_RPU; false disables a group
            imu.setWakeOnMotion(100, 1, 0, 0); // Arm wake-on-motion, (thresholdMg, interruptPin, initialLevel, blankingSamples) → void
                                                                            // accel 128 Hz ±2 g, CTRL9 WRITE_WOM_SETTING
            imu.setWakeOnMotion(0, 1, 0, 0); // Leave wake-on-motion, (thresholdMg, interruptPin, initialLevel, blankingSamples) → void
                                                                            // threshold 0 exits WoM mode
            imu.setSensors(true, true, false); // Re-enable sensors, (accel, gyro, gyroSnooze) → void
                                                                            // restores normal operation
            imu.setInterruptPins(2, 2); // Route interrupts, (fifoPin, activityPin) → void
                                                                            // CTRL1.FIFO_INT_SEL and CTRL8.ACTIVITY_INT_SEL
            imu.enableInterrupt(QMI8658AFull.SOURCE_DATA_READY); // Enable interrupt source, (source) → void
                                                                            // clears DRDY_DIS, enables INT2
            int status = imu.pollInterrupt(); // Poll interrupt status, () → int
                                                                            // STATUS0, STATUS1 and FIFO_STATUS as SOURCE_* bits
            imu.disableInterrupt(QMI8658AFull.SOURCE_DATA_READY); // Disable interrupt source, (source) → void
                                                                            // sets DRDY_DIS, releases INT2
            imu.setSyncSample(true); // Enable SyncSample, (enabled) → void
                                                                            // locks each sample; reads wait for Avail and Locked
            imu.setSyncSample(false); // Disable SyncSample, (enabled) → void
                                                                            // restores the AHB clock gating
            imu.setPowerDown(true); // Power down, (down) → void
                                                                            // CTRL1.SensorDisable after disabling the sensors
            imu.setPowerDown(false); // Wake, (down) → void
                                                                            // clears SensorDisable
            imu.setSensors(true, true, false); // Re-enable sensors, (accel, gyro, gyroSnooze) → void
                                                                            // needs 150 ms gyroscope turn-on
            Thread.sleep(200);
            imu.reset(); // Soft reset, () → void
                                                                            // RESET register, waits 15 ms
            System.out.printf("%s%n", java.util.Arrays.toString(a) + java.util.Arrays.toString(g) + sample.tempC + t + ts + ready + rawA.length + rawG.length + fifoFlags + fifoBytes + data.length + tap.count + steps + cal.success + accelTest.passed + gyroTest.passed + info.revision + status);
            System.out.println("Complete example done");
        }
    }
}
