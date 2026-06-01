///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.imu.Mpu6050Full

fun main() {
    I2CTransport(1, 0x68).use { transport ->
        val imu = Mpu6050Full(transport)                           // Create MPU6050 driver, (transport, addr=0x68) → None

        val a = imu.accel()                                        // Read 3-axis acceleration, () → DoubleArray m/s²
                                                                   // converts raw accel register to m/s² (16384 LSB/g at ±2g)
        val g = imu.gyro()                                         // Read 3-axis angular rate, () → DoubleArray rad/s
                                                                   // converts raw gyro register to rad/s (131.0 LSB/(°/s) at ±250dps)

        imu.configureGyro(1)                                        // Configure gyro range, (fullScale=0) → None
        imu.configureAccel(1)                                       // Configure accel range, (fullScale=0) → None
        imu.configureDlpf(3)                                        // Configure DLPF bandwidth, (dlpf=3) → None
        imu.configureSampleRate(4)                                  // Configure sample rate, (divider=4) → None

        println("temp: %.1f C".format(imu.temperature()))           // Read die temperature, () → Double °C

        val ra = imu.accelRaw()                                     // Read raw accel values, () → IntArray
        val rg = imu.gyroRaw()                                      // Read raw gyro values, () → IntArray

        println("data_ready: ${imu.dataReady()}")                   // Check data ready flag, () → Boolean

        imu.setSleep(true)                                          // Enter sleep mode, (sleep=true) → None
        Thread.sleep(10)
        imu.setSleep(false)                                         // Wake from sleep, (sleep=true) → None
        Thread.sleep(50)

        imu.setStandby(xa = true)                                   // Set axes standby, (xa, ya, za, xg, yg, zg) → None
        imu.setStandby()                                            // Clear all standby, (xa=false, ...) → None

        imu.resetFifo()                                             // Reset FIFO buffer, () → None
        imu.enableFifo(gyro = true, accel = true)                   // Enable FIFO sources, (gyro, accel, temp) → None
        Thread.sleep(50)
        println("fifo_count: ${imu.fifoCount()}")                   // Read FIFO byte count, () → Int
        val data = imu.readFifo()                                   // Read FIFO data, () → ByteArray
        println("fifo read: ${data.size} bytes")
        imu.resetFifo()                                             // Reset FIFO buffer, () → None
    }
}
