///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.LSM303Full

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, 0x19).use { accelConn ->
        I2CConnection(bus, 0x1E).use { magConn ->
            val sensor = LSM303Full(accelConn, magConn)                 // construct driver and run init, (accelConnection, magConnection) → LSM303Full
                                                                        // verifies magnetometer ID 0x483433 and leaves ±2 g, 100 Hz, ±130 µT, 15 Hz

            sensor.setRange(4)                                          // Set full-scale range, (rangeG=2|4|8|16) → Unit g
                                                                        // selects ±4 g; the cached sensitivity updates to 2 mg/LSB
            sensor.setDataRate(50)                                      // Set accelerometer ODR, (rateHz) → Unit Hz
                                                                        // 50 Hz is valid in every mode
            sensor.setMode(2)                                           // Set accelerometer mode, (mode=0 low-power|1 normal|2 high-resolution) → Unit
                                                                        // LPen=0, HR=1; high-resolution 12-bit output
            sensor.setAxes(true, true, true)                            // Enable accelerometer axes, (x, y, z) → Unit
                                                                        // X, Y and Z enabled (Xen/Yen/Zen in CTRL_REG1_A)
            val a = sensor.readAcceleration()                           // Read acceleration, () → DoubleArray g
                                                                        // burst-reads OUT_X_L_A…OUT_Z_H_A with auto-increment
            val raw = sensor.readAccelerationRaw()                      // Read raw acceleration, () → IntArray counts
                                                                        // signed counts at the current resolution (raw16 >> 4 in HR mode)
            val fresh = sensor.newDataAvailable()                       // Check new sample, () → Boolean
                                                                        // true when STATUS_REG_A.ZYXDA is set
            val status = sensor.readStatus()                            // Read accelerometer status, () → Int
                                                                        // STATUS_REG_A byte: ZYXOR…XDA flags
            println("a=${a.toList()} raw=${raw.toList()} fresh=$fresh status=0x%02X".format(status))

            sensor.setHighPass(1, 0, false, true, false, false)         // Configure high-pass filter, (mode, cutoff, filterOutput, onIa1, onIa2, onClick) → Unit
                                                                        // reference-filter mode; filter applied to generator 1 only
            sensor.setReference(0x00)                                   // Set high-pass reference, (value=0–255) → Unit
                                                                        // REFERENCE_A value used when HPM = 01
            val ref = sensor.resetHighPass()                            // Read REFERENCE_A, () → Int
                                                                        // reading REFERENCE_A resets the filter in HPM = 00 mode
            println("reference=$ref")

            sensor.setFifo(2, 16, false)                                // Configure FIFO, (mode=0–3, watermark=0–31, triggerInt2=false) → Unit
                                                                        // stream mode with a 16-sample watermark; FIFO_EN set in CTRL_REG5_A
            Thread.sleep(300)
            val level = sensor.fifoLevel()                              // Read FIFO level, () → Int samples
                                                                        // FSS<4:0>, number of unread sample sets
            val samples = sensor.readFifo(4)                            // Read FIFO samples, (count=all) → Array<DoubleArray> g
                                                                        // reads up to 4 sets, one 6-byte burst each
            val fifoStatus = sensor.readFifoStatus()                    // Read FIFO status, () → IntArray (watermark, overrun, empty, level)
                                                                        // decodes FIFO_SRC_REG_A
            println("fifo level=$level read=${samples.size} status=${fifoStatus.toList()}")
            sensor.setFifo(0, 0, false)                                 // Return FIFO to bypass, (mode=0) → Unit
                                                                        // bypass clears the buffer; FIFO_EN cleared

            sensor.setMotionInterrupt(1, 0x3F, 1.0, 100.0, false)       // Configure motion interrupt, (generator, events, threshold_g, duration_ms, andMode) → Unit
                                                                        // OR of all six axis events, 1 g threshold, 100 ms minimum duration
            sensor.setFreeFall(2, 0.35, 30.0)                           // Configure free-fall, (generator, threshold_g, duration_ms) → Unit
                                                                        // AND of low X/Y/Z events on generator 2
            sensor.setOrientation(1, false, false)                      // Configure 6D orientation, (generator, position, fourD) → Unit
                                                                        // movement recognition on generator 1
            sensor.setLatch(1, true)                                    // Latch interrupt generator, (generator, enabled) → Unit
                                                                        // LIR_INT1 set: IA stays high until INT1_SOURCE_A is read
            val src = sensor.readInterruptSource(1)                     // Read interrupt source, (generator) → Int
                                                                        // INT1_SOURCE_A byte; clears the latched interrupt
            println("int1 source=0x%02X".format(src))

            sensor.setClick(0x04, 0x04, 0.8, 20.0, 40.0, 200.0)         // Configure click detection, (single, double, threshold_g, time_limit_ms, latency_ms, window_ms) → Unit
                                                                        // Z-axis single and double click, 0.8 g threshold
            val click = sensor.readClickSource()                        // Read click source, () → Int
                                                                        // CLICK_SRC_A byte; clears the click interrupt
            println("click source=0x%02X".format(click))

            sensor.setInterruptPolarity(false)                          // Set INT polarity, (activeLow) → Unit
                                                                        // H_LACTIVE=0: INT1/INT2 active high
            sensor.enableInterrupt(LSM303Full.SOURCE_CLICK, 1)          // Route interrupt source, (source, pin=1) → Unit
                                                                        // click routed to INT1 (CTRL_REG3_A I1_CLICK)
            sensor.enableInterrupt(LSM303Full.SOURCE_IA1, 2)            // Route interrupt source, (source, pin=2) → Unit
                                                                        // generator 1 routed to INT2 (CTRL_REG6_A I2_INT1)
            sensor.disableInterrupt(LSM303Full.SOURCE_IA1, 2)           // Remove interrupt source, (source, pin=2) → Unit
                                                                        // generator 1 removed from INT2

            sensor.onInterrupt({ s -> println("interrupt status=0x%02X".format(s)) }, 1) // Subscribe to INT1, (callback, pin=1) → Unit
                                                                        // no INT1 pin wired, so a 5 ms polling thread delivers events
            Thread.sleep(200)
            sensor.offInterrupt(1)                                      // Unsubscribe from INT1, (pin=1) → Unit
                                                                        // stops the polling thread
            val pending = sensor.pollInterrupt()                        // Poll interrupt sources, () → Int
                                                                        // status bitmask of enabled sources; clears latched generator and click flags
            println("pending=0x%04X".format(pending))

            sensor.setMagRange(810)                                     // Set magnetometer range, (rangeUt=130|190|250|400|470|560|810) → Unit
                                                                        // GN=111, ±8.1 gauss; cached gains updated
            sensor.setMagDataRate(15.0)                                 // Set magnetometer ODR, (rateHz) → Unit Hz
                                                                        // DO=100 (15 Hz); TEMP_EN kept
            sensor.setMagMode(0)                                        // Set magnetometer mode, (mode=0 continuous|1 single|2 sleep) → Unit
                                                                        // continuous conversion
            val magRaw = sensor.readMagneticRaw()                       // Read raw magnetic field, () → IntArray counts
                                                                        // X, Y, Z counts after reordering from the X, Z, Y wire order
            val drdy = sensor.magDataReady()                            // Check magnetometer data ready, () → Boolean
                                                                        // true when SR_REG_M.DRDY is set
            val magStatus = sensor.readMagStatus()                      // Read magnetometer status, () → IntArray (drdy, lock)
                                                                        // decodes SR_REG_M
            val id = sensor.readMagIdentity()                           // Read magnetometer ID, () → ByteArray
                                                                        // IRA/IRB/IRC: 0x48 0x34 0x33 ("H43")
            println("mag raw=${magRaw.toList()} drdy=$drdy status=${magStatus.toList()} id=${id.toList()}")

            sensor.enableTemperature(true)                              // Enable temperature sensor, (enabled) → Unit
                                                                        // sets TEMP_EN in CRA_REG_M
            val dT = sensor.readTemperatureDelta()                      // Read temperature delta, () → Double °C
                                                                        // relative only (8 LSB/°C), no absolute reference
            val m = sensor.readMagnetic()                               // Read magnetic field, () → DoubleArray µT
                                                                        // X, Y, Z in µT with the per-axis gain
            val hFlat = sensor.heading(false)                           // Compass heading (flat), (tiltCompensate=false) → Double degrees
                                                                        // atan2(my, mx), 0–360
            val hTilt = sensor.heading(true)                            // Compass heading (tilt-compensated), (tiltCompensate=true) → Double degrees
                                                                        // reads the accelerometer for roll and pitch
            println("dT=%.3f C  m=${m.toList()}  heading flat=%.1f tilt=%.1f".format(dT, hFlat, hTilt))

            sensor.powerDown()                                          // Power down accelerometer, () → Unit
                                                                        // ODR=0000; register contents retained
            sensor.setDataRate(100)                                     // Resume accelerometer, (rateHz) → Unit Hz
                                                                        // back to 100 Hz after power-down
            sensor.setMagMode(2)                                        // Put magnetometer to sleep, (mode=2) → Unit
                                                                        // MD=10 sleep mode
            sensor.reboot()                                             // Reboot accelerometer memory, () → Unit
                                                                        // BOOT=1, waits 5 ms; construct the driver again to re-run init
        }
    }
}
