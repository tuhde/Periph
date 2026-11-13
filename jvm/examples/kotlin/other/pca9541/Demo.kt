///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.other.PCA9541Full

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, PCA9541Full.DEFAULT_ADDRESS).use { connection ->                            // open I²C bus, device address, (bus, address=0x70) → I2CConnection
        val selector = PCA9541Full(connection)                                                     // construct driver, (connection) → PCA9541Full
        // --- Power-up state ---
        // CONTROL 0x04 for master 0 means /01 (bus on, master 0); 0x00 means /03 (bus off).
        val all = selector.readAll()                                                               // Read IE, CONTROL, ISTAT, () → {ie, control, istat}
        println("IE/CONTROL/ISTAT $all")
        println("hasControl ${selector.status().hasControl}")                                      // Decode CONTROL, () → Status

        // --- Interrupts ---
        selector.setInterruptMask()                                                                // Unmask all interrupts, (...) → void
        selector.onInterrupt { status ->
            if (status and PCA9541Full.SOURCE_BUS_LOST != 0) println("BUS_LOST: the other master took the bus")
            if (status and PCA9541Full.SOURCE_TEST != 0) println("MYTEST: the INT wire works")
            println("ISTAT ${status.toString(16)}")
        }

        // --- Take the bus with a bus initialization ---
        // The chip clocks nine pulses, a NACK and a STOP on the downstream bus first, which
        // completes a transfer the previous master left half-done.
        println("recoverBus " + selector.recoverBus(20))                                           // Recover and take the bus, (timeoutMs) → boolean
        Thread.sleep(10)
        // tBUF: bus-free time before talking to a downstream device through a second Connection

        // --- INT wire check ---
        selector.testInterrupt(own = true)                                                         // Drive this master's INT low, (own, other) → void
        Thread.sleep(100)
        selector.testInterrupt()                                                                   // Clear the test, (own, other) → void

        // --- Hand over and take back ---
        selector.release()                                                                         // Give the bus to the other master, () → void
        println("has control after release " + selector.hasControl())                              // Report control, () → boolean
        println("acquire " + selector.acquire())                                                   // Take the downstream bus, (busInit=false, timeoutMs=20) → boolean
        selector.disconnect()                                                                      // Isolate the downstream bus, () → void
        selector.connect()                                                                         // Reconnect, () → void

        // --- Watch the flags for 10 s ---
        for (i in 0 until 20) {
            println("ISTAT ${selector.pollInterrupt().toString(16)}")                              // Read and clear ISTAT, () → int
            Thread.sleep(500)
        }
        selector.offInterrupt()                                                                    // Unsubscribe, () → void
    }
}
