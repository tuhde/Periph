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

        val eul = imu.euler()                                                               // Read fused Euler angles, () → double[] rad
                                                                                            // burst read of 0x1A…0x1F, raw / 900
        val q = imu.quaternion()                                                            // Read orientation quaternion, () → double[] (w, x, y, z)
                                                                                            // raw / 16384
        val lin = imu.linearAcceleration()                                                  // Read gravity-free acceleration, () → double[] m/s²
                                                                                            // raw / 100
        val grav = imu.gravity()                                                            // Read gravity vector, () → double[] m/s²
                                                                                            // raw / 100
        val acc = imu.acceleration()                                                        // Read raw accelerometer, () → double[] m/s²
                                                                                            // raw / 100
        val mag = imu.magneticField()                                                       // Read raw magnetometer, () → double[] µT
                                                                                            // raw / 16
        val gyr = imu.angularRate()                                                         // Read raw gyroscope, () → double[] rad/s
                                                                                            // raw / 900
        val t = imu.temperature()                                                           // Read temperature, () → int °C
                                                                                            // signed 8 bit, 1 LSB = 1 °C
        val cal = imu.calibrationStatus()                                                   // Read calibration status, () → int[] (sys, gyr, acc, mag)
                                                                                            // each 0 (uncalibrated) … 3 (calibrated)
        val done = imu.isCalibrated()                                                       // Check full calibration, () → boolean
                                                                                            // true when all four values are 3
        println("euler %s quat %s lin %s grav %s".format(java.util.Arrays.toString(eul), java.util.Arrays.toString(q), java.util.Arrays.toString(lin), java.util.Arrays.toString(grav)))
        println("acc %s mag %s gyr %s t %d cal %s done %s".format(java.util.Arrays.toString(acc), java.util.Arrays.toString(mag), java.util.Arrays.toString(gyr), t, java.util.Arrays.toString(cal), done))
        val mode = imu.getMode()                                                            // Read operation mode, () → int
                                                                                            // 0x0C = NDOF
        imu.setMode(BNO055Full.MODE_IMU)                                                    // Select operation mode, (mode) → void
                                                                                            // via CONFIGMODE, 19 ms + 7 ms waits
        imu.setMode(BNO055Full.MODE_NDOF)                                                   // Select operation mode, (mode) → void
                                                                                            // back to 9-DOF fusion
        val blob = imu.getCalibration()                                                     // Read offsets and radii, () → byte[22]
                                                                                            // 0x55…0x6A, read in CONFIGMODE
        imu.setCalibration(blob)                                                            // Restore offsets and radii, (data) → void
                                                                                            // 22 bytes, written in CONFIGMODE
        imu.setAxisRemap(0x24, 0x00)                                                        // Remap axes, (config, sign) → void
                                                                                            // 0x24/0x00 = identity placement
        imu.setPowerMode("normal")                                                          // Select power mode, (mode) → void
                                                                                            // "normal", "low_power" or "suspend"
        imu.setTemperatureSource("accelerometer")                                           // Select temperature sensor, (source) → void
                                                                                            // "accelerometer" or "gyroscope"
        imu.useExternalCrystal(false)                                                       // Select clock source, (on) → void
                                                                                            // internal oscillator unless a crystal is fitted
        val st = imu.selfTest()                                                             // Run self-test, () → boolean[] (acc, mag, gyr, mcu)
                                                                                            // true = passed, mode restored afterwards
        val ss = imu.systemStatus()                                                         // Read system status, () → int[] (status, error)
                                                                                            // SYS_STATUS 5 = fusion running, SYS_ERR 0 = none
        val rev = imu.revisions()                                                           // Read revisions, () → int[] (chipId, accId, magId, gyrId, swRevision, bootloader)
                                                                                            // identification registers 0x00…0x06
        val uid = imu.uniqueId()                                                            // Read unique ID, () → byte[16]
                                                                                            // 16 bytes from page 1
        println("mode %d self-test %s status %s revisions %s uid %s".format(mode, java.util.Arrays.toString(st), java.util.Arrays.toString(ss), java.util.Arrays.toString(rev), java.util.HexFormat.of().formatHex(uid)))
        imu.enableInterrupt(BNO055Full.SOURCE_ACC_BSX_DRDY)                                 // Enable interrupt source, (source) → void
                                                                                            // INT_EN and INT_MSK on page 1
        val ints = imu.pollInterrupt()                                                      // Poll interrupt status, () → int
                                                                                            // INT_STA, cleared on read
        imu.onInterrupt(IntConsumer { s -> println("INT 0x%02X".format(s)) })               // Subscribe to interrupts, (callback) → void
                                                                                            // uses an INT pin or a 100 ms polling thread
        Thread.sleep(500)
        imu.offInterrupt()                                                                  // Unsubscribe, () → void
                                                                                            // stops the callback
        imu.disableInterrupt(BNO055Full.SOURCE_ACC_BSX_DRDY)                                // Disable interrupt source, (source) → void
                                                                                            // clears the INT_EN and INT_MSK bits
        println("interrupt status 0x%02X".format(ints))
        imu.reset()                                                                         // Reset the chip, () → void
                                                                                            // RST_SYS, waits 650 ms, re-enters NDOF
    }
}
