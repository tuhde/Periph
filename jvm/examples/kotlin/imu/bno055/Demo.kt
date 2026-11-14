///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.imu.BNO055Full
import java.util.function.IntConsumer

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x29").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val imu = BNO055Full(connection)                                                    // Create BNO055 driver, (connection) → void

        // --- Identify the part ---
        val rev = imu.revisions()                                                           // Read revisions, () → int[]
        println("chip 0x%02X, SW %d.%d, bootloader %d".format(rev[0], rev[4] >> 8, rev[4] & 0xFF, rev[5]))
        println("unique id %s".format(java.util.HexFormat.of().formatHex(imu.uniqueId())))
        // --- Orientation monitor until fully calibrated (move the board in a figure eight) ---
        while (true) {
            val eul = imu.euler()                                                           // Read fused Euler angles, () → double[] rad
            val q = imu.quaternion()                                                        // Read orientation quaternion, () → double[]
            val lin = imu.linearAcceleration()                                              // Read gravity-free acceleration, () → double[] m/s²
            val grav = imu.gravity()                                                        // Read gravity vector, () → double[] m/s²
            val cal = imu.calibrationStatus()                                               // Read calibration status, () → int[]
            println("euler %s quat %s lin %s grav %s cal %s".format(java.util.Arrays.toString(eul), java.util.Arrays.toString(q), java.util.Arrays.toString(lin), java.util.Arrays.toString(grav), java.util.Arrays.toString(cal)))
            if (imu.isCalibrated()) break
            Thread.sleep(100)
        }
        // --- Save the calibration, relative mode for 5 s, restore it ---
        val blob = imu.getCalibration()                                                     // Read offsets and radii, () → byte[22]
        println("calibration %s".format(java.util.HexFormat.of().formatHex(blob)))
        imu.setMode(BNO055Full.MODE_IMU)                                                    // Select operation mode, (mode) → void
        println("mode %d".format(imu.getMode()))
        Thread.sleep(5000)
        imu.setMode(BNO055Full.MODE_NDOF)                                                   // Select operation mode, (mode) → void
        imu.setCalibration(blob)                                                            // Restore offsets and radii, (data) → void
        // --- Self-test, temperature and configuration ---
        println("self-test %s".format(java.util.Arrays.toString(imu.selfTest())))
        println("temperature %d C".format(imu.temperature()))
        imu.setTemperatureSource("gyroscope")                                               // Select temperature sensor, (source) → void
        imu.setAxisRemap(0x24, 0x00)                                                        // Remap axes, (config, sign) → void
        imu.setPowerMode("normal")                                                          // Select power mode, (mode) → void
        imu.useExternalCrystal(false)                                                       // Select clock source, (on) → void
        // --- Raw sensors in AMG mode ---
        imu.setMode(BNO055Full.MODE_AMG)                                                    // Select operation mode, (mode) → void
        val acc = imu.acceleration()                                                        // Read raw accelerometer, () → double[] m/s²
        val mag = imu.magneticField()                                                       // Read raw magnetometer, () → double[] µT
        val gyr = imu.angularRate()                                                         // Read raw gyroscope, () → double[] rad/s
        println("acc %s mag %s gyr %s".format(java.util.Arrays.toString(acc), java.util.Arrays.toString(mag), java.util.Arrays.toString(gyr)))
        // --- Interrupts: accelerometer/fusion data ready ---
        imu.setMode(BNO055Full.MODE_NDOF)                                                   // Select operation mode, (mode) → void
        imu.enableInterrupt(BNO055Full.SOURCE_ACC_BSX_DRDY)                                 // Enable interrupt source, (source) → void
        imu.onInterrupt(IntConsumer { s -> println("INT 0x%02X".format(s)) })               // Subscribe to interrupts, (callback) → void
        Thread.sleep(500)
        imu.offInterrupt()                                                                  // Unsubscribe, () → void
        imu.disableInterrupt(BNO055Full.SOURCE_ACC_BSX_DRDY)                                // Disable interrupt source, (source) → void
        println("interrupt status 0x%02X".format(imu.pollInterrupt()))
        println("system status %s".format(java.util.Arrays.toString(imu.systemStatus())))
        imu.reset()                                                                         // Reset the chip, () → void
    }
}
