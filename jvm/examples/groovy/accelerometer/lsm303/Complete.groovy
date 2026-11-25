///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.LSM303Full

def bus = (System.getenv('I2C_BUS') ?: '1') as int
def accelConn = new I2CConnection(bus, LSM303Full.I2C_ADDRESS_ACCEL)
def magConn = new I2CConnection(bus, LSM303Full.I2C_ADDRESS_MAG)
try {
    def compass = new LSM303Full(accelConn, magConn)         // Create LSM303 driver, (accel_connection, mag_connection)

    // --- Accelerometer readings ---
    def accel = compass.readAcceleration()                    // Read acceleration, () → [g, g, g]
    printf('accel x=%.4f y=%.4f z=%.4f g%n', accel[0], accel[1], accel[2])

    def rawAccel = compass.readAccelerationRaw()              // Read raw acceleration, () → [counts, counts, counts]
    printf('raw accel %d %d %d%n', rawAccel[0], rawAccel[1], rawAccel[2])

    def status = compass.readStatus()                        // Read status register, () → byte
    printf('status 0x%02X%n', status)

    def newData = compass.newDataAvailable()                  // Check new data, () → bool
    printf('new data %s%n', newData)

    // --- Accelerometer configuration ---
    compass.setRange(4)                                      // Set full-scale range, (range_g=4) → None
    compass.setDataRate(50)                                  // Set output data rate, (rate_hz=50) → None
    compass.setMode(LSM303Full.MODE_HIGH_RESOLUTION)         // Set operating mode, (mode=MODE_HIGH_RESOLUTION=2) → None
    compass.setAxes(true, true, true)                        // Enable axes, (x=true, y=true, z=true) → None

    // --- High-pass filter ---
    compass.setHighPass(0, 0, false, true, false, false)     // Configure high-pass filter, (mode=0, cutoff=0, filter_output=false, on_ia1=true, on_ia2=false, on_click=false) → None
    printf('reference %d%n', compass.resetHighPass())         // Reset high-pass filter, () → byte
    compass.setReference(0)                                  // Set reference value, (value=0) → None

    // --- FIFO ---
    compass.setFifo(LSM303Full.FIFO_STREAM, 16, false)        // Configure FIFO, (mode=FIFO_STREAM=2, watermark=16, trigger_int2=false) → None
    printf('fifo level %d%n', compass.fifoLevel())            // Read FIFO level, () → byte
    def fs = compass.readFifoStatus()                        // Read FIFO status, () → [watermark, overrun, empty, level]
    printf('fifo wm=%d ov=%d empty=%d level=%d%n', fs[0], fs[1], fs[2], fs[3])
    def samples = compass.readFifo(8)                        // Read FIFO, (count=8) → [[g, g, g], ...]
    printf('fifo samples %d%n', samples.length)

    // --- Inertial interrupt generators ---
    compass.setMotionInterrupt(1, 0x02, 0.5, 10, false)      // Configure motion interrupt, (generator=1, events=0x02, threshold_g=0.5, duration_ms=10, and_mode=false) → None
    compass.setFreeFall(1, 0.35, 30)                         // Arm free-fall, (generator=1, threshold_g=0.35, duration_ms=30) → None
    compass.setOrientation(1, false, false)                  // Configure orientation, (generator=1, position=false, four_d=false) → None
    compass.setLatch(1, true)                                // Latch the interrupt, (generator=1, enabled=true) → None
    printf('int1 source 0x%02X%n', compass.readInterruptSource(1))   // Read interrupt source, (generator=1) → byte

    // --- Click detection ---
    compass.setClick(0, 0x04, 0.8, 20, 40, 200)              // Configure click detector, (single=0, double=0x04, threshold_g=0.8, time_limit_ms=20, latency_ms=40, window_ms=200) → None
    printf('click source 0x%02X%n', compass.readClickSource())        // Read click source, () → byte

    // --- Interrupt routing ---
    compass.setInterruptPolarity(false)                      // Set interrupt polarity, (active_low=false) → None
    compass.enableInterrupt(LSM303Full.SOURCE_CLICK, 1)      // Route an interrupt, (source=SOURCE_CLICK=0x20, pin=1) → None
    compass.disableInterrupt(LSM303Full.SOURCE_CLICK, 1)     // Remove an interrupt, (source=SOURCE_CLICK=0x20, pin=1) → None

    // --- Magnetometer ---
    compass.setMagRange(250)                                 // Set magnetometer range, (range_ut=250) → None
    compass.setMagDataRate(30)                               // Set magnetometer data rate, (rate_hz=30) → None
    compass.setMagMode(LSM303Full.MAG_MODE_CONTINUOUS)       // Set magnetometer mode, (mode=MAG_MODE_CONTINUOUS=0) → None
    def rawMag = compass.readMagneticRaw()                   // Read raw magnetic field, () → [counts, counts, counts]
    printf('raw mag %d %d %d%n', rawMag[0], rawMag[1], rawMag[2])
    def ms = compass.readMagStatus()                         // Read magnetometer status, () → [drdy, lock]
    printf('mag drdy=%s lock=%s%n', ms[0], ms[1])
    def identity = compass.readMagIdentity()                 // Read magnetometer identity, () → [byte, byte, byte]
    printf('identity %02X%02X%02X%n', identity[0], identity[1], identity[2])
    compass.enableTemperature(true)                          // Enable the temperature sensor, (enabled=true) → None
    printf('temperature %.2f C%n', compass.readTemperatureDelta())   // Read relative temperature, () → °C

    // --- Compass heading ---
    printf('heading %.1f deg%n', compass.heading(true))     // Read the compass heading, (tilt_compensate=true) → °
    printf('flat heading %.1f deg%n', compass.heading(false))  // Read the compass heading, (tilt_compensate=false) → °

    // --- Miscellaneous ---
    compass.reboot()                                         // Reboot the accelerometer, () → None
} finally {
    accelConn.close()
    magConn.close()
}
