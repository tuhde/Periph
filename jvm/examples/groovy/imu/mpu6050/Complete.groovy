///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.imu.Mpu6050Full

def transport = new I2CTransport(1, 0x68)
try {
    def imu = new Mpu6050Full(transport)                           // Create MPU6050 driver, (transport, addr=0x68) → None

    double[] a = imu.accel()                                       // Read 3-axis acceleration, () → double[] m/s²
                                                                   // converts raw accel register to m/s² (16384 LSB/g at ±2g)
    double[] g = imu.gyro()                                        // Read 3-axis angular rate, () → double[] rad/s
                                                                   // converts raw gyro register to rad/s (131.0 LSB/(°/s) at ±250dps)

    imu.configureGyro(1)                                            // Configure gyro range, (fullScale=0) → None
    imu.configureAccel(1)                                           // Configure accel range, (fullScale=0) → None
    imu.configureDlpf(3)                                            // Configure DLPF bandwidth, (dlpf=3) → None
    imu.configureSampleRate(4)                                      // Configure sample rate, (divider=4) → None

    println "temp: ${String.format('%.1f', imu.temperature())} C"  // Read die temperature, () → double °C

    int[] ra = imu.accelRaw()                                       // Read raw accel values, () → int[]
    int[] rg = imu.gyroRaw()                                        // Read raw gyro values, () → int[]

    println "data_ready: ${imu.dataReady()}"                        // Check data ready flag, () → boolean

    imu.setSleep(true)                                              // Enter sleep mode, (sleep=true) → None
    Thread.sleep(10)
    imu.setSleep(false)                                             // Wake from sleep, (sleep=true) → None
    Thread.sleep(50)

    imu.setStandby(true, false, false, false, false, false)         // Set axes standby, (xa, ya, za, xg, yg, zg) → None
    imu.setStandby()                                                // Clear all standby, (xa=false, ...) → None

    imu.resetFifo()                                                 // Reset FIFO buffer, () → None
    imu.enableFifo(true, true, false)                               // Enable FIFO sources, (gyro=true, accel=true, temp=false) → None
    Thread.sleep(50)
    println "fifo_count: ${imu.fifoCount()}"                        // Read FIFO byte count, () → int
    byte[] data = imu.readFifo()                                    // Read FIFO data, () → byte[]
    println "fifo read: ${data.length} bytes"
    imu.resetFifo()                                                 // Reset FIFO buffer, () → None
} finally {
    transport.close()
}
