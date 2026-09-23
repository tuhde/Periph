///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-groovy:1.2.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.temperature.MCP9808Full
import java.util.function.IntConsumer

// Exercises every method in the MCP9808 public API. The one-way lock
// methods are shown but left commented out — they cannot be undone
// without a power-on reset.

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, MCP9808Full.DEFAULT_ADDRESS)                      // open I²C bus, device address, (bus, address=0x18) → I2CConnection
try {
    def sensor = new MCP9808Full(connection)                                              // construct driver and check identity, (connection) → MCP9808Full
                                                                                          // checks MANUFACTURER_ID 0x0054 and DEVICE_ID 0x04

    def t = sensor.readTemperature()                                                      // Read ambient temperature, () → double °C
                                                                                          // masks TA's 3 status bits, decodes 1/16 °C two's complement
    printf("temperature %.4f °C%n", t)

    sensor.setResolution(0.25d)                                                           // Set resolution, (celsius 0.5|0.25|0.125|0.0625) → void
                                                                                          // 0.25 °C step converts in ~65 ms instead of 250 ms
    def res = sensor.getResolution()                                                      // Read resolution, () → double °C
                                                                                          // decodes the RESOLUTION register code
    printf("resolution %s °C%n", res)

    sensor.shutdown()                                                                     // Enter Shutdown mode, () → void
                                                                                          // stops conversion; TA keeps its last value
    def off = sensor.isShutdown()                                                         // Check Shutdown mode, () → boolean
                                                                                          // reads CONFIG.SHDN
    printf("shutdown %s%n", off)
    sensor.wake()                                                                         // Leave Shutdown mode, () → void
                                                                                          // resumes continuous conversion
    Thread.sleep(100)

    sensor.setUpperLimit(30.0d)                                                           // Set TUPPER, (celsius °C) → void
                                                                                          // rounded to the nearest 0.25 °C step
    sensor.setLowerLimit(10.0d)                                                           // Set TLOWER, (celsius °C) → void
                                                                                          // rounded to the nearest 0.25 °C step
    sensor.setCriticalLimit(45.0d)                                                        // Set TCRIT, (celsius °C) → void
                                                                                          // rounded to the nearest 0.25 °C step
    def upper = sensor.getUpperLimit()                                                    // Read TUPPER, () → double °C
                                                                                          // decodes the 0.25 °C two's-complement boundary
    def lower = sensor.getLowerLimit()                                                    // Read TLOWER, () → double °C
                                                                                          // decodes the 0.25 °C two's-complement boundary
    def crit = sensor.getCriticalLimit()                                                  // Read TCRIT, () → double °C
                                                                                          // decodes the 0.25 °C two's-complement boundary
    printf("upper %s lower %s critical %s%n", upper, lower, crit)

    sensor.setHysteresis(1.5d)                                                            // Set hysteresis, (celsius 0|1.5|3.0|6.0) → void
                                                                                          // applied on the cooling edge of each boundary only
    def hyst = sensor.getHysteresis()                                                     // Read hysteresis, () → double °C
                                                                                          // decodes CONFIG.THYST
    printf("hysteresis %s °C%n", hyst)

    // sensor.lockCriticalLimit()                                                         // Lock TCRIT, () → void
                                                                                          // irreversible until power-on reset
    // sensor.lockWindowLimits()                                                          // Lock TUPPER/TLOWER, () → void
                                                                                          // irreversible until power-on reset
    def critLocked = sensor.isCriticalLimitLocked()                                       // Check TCRIT lock, () → boolean
                                                                                          // reads CONFIG.CRIT_LOCK
    def winLocked = sensor.isWindowLimitsLocked()                                         // Check TUPPER/TLOWER lock, () → boolean
                                                                                          // reads CONFIG.WIN_LOCK
    printf("crit locked %s win locked %s%n", critLocked, winLocked)

    sensor.configureAlert(MCP9808Full.AlertMode.ALL, MCP9808Full.AlertOutput.INTERRUPT, MCP9808Full.AlertPolarity.ACTIVE_LOW)  // Configure Alert, (mode=ALL, output=COMPARATOR, polarity=ACTIVE_LOW) → void
                                                                                          // sets ALERT_SEL, ALERT_MOD and ALERT_POL together
    sensor.enableAlert()                                                                  // Enable Alert output, () → void
                                                                                          // sets CONFIG.ALERT_CNT
    def asserted = sensor.isAlertAsserted()                                               // Check Alert output, () → boolean
                                                                                          // reads the read-only CONFIG.ALERT_STAT
    printf("alert asserted %s%n", asserted)

    def status = sensor.pollInterrupt()                                                   // Read boundary status, () → int mask
                                                                                          // TA's live bits: SOURCE_LOWER/UPPER/CRITICAL, nothing cleared
    printf("below lower %s above upper %s critical %s%n", (status & MCP9808Full.SOURCE_LOWER) != 0, (status & MCP9808Full.SOURCE_UPPER) != 0, (status & MCP9808Full.SOURCE_CRITICAL) != 0)
    sensor.clearInterrupt()                                                               // Clear interrupt-mode Alert, () → void
                                                                                          // writes CONFIG.INT_CLEAR=1; no effect in comparator mode

    sensor.onInterrupt({ int st -> println("alert, status mask ${st}") } as IntConsumer)  // Subscribe to Alert, (callback) → void
                                                                                          // callback receives the pollInterrupt() mask; 5 ms polling thread without an intPin
    Thread.sleep(5000)
    sensor.offInterrupt()                                                                 // Unsubscribe, () → void
                                                                                          // detaches the edge handler or stops the polling thread
    sensor.disableAlert()                                                                 // Disable Alert output, () → void
                                                                                          // clears CONFIG.ALERT_CNT
} finally {
    connection.close()
}
