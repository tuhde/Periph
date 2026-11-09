///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-groovy:1.3.3

// TMC5160 demo: pick-and-place axis with the internal ramp generator.
// Wiring: SPI Mode 3, 1 MHz; DRV_ENN tied low (or on GPIO 17); SD_MODE strapped low (SPI + ramp generator).

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.motor.TMC5160Full

def connection = new SPIConnection((System.getenv("SPI_BUS") ?: "0").toInteger(), (System.getenv("SPI_DEVICE") ?: "0").toInteger(), 3, 1_000_000)  // open SPI, Mode 3, 1 MHz, (bus, device, mode, maxSpeedHz) → SPIConnection
try {
    // --- Pick-and-place axis: smooth trapezoid between two stations ---
    // The internal ramp generator accelerates at 400 full steps/s² up to 600 full steps/s;
// StealthChop keeps the axis quiet and StallGuard2 reports a crash against an end stop.
    def m = new TMC5160Full(connection, 0.075, null, null, null)  // create Full driver, (connection, rSenseOhm=0.075 Ω, enPin, diag0, diag1) → TMC5160Full
    m.setCurrent(1.2)  // set run current, (rmsA A RMS) → void
    m.setSpeed(600.0)  // set VMAX, (fullStepsPerS full steps/s) → void
    m.setAcceleration(400.0)  // set acceleration, (fullStepsPerS2 full steps/s²) → void
    m.setStallThreshold(2)  // set StallGuard2 threshold, (sgt -64..63) → void
    m.enable()  // power stage on, () → void
    m.setPosition(0.0)  // define position, (position full steps) → void
    // --- Shuttle between the stations and report each stop ---
    // Each stop prints the position and the StallGuard2 reading; a driver fault aborts the run.
    for (double station : [1000.0d, 0.0d, 1000.0d, 0.0d]) {
        m.moveTo(station)  // start positioning move, (position full steps) → void
        m.waitUntilReached(0.0d)  // wait for the ramp generator, (timeoutS=0 s) → void
        println("at " + m.position() + " (SG " + m.readStallguard() + ")")  // read XACTUAL / SG_RESULT, () → double, int
        if (m.readFault() != 0) {  // read fault bitmask, () → int
            println("driver fault, aborting")
            break
        }
    }
    m.disable()  // power stage off, () → void
} finally {
    connection.close()
}
