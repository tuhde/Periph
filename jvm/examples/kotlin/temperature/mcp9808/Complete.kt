///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.temperature.MCP9808Full
import it.uhde.periph.chips.temperature.MCP9808Minimal

// Exercises every method in the MCP9808 public API. The one-way lock
// methods are shown but left commented out — they cannot be undone
// without a power-on reset.

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, MCP9808Minimal.DEFAULT_ADDRESS).use { connection ->                // open I²C bus, device address, (bus, address=0x18) → I2CConnection
        val sensor = MCP9808Full(connection)                                              // construct driver and check identity, (connection) → MCP9808Full
                                                                                          // checks MANUFACTURER_ID 0x0054 and DEVICE_ID 0x04

        val t = sensor.readTemperature()                                                  // Read ambient temperature, () → double °C
                                                                                          // masks TA's 3 status bits, decodes 1/16 °C two's complement
        println("temperature %.4f °C".format(t))

        sensor.setResolution(0.25)                                                        // Set resolution, (celsius 0.5|0.25|0.125|0.0625) → Unit
                                                                                          // 0.25 °C step converts in ~65 ms instead of 250 ms
        val res = sensor.getResolution()                                                  // Read resolution, () → double °C
                                                                                          // decodes the RESOLUTION register code
        println("resolution %s °C".format(res))

        sensor.shutdown()                                                                 // Enter Shutdown mode, () → Unit
                                                                                          // stops conversion; TA keeps its last value
        val off = sensor.isShutdown()                                                     // Check Shutdown mode, () → boolean
                                                                                          // reads CONFIG.SHDN
        println("shutdown %s".format(off))
        sensor.wake()                                                                     // Leave Shutdown mode, () → Unit
                                                                                          // resumes continuous conversion
        Thread.sleep(100)

        sensor.setUpperLimit(30.0)                                                        // Set TUPPER, (celsius °C) → Unit
                                                                                          // rounded to the nearest 0.25 °C step
        sensor.setLowerLimit(10.0)                                                        // Set TLOWER, (celsius °C) → Unit
                                                                                          // rounded to the nearest 0.25 °C step
        sensor.setCriticalLimit(45.0)                                                     // Set TCRIT, (celsius °C) → Unit
                                                                                          // rounded to the nearest 0.25 °C step
        val upper = sensor.getUpperLimit()                                                // Read TUPPER, () → double °C
                                                                                          // decodes the 0.25 °C two's-complement boundary
        val lower = sensor.getLowerLimit()                                                // Read TLOWER, () → double °C
                                                                                          // decodes the 0.25 °C two's-complement boundary
        val crit = sensor.getCriticalLimit()                                              // Read TCRIT, () → double °C
                                                                                          // decodes the 0.25 °C two's-complement boundary
        println("upper %s lower %s critical %s".format(upper, lower, crit))

        sensor.setHysteresis(1.5)                                                         // Set hysteresis, (celsius 0|1.5|3.0|6.0) → Unit
                                                                                          // applied on the cooling edge of each boundary only
        val hyst = sensor.getHysteresis()                                                 // Read hysteresis, () → double °C
                                                                                          // decodes CONFIG.THYST
        println("hysteresis %s °C".format(hyst))

        // sensor.lockCriticalLimit()                                                     // Lock TCRIT, () → Unit
                                                                                          // irreversible until power-on reset
        // sensor.lockWindowLimits()                                                      // Lock TUPPER/TLOWER, () → Unit
                                                                                          // irreversible until power-on reset
        val critLocked = sensor.isCriticalLimitLocked()                                   // Check TCRIT lock, () → boolean
                                                                                          // reads CONFIG.CRIT_LOCK
        val winLocked = sensor.isWindowLimitsLocked()                                     // Check TUPPER/TLOWER lock, () → boolean
                                                                                          // reads CONFIG.WIN_LOCK
        println("crit locked %s win locked %s".format(critLocked, winLocked))

        sensor.configureAlert(MCP9808Full.AlertMode.ALL, MCP9808Full.AlertOutput.INTERRUPT, MCP9808Full.AlertPolarity.ACTIVE_LOW)  // Configure Alert, (mode=ALL, output=COMPARATOR, polarity=ACTIVE_LOW) → Unit
                                                                                          // sets ALERT_SEL, ALERT_MOD and ALERT_POL together
        sensor.enableAlert()                                                              // Enable Alert output, () → Unit
                                                                                          // sets CONFIG.ALERT_CNT
        val asserted = sensor.isAlertAsserted()                                           // Check Alert output, () → boolean
                                                                                          // reads the read-only CONFIG.ALERT_STAT
        println("alert asserted %s".format(asserted))

        val status = sensor.pollInterrupt()                                               // Read boundary status, () → int mask
                                                                                          // TA's live bits: SOURCE_LOWER/UPPER/CRITICAL, nothing cleared
        println("below lower %s above upper %s critical %s".format(status and MCP9808Full.SOURCE_LOWER != 0, status and MCP9808Full.SOURCE_UPPER != 0, status and MCP9808Full.SOURCE_CRITICAL != 0))
        sensor.clearInterrupt()                                                           // Clear interrupt-mode Alert, () → Unit
                                                                                          // writes CONFIG.INT_CLEAR=1; no effect in comparator mode

        sensor.onInterrupt({ st -> println("alert, status mask $st") })                   // Subscribe to Alert, (callback) → Unit
                                                                                          // callback receives the pollInterrupt() mask; 5 ms polling thread without an intPin
        Thread.sleep(5000)
        sensor.offInterrupt()                                                             // Unsubscribe, () → Unit
                                                                                          // detaches the edge handler or stops the polling thread
        sensor.disableAlert()                                                             // Disable Alert output, () → Unit
                                                                                          // clears CONFIG.ALERT_CNT
    }
}
