///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.LSM303Full
import java.util.function.IntConsumer

def bus  = System.getenv("I2C_BUS")?.toInteger() ?: 1
def accelConn = new I2CConnection(bus, 0x19)
def magConn   = new I2CConnection(bus, 0x1E)
try {
    def compass = new LSM303Full(accelConn, magConn)           // Create LSM303 Full driver, (accel_connection, mag_connection)

    def ident = compass.readMagIdentity()                      // Read magnetometer identity, () → [byte, byte, byte]
                                                               // IRA/IRB/IRC = 0x48 0x34 0x33 ("H43", same as HMC5883L)
    compass.setRange(4)                                        // Set accelerometer range, (rangeG) → g
                                                               // selects ±4 g and updates the cached sensitivity
    compass.setDataRate(50)                                    // Set accelerometer data rate, (rateHz) → Hz
                                                               // 50 Hz ODR; 1620 / 5376 Hz need low-power mode
    compass.setMode(2)                                         // Set operating mode, (mode) → None
                                                               // 2 = high-resolution (12-bit, 1 mg/LSB at ±2 g)
    compass.setAxes(true, true, true)                         // Set axes enabled, (x, y, z) → None
                                                               // Xen / Yen / Zen bits in CTRL_REG1_A
    compass.setMagRange(130)                                   // Set magnetometer range, (rangeUt) → µT
                                                               // writes CRB_REG_M gain ±1.3 gauss and updates the cached gains
    compass.setMagDataRate(30.0d)                              // Set magnetometer data rate, (rateHz) → Hz
                                                               // DO bits in CRA_REG_M; keeps TEMP_EN
    compass.setMagMode(0)                                      // Set magnetometer mode, (magMode) → None
                                                               // 0 = continuous conversion
    compass.enableTemperature(true)                            // Enable temperature sensor, (enabled) → None
                                                               // sets TEMP_EN in CRA_REG_M

    compass.setHighPass(0, 0, false, false, false, false)      // Configure high-pass filter, (mode, cutoff, filterOutput, onIa1, onIa2, onClick) → None
                                                               // HPM=00 normal mode, filter bypassed from output and interrupts
    compass.resetHighPass()                                    // Reset high-pass filter, () → int
                                                               // reads REFERENCE_A, which resets the filter in HPM=00
    compass.setReference(0)                                    // Set high-pass reference, (value) → None
                                                               // REFERENCE_A for the reference-signal mode HPM=01

    compass.setFifo(2, 8, false)                               // Configure FIFO, (mode, watermark, triggerInt2) → None
                                                               // stream mode, 8-sample watermark, FIFO_EN set in CTRL_REG5_A
    compass.fifoLevel()                                        // FIFO unread sample sets, () → int
                                                               // FSS<4:0> from FIFO_SRC_REG_A (0–32)
    compass.readFifoStatus()                                   // Read FIFO status, () → [wm, ov, empty, level]
                                                               // watermark / overrun / empty flags plus the sample count
    compass.readFifo(-1)                                       // Drain the FIFO, (count=-1 all unread) → [g, g, g]*
                                                               // pops one 6-byte burst per sample set, converted to *g*
    compass.setFifo(0, 0, false)                               // Configure FIFO, (mode, watermark, triggerInt2) → None
                                                               // back to bypass — only bypass empties the buffer

    compass.setFreeFall(1, 0.35d, 60.0d)                      // Configure free-fall, (generator=1, thresholdG, durationMs) → g, ms
                                                               // INT1_CFG_A = 0x95: all three axes low, AND mode
    compass.setLatch(1, true)                                  // Set interrupt latching, (generator, enabled) → None
                                                               // LIR_INT1: INT output stays asserted until INT1_SOURCE_A is read
    compass.enableInterrupt(LSM303Full.SOURCE_IA1, 1)         // Route interrupt source, (source, pin) → None
                                                               // generator 1 events → INT1
    compass.setClick(0, 0x04, 0.8d, 20.0d, 40.0d, 200.0d)      // Configure click detection, (single=0, double=0x04, thresholdG, timeLimitMs, latencyMs, windowMs) → g, ms
                                                               // axis masks: X=1, Y=2, Z=4; double tap on Z
    compass.enableInterrupt(LSM303Full.SOURCE_CLICK, 2)       // Route interrupt source, (source, pin) → None
                                                               // click events → INT2
    compass.setMotionInterrupt(2, 0x15, 0.5d, 100.0d, true)   // Configure motion interrupt, (generator, events, thresholdG, durationMs, andMode) → g, ms
                                                               // generator 2: all axes low, AND mode, 0.5 g / 100 ms
    compass.enableInterrupt(LSM303Full.SOURCE_IA2, 2)         // Route interrupt source, (source, pin) → None
                                                               // generator 2 events → INT2
    compass.setOrientation(2, true, false)                    // Configure orientation recognition, (generator, position, fourD) → None
                                                               // 6D position recognition on generator 2
    compass.disableInterrupt(LSM303Full.SOURCE_IA2, 2)        // Remove interrupt source, (source, pin) → None
                                                               // unroutes generator 2 from INT2 again
    compass.setInterruptPolarity(false)                       // Set INT polarity, (activeLow) → None
                                                               // H_LACTIVE = 0: INT1 / INT2 active-high

    def temp = compass.readTemperatureDelta()                  // Read relative temperature, () → float °C
                                                               // 12-bit left-justified, 8 LSB/°C, relative only (no absolute reference)
    def raw = compass.readAccelerationRaw()                   // Read raw acceleration, () → [counts, counts, counts]
                                                               // signed counts at the current resolution (12-bit in high-resolution mode)
    def xyz = compass.readAcceleration()                      // Read 3-axis acceleration, () → [g, g, g]
                                                               // burst-reads 0x28–0x2D with auto-increment, 1 mg/LSB at ±2 g
    def mag = compass.readMagnetic()                          // Read 3-axis magnetic field, () → [µT, µT, µT]
                                                               // on-wire order X, Z, Y high-byte-first, reordered to X, Y, Z
    def mraw = compass.readMagneticRaw()                      // Read raw magnetic field, () → [counts, counts, counts]
                                                               // signed 12-bit counts in X, Y, Z order
    def avail = compass.newDataAvailable()                    // Check new accelerometer data, () → boolean
                                                               // STATUS_REG_A.ZYXDA
    def status = compass.readStatus()                          // Read accelerometer status, () → int
                                                               // STATUS_REG_A byte (overrun + data-ready flags)
    def mdrdy = compass.magDataReady()                        // Check new magnetometer data, () → boolean
                                                               // SR_REG_M.DRDY
    def magStatus = compass.readMagStatus()                   // Read magnetometer status, () → [drdy, lock]
                                                               // DRDY flag and output-register LOCK
    def src1 = compass.readInterruptSource(1)                 // Read interrupt source register, (generator) → int
                                                               // INT1_SOURCE_A byte; reading clears the latched interrupt
    def click = compass.readClickSource()                     // Read click source register, () → int
                                                               // CLICK_SRC_A byte; reading clears the click interrupt
    def pollStatus = compass.pollInterrupt()                  // Poll all enabled interrupt sources, () → int
                                                               // combined SOURCE_* bitmask; reading clears the latched flags
    def hdg = compass.heading(true)                           // Compute compass heading, (tiltCompensate=true) → float °
                                                               // tilt-compensated atan2 heading from both sensors, 0–360°

    compass.onInterrupt({ statusInt -> } as IntConsumer, 1)    // Subscribe to INT line, (callback, pin) → None
                                                               // uses the connection's INT pin, or a 5 ms polling thread as fallback
    compass.offInterrupt(1)                                    // Unsubscribe from INT line, (pin) → None
                                                               // removes the callback and stops edge delivery / polling

    compass.powerDown()                                        // Enter power-down, () → None
                                                               // ODR = 0000; register contents are retained
    compass.setDataRate(50)                                    // Set accelerometer data rate, (rateHz) → Hz
                                                               // leaving power-down again; first sample after the turn-on time
    compass.reboot()                                           // Reboot memory content, () → None
                                                               // BOOT = 1 reloads the factory trim; re-run init afterwards

    printf("ident=0x%02X%02X%02X raw=(%d,%d,%d) xyz=(%.3f,%.3f,%.3f) g%n",
            ident[0], ident[1], ident[2], raw[0], raw[1], raw[2], xyz[0], xyz[1], xyz[2])
    printf("mag=(%.1f,%.1f,%.1f) uT mraw=(%d,%d,%d)%n", mag[0], mag[1], mag[2], mraw[0], mraw[1], mraw[2])
    printf("temp=%.2f C avail=%s status=0x%02X mdrdy=%s lock=%s src1=0x%02X click=0x%02X poll=0x%02X hdg=%.1f deg%n",
            temp, avail ? "yes" : "no", status, mdrdy ? "yes" : "no",
            magStatus[1] == 1 ? "locked" : "free", src1, click, pollStatus, hdg)
} finally {
    accelConn.close()
    magConn.close()
}

