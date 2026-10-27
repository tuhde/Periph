///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.LSM303Full
import java.util.function.IntConsumer

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def accelConn = new I2CConnection(bus, 0x19)
def magConn = new I2CConnection(bus, 0x1E)
try {
    def sensor = new LSM303Full(accelConn, magConn)            // construct driver and run init, (accelConnection, magConnection) → LSM303Full
                                                               // verifies magnetometer ID 0x483433 and leaves ±2 g, 100 Hz, ±130 µT, 15 Hz

    sensor.setRange(4)                                         // Set full-scale range, (rangeG=2|4|8|16) → void g
                                                               // selects ±4 g; the cached sensitivity updates to 2 mg/LSB
    sensor.setDataRate(50)                                     // Set accelerometer ODR, (rateHz) → void Hz
                                                               // 50 Hz is valid in every mode
    sensor.setMode(2)                                          // Set accelerometer mode, (mode=0 low-power|1 normal|2 high-resolution) → void
                                                               // LPen=0, HR=1; high-resolution 12-bit output
    sensor.setAxes(true, true, true)                           // Enable accelerometer axes, (x, y, z) → void
                                                               // X, Y and Z enabled (Xen/Yen/Zen in CTRL_REG1_A)
    def a = sensor.readAcceleration()                          // Read acceleration, () → double[3] g
                                                               // burst-reads OUT_X_L_A…OUT_Z_H_A with auto-increment
    def raw = sensor.readAccelerationRaw()                     // Read raw acceleration, () → int[3] counts
                                                               // signed counts at the current resolution (raw16 >> 4 in HR mode)
    def fresh = sensor.newDataAvailable()                      // Check new sample, () → boolean
                                                               // true when STATUS_REG_A.ZYXDA is set
    def status = sensor.readStatus()                           // Read accelerometer status, () → int
                                                               // STATUS_REG_A byte: ZYXOR…XDA flags
    println(String.format("a=%s raw=%s fresh=%b status=0x%02X", a.toList(), raw.toList(), fresh, status))

    sensor.setHighPass(1, 0, false, true, false, false)        // Configure high-pass filter, (mode, cutoff, filterOutput, onIa1, onIa2, onClick) → void
                                                               // reference-filter mode; filter applied to generator 1 only
    sensor.setReference(0x00)                                  // Set high-pass reference, (value=0–255) → void
                                                               // REFERENCE_A value used when HPM = 01
    def ref = sensor.resetHighPass()                           // Read REFERENCE_A, () → int
                                                               // reading REFERENCE_A resets the filter in HPM = 00 mode
    println("reference=${ref}")

    sensor.setFifo(2, 16, false)                               // Configure FIFO, (mode=0–3, watermark=0–31, triggerInt2=false) → void
                                                               // stream mode with a 16-sample watermark; FIFO_EN set in CTRL_REG5_A
    Thread.sleep(300)
    def level = sensor.fifoLevel()                             // Read FIFO level, () → int samples
                                                               // FSS<4:0>, number of unread sample sets
    def samples = sensor.readFifo(4)                           // Read FIFO samples, (count=all) → double[][] g
                                                               // reads up to 4 sets, one 6-byte burst each
    def fifoStatus = sensor.readFifoStatus()                   // Read FIFO status, () → int[4] (watermark, overrun, empty, level)
                                                               // decodes FIFO_SRC_REG_A
    println("fifo level=${level} read=${samples.length} status=${fifoStatus.toList()}")
    sensor.setFifo(0, 0, false)                                // Return FIFO to bypass, (mode=0) → void
                                                               // bypass clears the buffer; FIFO_EN cleared

    sensor.setMotionInterrupt(1, 0x3F, 1.0d, 100.0d, false)    // Configure motion interrupt, (generator, events, threshold_g, duration_ms, andMode) → void
                                                               // OR of all six axis events, 1 g threshold, 100 ms minimum duration
    sensor.setFreeFall(2, 0.35d, 30.0d)                        // Configure free-fall, (generator, threshold_g, duration_ms) → void
                                                               // AND of low X/Y/Z events on generator 2
    sensor.setOrientation(1, false, false)                     // Configure 6D orientation, (generator, position, fourD) → void
                                                               // movement recognition on generator 1
    sensor.setLatch(1, true)                                   // Latch interrupt generator, (generator, enabled) → void
                                                               // LIR_INT1 set: IA stays high until INT1_SOURCE_A is read
    def src = sensor.readInterruptSource(1)                    // Read interrupt source, (generator) → int
                                                               // INT1_SOURCE_A byte; clears the latched interrupt
    println(String.format("int1 source=0x%02X", src))

    sensor.setClick(0x04, 0x04, 0.8d, 20.0d, 40.0d, 200.0d)    // Configure click detection, (single, double, threshold_g, time_limit_ms, latency_ms, window_ms) → void
                                                               // Z-axis single and double click, 0.8 g threshold
    def click = sensor.readClickSource()                       // Read click source, () → int
                                                               // CLICK_SRC_A byte; clears the click interrupt
    println(String.format("click source=0x%02X", click))

    sensor.setInterruptPolarity(false)                         // Set INT polarity, (activeLow) → void
                                                               // H_LACTIVE=0: INT1/INT2 active high
    sensor.enableInterrupt(LSM303Full.SOURCE_CLICK, 1)         // Route interrupt source, (source, pin=1) → void
                                                               // click routed to INT1 (CTRL_REG3_A I1_CLICK)
    sensor.enableInterrupt(LSM303Full.SOURCE_IA1, 2)           // Route interrupt source, (source, pin=2) → void
                                                               // generator 1 routed to INT2 (CTRL_REG6_A I2_INT1)
    sensor.disableInterrupt(LSM303Full.SOURCE_IA1, 2)          // Remove interrupt source, (source, pin=2) → void
                                                               // generator 1 removed from INT2

    sensor.onInterrupt({ int s -> println(String.format("interrupt status=0x%02X", s)) } as IntConsumer, 1)
                                                               // Subscribe to INT1, (callback, pin=1) → void
                                                               // no INT1 pin wired, so a 5 ms polling thread delivers events
    Thread.sleep(200)
    sensor.offInterrupt(1)                                     // Unsubscribe from INT1, (pin=1) → void
                                                               // stops the polling thread
    def pending = sensor.pollInterrupt()                       // Poll interrupt sources, () → int
                                                               // status bitmask of enabled sources; clears latched generator and click flags
    println(String.format("pending=0x%04X", pending))

    sensor.setMagRange(810)                                    // Set magnetometer range, (rangeUt=130|190|250|400|470|560|810) → void
                                                               // GN=111, ±8.1 gauss; cached gains updated
    sensor.setMagDataRate(15.0d)                               // Set magnetometer ODR, (rateHz) → void Hz
                                                               // DO=100 (15 Hz); TEMP_EN kept
    sensor.setMagMode(0)                                       // Set magnetometer mode, (mode=0 continuous|1 single|2 sleep) → void
                                                               // continuous conversion
    def magRaw = sensor.readMagneticRaw()                      // Read raw magnetic field, () → int[3] counts
                                                               // X, Y, Z counts after reordering from the X, Z, Y wire order
    def drdy = sensor.magDataReady()                           // Check magnetometer data ready, () → boolean
                                                               // true when SR_REG_M.DRDY is set
    def magStatus = sensor.readMagStatus()                     // Read magnetometer status, () → int[2] (drdy, lock)
                                                               // decodes SR_REG_M
    def id = sensor.readMagIdentity()                          // Read magnetometer ID, () → byte[3]
                                                               // IRA/IRB/IRC: 0x48 0x34 0x33 ("H43")
    println("mag raw=${magRaw.toList()} drdy=${drdy} status=${magStatus.toList()} id=${id.toList()}")

    sensor.enableTemperature(true)                             // Enable temperature sensor, (enabled) → void
                                                               // sets TEMP_EN in CRA_REG_M
    def dT = sensor.readTemperatureDelta()                     // Read temperature delta, () → double °C
                                                               // relative only (8 LSB/°C), no absolute reference
    def m = sensor.readMagnetic()                              // Read magnetic field, () → double[3] µT
                                                               // X, Y, Z in µT with the per-axis gain
    def hFlat = sensor.heading(false)                          // Compass heading (flat), (tiltCompensate=false) → double degrees
                                                               // atan2(my, mx), 0–360
    def hTilt = sensor.heading(true)                           // Compass heading (tilt-compensated), (tiltCompensate=true) → double degrees
                                                               // reads the accelerometer for roll and pitch
    println(String.format("dT=%.3f C  m=%s  heading flat=%.1f tilt=%.1f", dT, m.toList(), hFlat, hTilt))

    sensor.powerDown()                                         // Power down accelerometer, () → void
                                                               // ODR=0000; register contents retained
    sensor.setDataRate(100)                                    // Resume accelerometer, (rateHz) → void Hz
                                                               // back to 100 Hz after power-down
    sensor.setMagMode(2)                                       // Put magnetometer to sleep, (mode=2) → void
                                                               // MD=10 sleep mode
    sensor.reboot()                                            // Reboot accelerometer memory, () → void
                                                               // BOOT=1, waits 5 ms; construct the driver again to re-run init
} finally {
    magConn.close()
    accelConn.close()
}
