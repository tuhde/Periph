///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.Lis3dhFull
import it.uhde.periph.chips.accelerometer.Lis3dhMinimal
import it.uhde.periph.chips.accelerometer.Lis3dhSource

def bus  = System.getenv("I2C_BUS")?.toInteger() ?: 1
def addr = System.getenv("I2C_ADDR")?.replaceFirst("^0[xX]", "")?.toInteger(16) ?: 0x18
new I2CConnection(bus, addr).withCloseable { connection ->
    def accel = new Lis3dhFull(connection)         // Create LIS3DH Full driver, (connection)
                                                   // the init sequence already sets ±2 g and 100 Hz

    // --- Configure the measurement for a tilt sensor ---
    // 50 Hz is enough for a human-scale tilt monitor and keeps the
    // FIFO filling at 16 samples in about a third of a second.
    // High-resolution mode (12 bit) is the default of the init
    // sequence, so the resolution stays at 1 mg/LSB.
    accel.setDataRate(50)                          // Set output data rate, (rateHz) → Hz
                                                   // 50 Hz; the driver waits 7/ODR before the first sample
    accel.setMode(Lis3dhMinimal.MODE_HIGH_RESOLUTION) // Set operating mode, (mode) → None
                                                   // 12-bit high-resolution mode, 1 mg/LSB at ±2 g

    // --- Verify the chip once at start-up ---
    // The self-test moves the internal MEMS mass and checks that
    // every axis changes by 17..360 LSb; a failure means the sensor
    // or its soldering is broken.
    def st = accel.selfTest(1)                      // Run the self-test, (mode) → boolean
                                                   // electrostatic self-test 0
    printf("self test: %s%n", st ? "PASS" : "FAIL")

    // --- Arm the gesture detectors ---
    // A double tap on the Z axis: two impulses within the 200 ms
    // window, each above 0.8 g.
    accel.setClick(0, 0x04, 0.8d, 20.0d, 40.0d, 200.0d, true) // Configure click detection, (single, doubleClick, thresholdG, timeLimitMs, latencyMs, windowMs, latch) → None
                                                   // double click on Z, 0.8 g, latched until CLICK_SRC is read
    // Free fall: all three axes below 0.35 g for 60 ms. The
    // generator interrupt is latched so the event is not lost
    // between two polls.
    accel.setFreeFall(1, 0.35d, 60.0d)             // Configure free fall, (generator, thresholdG, durationMs) → None
                                                   // all three axes low, AND mode, 0.35 g for 60 ms
    accel.setLatch(1, true)                        // Latch a generator interrupt, (generator, enabled) → None
                                                   // LIR_INT1 keeps the INT line high until INT1_SRC is read
    accel.enableInterrupt(Lis3dhSource.SOURCE_CLICK, 1) // Route a source to an INT pin, (source, pin) → None
                                                   // click events on INT1
    accel.enableInterrupt(Lis3dhSource.SOURCE_IA1, 1)  // Route a source to an INT pin, (source, pin) → None
                                                   // generator 1 (free fall) events on INT1

    // --- Buffer the samples in the FIFO ---
    // Stream mode overwrites the oldest sample when the FIFO is
    // full, so the last 320 ms are always available — enough to
    // average out the noise of a hand-held sensor.
    accel.setFifo(2, 16, false)                    // Configure the FIFO, (mode, watermark, triggerInt2) → None
                                                   // stream mode with a 16-sample watermark

    // --- Monitor for 60 s ---
    // Every 100 ms the FIFO is drained, the samples are averaged and
    // the orientation is printed. The interrupt flags are polled in
    // the same loop; reading INT1_SRC / CLICK_SRC clears them.
    def deadline = System.currentTimeMillis() + 60_000
    while (System.currentTimeMillis() < deadline) {
        def samples = accel.readFifo()             // Drain the FIFO, () → [[g, g, g], …]
                                                   // one 6-byte burst per sample set, oldest first
        if (!samples.isEmpty()) {
            double sx = 0, sy = 0, sz = 0
            for (def s : samples) {
                sx += s[0]
                sy += s[1]
                sz += s[2]
            }
            sx /= samples.size()
            sy /= samples.size()
            sz /= samples.size()

            // The tilt of the board: pitch is the rotation around
            // the Y axis, roll the one around the X axis.
            def pitch = Math.toDegrees(Math.atan2(-sx, Math.sqrt(sy * sy + sz * sz)))
            def roll = Math.toDegrees(Math.atan2(sy, sz))
            def magnitude = Math.sqrt(sx * sx + sy * sy + sz * sz)
            def temp = accel.readTemperatureDelta() // Read the relative temperature, () → °C
                                                   // 1 digit/°C, relative to an unspecified reference

            printf("pitch=%6.1f° roll=%6.1f° |g|=%.3f temp=%d°C samples=%d%n",
                    pitch, roll, magnitude, temp, samples.size())
        }

        def status = accel.pollInterrupt()          // Poll the interrupt status, () → int
                                                   // bitmask of every enabled source that fired
        if ((status & Lis3dhSource.SOURCE_CLICK) != 0) {
            println("DOUBLE TAP")
        }
        if ((status & Lis3dhSource.SOURCE_IA1) != 0) {
            println("FREE FALL")
        }
        Thread.sleep(100)
    }
}
