///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.imu.MPU9250Full;

public class Complete {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x68").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr);
             var magConnection = new I2CConnection(bus, 0x0C)) {              // AK8963, same bus, reached via I²C bypass
            var imu = new MPU9250Full(connection, magConnection);              // Create MPU9250 driver, (connection, magConnection) → void

            double[] a = imu.accel();                                          // Read 3-axis acceleration, () → double[] m/s²
                                                    // converts raw accel register to m/s² (16384 LSB/g at ±2g)
            double[] g = imu.gyro();                                           // Read 3-axis angular rate, () → double[] rad/s
                                                    // converts raw gyro register to rad/s (131.0 LSB/(°/s) at ±250dps)

            imu.configureGyro(1);                                              // Configure gyro range, (fullScale=0) → void
                                                    // sets GYRO_FS_SEL: 0=±250, 1=±500, 2=±1000, 3=±2000 dps
            imu.configureAccel(1);                                             // Configure accel range, (fullScale=0) → void
                                                    // sets ACCEL_FS_SEL: 0=±2g, 1=±4g, 2=±8g, 3=±16g
            imu.configureDlpf(3, 3);                                           // Configure DLPF bandwidth, (gyroDlpf=3, accelDlpf=3) → void
                                                    // sets DLPF_CFG/A_DLPFCFG: 0=256/460Hz … 6=5/10Hz (gyro/accel BW)
            imu.configureSampleRate(4);                                        // Configure sample rate, (divider=4) → void
                                                    // sets SMPLRT_DIV: output rate = 1kHz / (1 + divider)

            double t = imu.temperature();                                      // Read die temperature, () → double °C
                                                    // converts raw temp register: raw/333.87 + 21.0

            imu.enableMag(16, 6);                                              // Initialize magnetometer, (bits=16, mode=6) → void
                                                    // reads ASA calibration, sets 16-bit 100 Hz continuous mode
            double[] m = imu.mag();                                            // Read 3-axis magnetic field, () → double[] µT
                                                    // applies factory ASA calibration and sensitivity scaling

            int[] ra = imu.accelRaw();                                         // Read raw accel values, () → int[]
                                                    // returns raw 16-bit signed accelerometer register values
            int[] rg = imu.gyroRaw();                                          // Read raw gyro values, () → int[]
                                                    // returns raw 16-bit signed gyroscope register values
            int[] rm = imu.magRaw();                                           // Read raw mag values, () → int[]
                                                    // returns raw 16-bit signed magnetometer register values

            boolean ready = imu.dataReady();                                   // Check data ready flag, () → boolean
                                                    // reads RAW_DATA_RDY_INT bit from INT_STATUS register

            imu.setSleep(true);                                                // Enter sleep mode, (sleep=true) → void
                                                    // sets SLEEP bit in PWR_MGMT_1
            Thread.sleep(10);
            imu.setSleep(false);                                               // Wake from sleep, (sleep=true) → void
                                                    // clears SLEEP bit in PWR_MGMT_1

            imu.resetFifo();                                                   // Reset FIFO buffer, () → void
                                                    // sets FIFO_RST bit in USER_CTRL to clear the buffer
            imu.enableFifo(true, true, false);                                 // Enable FIFO sources, (gyro=true, accel=true, temp=false) → void
                                                    // configures FIFO_EN and sets FIFO_EN bit in USER_CTRL
            Thread.sleep(50);
            int count = imu.fifoCount();                                       // Read FIFO byte count, () → int
                                                    // reads FIFO_COUNTH/L: number of bytes available
            byte[] data = imu.readFifo();                                      // Read FIFO data, () → byte[]
                                                    // reads all available bytes from FIFO_R_W register
            imu.resetFifo();                                                   // Reset FIFO buffer, () → void
                                                    // sets FIFO_RST bit in USER_CTRL to clear the buffer
        }
    }
}