///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.2
//DEPS it.uhde:periph-java:1.3.2

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.imu.ICM20948Full;

public class Complete {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x68").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var imu = new ICM20948Full(connection);                           // Create ICM20948 driver, (connection) → void

            double[] a = imu.accel();                       // Read 3-axis acceleration, () → double[] m/s²
                                                                // converts raw accel registers to m/s² (16384 LSB/g at ±2g)
            double[] g = imu.gyro();                        // Read 3-axis angular rate, () → double[] rad/s
                                                                // converts raw gyro registers to rad/s (131 LSB/(°/s) at ±250dps)
            var s = imu.readAll();                          // Read one coherent sample, () → Sample (accel m/s², gyro rad/s, tempC °C)
                                                                // burst-reads registers 0x2D…0x3A so all three belong to one sample
            double t = imu.temperature();                   // Read die temperature, () → double °C
                                                                // converts raw temp register: raw/333.87 + 21.0
            int[] ra = imu.accelRaw();                      // Read raw accel values, () → int[]
                                                                // returns signed 16-bit accelerometer counts
            int[] rg = imu.gyroRaw();                       // Read raw gyro values, () → int[]
                                                                // returns signed 16-bit gyroscope counts

            imu.configureGyro(1);                           // Configure gyro range, (fullScale) → void
                                                                // sets GYRO_FS_SEL: 0=±250, 1=±500, 2=±1000, 3=±2000 dps
            imu.configureAccel(1);                          // Configure accel range, (fullScale) → void
                                                                // sets ACCEL_FS_SEL: 0=±2g, 1=±4g, 2=±8g, 3=±16g
            imu.configureFilters(3, 3, true, true);         // Configure low-pass filters, (gyroDlpf, accelDlpf, gyroFilter, accelFilter) → void
                                                                // sets *_DLPFCFG and *_FCHOICE; filter false bypasses the filter
            imu.configureSampleRate(10, 10);                // Configure sample rate, (gyroDiv, accelDiv) → void
                                                                // sets the dividers; ODR = 1125 Hz / (1 + div)
            imu.setSensors(true, true);                     // Power sensors, (accel, gyro) → void
                                                                // writes PWR_MGMT_2 DISABLE_ACCEL / DISABLE_GYRO
            imu.setLowPower(false, false, 0, 0);            // Configure duty-cycled mode, (accel, gyro, accelAvg, gyroAvg) → void
                                                                // writes LP_CONFIG and averaging; LP_EN is set last
            imu.setGyroOffset(0.0, 0.0, 0.0);               // Set gyro offsets, (x, y, z rad/s) → void
                                                                // writes the 16-bit offset-cancellation registers (0.0305 dps/LSB)
            int[] off = imu.getAccelOffset();               // Read accel offset codes, () → int[]
                                                                // reads the factory-trimmed 15-bit offsets (0.98 mg/LSB)
            imu.setAccelOffset(off[0], off[1], off[2]);     // Write accel offset codes, (x, y, z) → void
                                                                // writes the 15-bit offsets back unchanged

            imu.enableMag();                                // Enable magnetometer, () → void
                                                                // starts the I²C master and reads the AK09916 block continuously
            double[] m = imu.mag();                         // Read 3-axis magnetic field, () → double[] µT
                                                                // converts raw counts at 0.15 µT/LSB; throws on HOFL
            int[] rm = imu.magRaw();                        // Read raw mag values, () → int[]
                                                                // returns signed 16-bit magnetometer counts
            boolean magReady = imu.magDataReady();          // Check magnetometer data ready, () → boolean
                                                                // reads ST1.DRDY from the shadowed block
            imu.magSetMode(8);                              // Set magnetometer mode, (mode) → void
                                                                // writes CNTL2: 0=off, 1=single, 2/4/6/8=continuous 1-4
            int who = imu.auxReadByte(0x0C, 0x01);          // Read auxiliary byte, (address, register) → int
                                                                // reads AK09916 WIA2 (0x09) through I²C-master slave 4
            imu.auxWriteByte(0x0C, 0x31, 0x08);             // Write auxiliary byte, (address, register, value) → void
                                                                // writes AK09916 CNTL2 through slave 4

            boolean ready = imu.dataReady();                // Check data ready flag, () → boolean
                                                                // reads INT_STATUS_1.RAW_DATA_0_RDY_INT

            imu.enableFifo(true, true, false);              // Enable FIFO sources, (accel, gyro, temp) → void
                                                                // sets FIFO_EN_2 and USER_CTRL.FIFO_EN, then resets the FIFO
            imu.setFifoMode(false);                         // Select FIFO mode, (snapshot) → void
                                                                // false = stream (overwrite oldest), true = snapshot (stop when full)
            Thread.sleep(50);
            int count = imu.fifoCount();                    // Read FIFO byte count, () → int
                                                                // reads FIFO_COUNTH/L: number of bytes available
            byte[] data = imu.readFifo(-1);                 // Read FIFO data, (count; <0 = all) → byte[]
                                                                // reads available bytes from FIFO_R_W in chunks
            imu.resetFifo();                                // Reset FIFO buffer, () → void
                                                                // asserts and de-asserts FIFO_RST

            imu.setWakeOnMotion(200.0, true);               // Arm wake-on-motion, (thresholdMg, enabled) → void
                                                                // writes ACCEL_WOM_THR (4 mg/LSB) and ACCEL_INTEL_CTRL
            imu.setInterruptPin(false, false, true, false); // Configure INT1 pin, (activeLow, openDrain, latch, anyReadClears) → void
                                                                // writes INT_PIN_CFG
            imu.enableInterrupt(ICM20948Full.SOURCE_DATA_READY); // Enable interrupt source, (source) → void
                                                                // sets the source's enable bit in INT_ENABLE*
            int status = imu.pollInterrupt();               // Read interrupt status, () → int
                                                                // reads INT_STATUS, _1, _2 (clearing them) into SOURCE_* bits
            imu.disableInterrupt(ICM20948Full.SOURCE_DATA_READY); // Disable interrupt source, (source) → void
                                                                // clears the source's enable bit
            imu.onInterrupt(st -> System.out.println("INT " + st)); // Subscribe to INT1, (callback) → void
                                                                // callback receives the status from pollInterrupt
            imu.offInterrupt();                             // Unsubscribe, () → void
                                                                // removes the callback

            double[][] st = imu.selfTest();                 // Run self-test, () → double[][] {accel, gyro}
                                                                // raw response (enabled minus disabled) per axis; no pass/fail
            double tbc = imu.readTimebaseCorrection();      // Read PLL time-base correction, () → double %
                                                                // reads TIMEBASE_CORRECTION_PLL (0.079 %/LSB, signed)

            imu.setSleep(true);                             // Enter sleep mode, (sleep) → void
                                                                // sets SLEEP bit in PWR_MGMT_1
            Thread.sleep(10);
            imu.setSleep(false);                            // Wake from sleep, (sleep) → void
                                                                // clears SLEEP bit in PWR_MGMT_1

            System.out.printf("accel=(%.2f %.2f %.2f) m/s2  gyro=(%.3f %.3f %.3f) rad/s%n", a[0], a[1], a[2], g[0], g[1], g[2]);
            System.out.printf("temperature=%.2f C  mag=(%.1f %.1f %.1f) uT  ready=%b%n", t, m[0], m[1], m[2], ready);
            imu.reset();                                    // Reset the device, () → void
                                                                // DEVICE_RESET and wait; construct a new driver afterwards
        }
    }
}
