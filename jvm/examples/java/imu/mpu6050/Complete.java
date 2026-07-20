///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.imu.Mpu6050Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x68)) {
            var imu = new Mpu6050Full(transport);                  // Create MPU6050 driver, (transport, addr=0x68) → None

            double[] a = imu.accel();                              // Read 3-axis acceleration, () → double[] m/s²
                                                                   // converts raw accel register to m/s² (16384 LSB/g at ±2g)
            double[] g = imu.gyro();                               // Read 3-axis angular rate, () → double[] rad/s
                                                                   // converts raw gyro register to rad/s (131.0 LSB/(°/s) at ±250dps)

            imu.configureGyro(1);                                   // Configure gyro range, (fullScale=0) → None
                                                                   // sets FS_SEL: 0=±250, 1=±500, 2=±1000, 3=±2000 dps
            imu.configureAccel(1);                                  // Configure accel range, (fullScale=0) → None
                                                                   // sets AFS_SEL: 0=±2g, 1=±4g, 2=±8g, 3=±16g
            imu.configureDlpf(3);                                   // Configure DLPF bandwidth, (dlpf=3) → None
                                                                   // sets DLPF_CFG: 0=260Hz … 6=5Hz (gyro/accel BW)
            imu.configureSampleRate(4);                             // Configure sample rate, (divider=4) → None
                                                                   // sets SMPLRT_DIV: output rate = 1kHz / (1 + divider)

            System.out.printf("temp: %.1f C%n", imu.temperature());// Read die temperature, () → double °C

            int[] ra = imu.accelRaw();                              // Read raw accel values, () → int[]
            int[] rg = imu.gyroRaw();                               // Read raw gyro values, () → int[]

            System.out.println("data_ready: " + imu.dataReady());   // Check data ready flag, () → bool

            imu.setSleep(true);                                     // Enter sleep mode, (sleep=true) → None
            Thread.sleep(10);
            imu.setSleep(false);                                    // Wake from sleep, (sleep=true) → None
            Thread.sleep(50);

            imu.setStandby(true, false, false, false, false, false);// Set axes standby, (xa, ya, za, xg, yg, zg) → None
            imu.setStandby(false, false, false, false, false, false);// Clear all standby, (xa=false, ...) → None

            imu.resetFifo();                                        // Reset FIFO buffer, () → None
            imu.enableFifo(true, true, false);                      // Enable FIFO sources, (gyro=true, accel=true, temp=false) → None
            Thread.sleep(50);
            System.out.println("fifo_count: " + imu.fifoCount());   // Read FIFO byte count, () → int
            byte[] data = imu.readFifo();                           // Read FIFO data, () → byte[]
            System.out.println("fifo read: " + data.length + " bytes");
            imu.resetFifo();                                        // Reset FIFO buffer, () → None
        }
    }
}
