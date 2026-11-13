///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.other.PCA9541Full

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, PCA9541Full.DEFAULT_ADDRESS)                               // open I²C bus, device address, (bus, address=0x70) → I2CConnection
try {
    def selector = new PCA9541Full(connection)                                                     // construct driver, (connection) → PCA9541Full
    // --- Power-up state ---
    // CONTROL 0x04 for master 0 means /01 (bus on, master 0); 0x00 means /03 (bus off).
    def all = selector.readAll()                                                                   // Read IE, CONTROL, ISTAT, () → {ie, control, istat}
    println("IE/CONTROL/ISTAT ${all}")
    println("hasControl ${selector.status().hasControl}")                                          // Decode CONTROL, () → Status

    // --- Interrupts ---
    selector.setInterruptMask(false, false, false, false)                                          // Unmask all interrupts, (...) → void
    selector.onInterrupt { int status ->
            if ((status & PCA9541Full.SOURCE_BUS_LOST) != 0) println("BUS_LOST: the other master took the bus")
            if ((status & PCA9541Full.SOURCE_TEST) != 0) println("MYTEST: the INT wire works")
            println("ISTAT ${Integer.toHexString(status)}")
        }

    // --- Take the bus with a bus initialization ---
    // The chip clocks nine pulses, a NACK and a STOP on the downstream bus first, which
    // completes a transfer the previous master left half-done.
    println("recoverBus " + selector.recoverBus(20))                                               // Recover and take the bus, (timeoutMs) → boolean
    Thread.sleep(10)
    // tBUF: bus-free time before talking to a downstream device through a second Connection

    // --- INT wire check ---
    selector.testInterrupt(true, false)                                                            // Drive this master's INT low, (own, other) → void
    Thread.sleep(100)
    selector.testInterrupt(false, false)                                                           // Clear the test, (own, other) → void

    // --- Hand over and take back ---
    selector.release()                                                                             // Give the bus to the other master, () → void
    println("has control after release " + selector.hasControl())                                  // Report control, () → boolean
    println("acquire " + selector.acquire())                                                       // Take the downstream bus, (busInit=false, timeoutMs=20) → boolean
    selector.disconnect()                                                                          // Isolate the downstream bus, () → void
    selector.connect()                                                                             // Reconnect, () → void

    // --- Watch the flags for 10 s ---
    for (int i = 0; i < 20; i++) {
        println("ISTAT ${Integer.toHexString(selector.pollInterrupt())}")                          // Read and clear ISTAT, () → int
        Thread.sleep(500)
    }
    selector.offInterrupt()                                                                        // Unsubscribe, () → void
} finally {
    connection.close()
}
