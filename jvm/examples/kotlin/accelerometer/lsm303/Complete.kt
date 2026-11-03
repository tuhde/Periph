///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.LSM303Full
import it.uhde.periph.chips.accelerometer.LSM303Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1
    I2CConnection(bus, LSM303Full.I2C_ADDRESS_ACCEL).use { accelConn ->
        I2CConnection(bus, LSM303Full.I2C_ADDRESS_MAG).use { magConn ->
            val compass = LSM303Full(accelConn, magConn)       // Create LSM303 driver, (accel_connection, mag_connection)

            // --- Accelerometer readings ---
            val accel = compass.readAcceleration()             // Read acceleration, () → [g, g, g]
            println("accel x=%.4f y=%.4f z=%.4f g".format(accel[0], accel[1], accel[2]))

            val rawAccel = compass.readAccelerationRaw()       // Read raw acceleration, () → [counts, counts, counts]
            println("raw accel ${rawAccel[0]} ${rawAccel[1]} ${rawAccel[2]}")

            val status = compass.readStatus()                 // Read status register, () → byte
            println("status 0x%02X".format(status))

            val newData = compass.newDataAvailable()           // Check new data, () → bool
            println("new data $newData")

            // --- Accelerometer configuration ---
            compass.setRange(4)                               // Set full-scale range, (range_g=4) → None
            compass.setDataRate(50)                           // Set output data rate, (rate_hz=50) → None
            compass.setMode(LSM303Minimal.MODE_HIGH_RESOLUTION)  // Set operating mode, (mode=MODE_HIGH_RESOLUTION=2) → None
            compass.setAxes(true, true, true)                 // Enable axes, (x=true, y=true, z=true) → None

            // --- High-pass filter ---
            compass.setHighPass(0, 0, false, true, false, false)   // Configure high-pass filter, (mode=0, cutoff=0, filter_output=false, on_ia1=true, on_ia2=false, on_click=false) → None
            println("reference ${compass.resetHighPass()}")   // Reset high-pass filter, () → byte
            compass.setReference(0)                           // Set reference value, (value=0) → None

            // --- FIFO ---
            compass.setFifo(LSM303Full.FIFO_STREAM, 16, false)     // Configure FIFO, (mode=FIFO_STREAM=2, watermark=16, trigger_int2=false) → None
            println("fifo level ${compass.fifoLevel()}")            // Read FIFO level, () → byte
            val fs = compass.readFifoStatus()                 // Read FIFO status, () → [watermark, overrun, empty, level]
            println("fifo wm=${fs[0]} ov=${fs[1]} empty=${fs[2]} level=${fs[3]}")
            val samples = compass.readFifo(8)                  // Read FIFO, (count=8) → [[g, g, g], ...]
            println("fifo samples ${samples.size}")

            // --- Inertial interrupt generators ---
            compass.setMotionInterrupt(1, 0x02, 0.5, 10.0, false)   // Configure motion interrupt, (generator=1, events=0x02, threshold_g=0.5, duration_ms=10, and_mode=false) → None
            compass.setFreeFall(1, 0.35, 30.0)                   // Arm free-fall, (generator=1, threshold_g=0.35, duration_ms=30) → None
            compass.setOrientation(1, false, false)            // Configure orientation, (generator=1, position=false, four_d=false) → None
            compass.setLatch(1, true)                          // Latch the interrupt, (generator=1, enabled=true) → None
            println("int1 source 0x%02X".format(compass.readInterruptSource(1)))  // Read interrupt source, (generator=1) → byte

            // --- Click detection ---
            compass.setClick(0, 0x04, 0.8, 20.0, 40.0, 200.0)       // Configure click detector, (single=0, double=0x04, threshold_g=0.8, time_limit_ms=20, latency_ms=40, window_ms=200) → None
            println("click source 0x%02X".format(compass.readClickSource()))     // Read click source, () → byte

            // --- Interrupt routing ---
            compass.setInterruptPolarity(false)                // Set interrupt polarity, (active_low=false) → None
            compass.enableInterrupt(LSM303Full.SOURCE_CLICK, 1)    // Route an interrupt, (source=SOURCE_CLICK=0x20, pin=1) → None
            compass.disableInterrupt(LSM303Full.SOURCE_CLICK, 1)   // Remove an interrupt, (source=SOURCE_CLICK=0x20, pin=1) → None

            // --- Magnetometer ---
            compass.setMagRange(250)                           // Set magnetometer range, (range_ut=250) → None
            compass.setMagDataRate(30.0)                         // Set magnetometer data rate, (rate_hz=30) → None
            compass.setMagMode(LSM303Full.MAG_MODE_CONTINUOUS)      // Set magnetometer mode, (mode=MAG_MODE_CONTINUOUS=0) → None
            val rawMag = compass.readMagneticRaw()              // Read raw magnetic field, () → [counts, counts, counts]
            println("raw mag ${rawMag[0]} ${rawMag[1]} ${rawMag[2]}")
            val ms = compass.readMagStatus()                    // Read magnetometer status, () → [drdy, lock]
            println("mag drdy=${ms[0]} lock=${ms[1]}")
            val identity = compass.readMagIdentity()             // Read magnetometer identity, () → [byte, byte, byte]
            println("identity %02X%02X%02X".format(identity[0], identity[1], identity[2]))
            compass.enableTemperature(true)                    // Enable the temperature sensor, (enabled=true) → None
            println("temperature %.2f C".format(compass.readTemperatureDelta()))  // Read relative temperature, () → °C

            // --- Compass heading ---
            println("heading %.1f deg".format(compass.heading(true)))       // Read the compass heading, (tilt_compensate=true) → °
            println("flat heading %.1f deg".format(compass.heading(false)))  // Read the compass heading, (tilt_compensate=false) → °

            // --- Miscellaneous ---
            compass.reboot()                                   // Reboot the accelerometer, () → None
        }
    }
}
