///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

// TMC5160 minimal: ramp-generator positioning moves.
// Wiring: SPI Mode 3, 1 MHz; DRV_ENN tied low (or on GPIO 17); SD_MODE strapped low (SPI + ramp generator).

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.motor.TMC5160Minimal

def connection = new SPIConnection((System.getenv("SPI_BUS") ?: "0").toInteger(), (System.getenv("SPI_DEVICE") ?: "0").toInteger(), 3, 1_000_000)  // open SPI, Mode 3, 1 MHz, (bus, device, mode, maxSpeedHz) → SPIConnection
try {
    def m = new TMC5160Minimal(connection, 0.075, null)  // create driver, (connection, rSenseOhm=0.075 Ω, enPin=null) → TMC5160Minimal
    m.setCurrent(1.0)  // set run current, (rmsA A RMS) → void
    m.enable()  // power stage on, () → void
    [400.0d, 0.0d].each { double target ->
        m.moveTo(target)  // start positioning move, (position full steps) → void
        m.waitUntilReached(0.0d)  // wait for the ramp generator, (timeoutS=0 s, 0 = estimate) → void
        println("position: " + m.position())  // read XACTUAL, () → double full steps
        println("fault: " + m.readFault())  // read fault bitmask, () → int
    }
    m.disable()  // power stage off, () → void
} finally {
    connection.close()
}
