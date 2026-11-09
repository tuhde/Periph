///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-kotlin:1.3.3

// TMC5160 complete: exercise the main public API.
// Wiring: SPI Mode 3, 1 MHz; DRV_ENN tied low (or on GPIO 17); SD_MODE strapped low (SPI + ramp generator).

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.motor.TMC5160Full

fun main() {
    SPIConnection(System.getenv("SPI_BUS")?.toIntOrNull() ?: 0, System.getenv("SPI_DEVICE")?.toIntOrNull() ?: 0, 3, 1_000_000).use { connection ->  // open SPI, Mode 3, 1 MHz, (bus, device, mode, maxSpeedHz) → SPIConnection
        val m = TMC5160Full(connection, 0.075, null, null, null)  // create Full driver, (connection, rSenseOhm=0.075 Ω, enPin, diag0, diag1) → TMC5160Full
        // probes IOIN.VERSION and applies the defaults
        m.setCurrent(1.0)  // set run current, (rmsA A RMS) → void
        // programs GLOBALSCALER and IRUN/IHOLD
        m.setHoldCurrent(0.3)  // set hold current, (rmsA A RMS) → void
        // independent IHOLD, never above the run current
        m.setMicrosteps(256)  // set microstep resolution, (n 1–256) → void
        // CHOPCONF.MRES; 256 keeps the ramp units in full steps
        m.configureRamp(0.0, 800.0, 400.0, 400.0, 400.0, 400.0, 800.0, 10.0)  // program SixPoint ramp, (vstart, a1, v1, amax, vmax, dmax, d1, vstop) → void
        // full steps/s and full steps/s²
        m.setZeroWait(0.1)  // set TZEROWAIT, (seconds s) → void
        // pause at standstill before reversing
        m.setStallThreshold(0)  // set StallGuard2 threshold, (sgt -64..63) → void
        // COOLCONF.sgt; lower is more sensitive
        m.enable()  // power stage on, () → void
        // waits 150 ms for StealthChop to settle
        m.setPosition(0.0)  // define position, (position full steps) → void
        // writes XACTUAL and XTARGET without motion
        m.moveTo(800.0)  // start positioning move, (position full steps) → void
        // writes XTARGET; the ramp generator does the rest
        m.waitUntilReached(0.0)  // wait for the ramp generator, (timeoutS=0 s, 0 = estimate) → void
        // polls RAMP_STAT.position_reached
        println("position: " + m.position())  // read XACTUAL, () → double full steps
        println("velocity: " + m.velocity())  // read VACTUAL, () → double full steps/s
        println("ramp: " + m.readRampStatus())  // read RAMP_STAT, () → RampStatus
        println("status: " + m.readStatus())  // read DRV_STATUS, () → Status
        println("stallguard: " + m.readStallguard())  // read SG_RESULT, () → int
        println("tstep: " + m.readTstep())  // read TSTEP, () → long
        println("gstat: " + m.readGlobalStatus())  // read GSTAT, () → GlobalStatus
        m.run(300.0)  // velocity mode, (fullStepsPerS full steps/s) → void
        // RAMPMODE 1 / 2 by sign
        Thread.sleep(500)
        m.stop()  // controlled stop, () → void
        // velocity mode with VMAX = 0
        m.disable()  // power stage off, () → void
        // DRV_ENN high or TOFF = 0
    }
}
