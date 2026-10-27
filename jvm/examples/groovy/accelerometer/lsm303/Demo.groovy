///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.LSM303Full

def COMPASS_POINTS = ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE',
                      'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW']

def bus  = System.getenv("I2C_BUS")?.toInteger() ?: 1
def accelConn = new I2CConnection(bus, 0x19)
def magConn   = new I2CConnection(bus, 0x1E)
try {
    def compass = new LSM303Full(accelConn, magConn)           // Create LSM303 Full driver, (accel_connection, mag_connection)

    // --- Configure the e-compass: ±2 g at 50 Hz, ±130 µT at 15 Hz ---
    // High-resolution mode keeps 1 mg/LSB tilt resolution; the 50 Hz
    // accelerometer rate gives the tap / free-fall detectors a 20 ms
    // time base (1 LSb = 1/ODR).
    compass.setDataRate(50)                                    // Set accelerometer data rate, (rateHz) → Hz
    compass.setMagRange(130)                                   // Set magnetometer range, (rangeUt) → µT

    // --- Arm double tap on Z and a latched free-fall generator ---
    // The double tap needs a short time limit and a wide window;
    // free-fall is latched so a brief drop is never missed between polls.
    compass.setClick(0, 0x04, 0.8d, 20.0d, 40.0d, 200.0d)       // Configure click detection, (single=0, double=0x04, thresholdG, timeLimitMs, latencyMs, windowMs) → g, ms
    compass.setFreeFall(1, 0.35d, 60.0d)                       // Configure free-fall, (generator=1, thresholdG, durationMs) → g, ms
    compass.setLatch(1, true)                                  // Set interrupt latching, (generator, enabled) → None
    compass.enableInterrupt(LSM303Full.SOURCE_CLICK, 1)       // Route interrupt source, (source, pin) → None
    compass.enableInterrupt(LSM303Full.SOURCE_IA1, 1)         // Route interrupt source, (source, pin) → None

    // --- Stream-mode FIFO with a 16-sample watermark for clean averaging ---
    compass.setFifo(2, 16, false)                              // Configure FIFO, (mode, watermark, triggerInt2) → None

    def start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < 60_000) {
        def samples = compass.readFifo(-1)                     // Drain the FIFO, (count=-1 all unread) → [g, g, g]*
        def ax = 0.0d
        def ay = 0.0d
        def az = 0.0d
        if (samples) {
            for (s in samples) {                               // average the drained sample sets
                ax += s[0]; ay += s[1]; az += s[2]
            }
            ax /= samples.size(); ay /= samples.size(); az /= samples.size()
        }
        def m = compass.readMagnetic()                         // Read 3-axis magnetic field, () → [µT, µT, µT]
        def hdg = compass.heading(true)                        // Compute compass heading, (tiltCompensate=true) → float °
        def field = Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
        def name = COMPASS_POINTS[((Math.floor((hdg + 11.25d) / 22.5d) as int) % 16)]
        def temp = compass.readTemperatureDelta()              // Read relative temperature, () → float °C
        if (samples) {
            def pitch = Math.toDegrees(Math.atan2(-ax, Math.sqrt(ay * ay + az * az)))
            def roll = Math.toDegrees(Math.atan2(ay, az))
            printf("%5.1f deg %s  pitch=%+.1f roll=%+.1f  |m|=%.0f uT  dT=%+.1f C%n",
                    hdg, name, pitch, roll, field, temp)
        } else {
            printf("%5.1f deg %s  |m|=%.0f uT  dT=%+.1f C%n", hdg, name, field, temp)
        }

        def status = compass.pollInterrupt()                   // Poll all enabled interrupt sources, () → int
        if (status & LSM303Full.SOURCE_CLICK) {
            println("DOUBLE TAP")
        }
        if (status & LSM303Full.SOURCE_IA1) {
            println("FREE FALL")
        }

        Thread.sleep(1000)
    }
} finally {
    accelConn.close()
    magConn.close()
}
