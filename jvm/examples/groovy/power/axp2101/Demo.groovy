///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.AXP2101Full

def bus  = System.getenv("I2C_BUS")?.toInteger() ?: 1
def addr = System.getenv("I2C_ADDR")?.replaceFirst("^0[xX]", "")?.toInteger(16) ?: 0x34
def rails = ["dcdc1", "dcdc2", "dcdc3", "dcdc4", "aldo1", "aldo2", "aldo3", "aldo4",
             "bldo1", "bldo2", "cpusldo", "dldo1", "dldo2"]
def events = [
    [AXP2101Full.SOURCE_VBUS_INSERT, "VBUS inserted"],
    [AXP2101Full.SOURCE_VBUS_REMOVE, "VBUS removed"],
    [AXP2101Full.SOURCE_CHARGE_START, "charging started"],
    [AXP2101Full.SOURCE_CHARGE_DONE, "charging done"],
    [AXP2101Full.SOURCE_PWRON_SHORT, "power key short press"],
    [AXP2101Full.SOURCE_SOC_WARN_L2, "battery warning level 2"]]
def connection = new I2CConnection(bus, addr)
try {
    def chip = new AXP2101Full(connection)          // Create AXP2101 Full driver, (connection)

    // --- Report why the board started and what every rail is doing ---
    // eFuse defaults differ per product, so the rails are read, never assumed.
    printf("power-on source: 0x%02X%n", chip.powerOnSource())  // Read power-on flags, () → int
    rails.each { String name ->
        def rail = chip.getRail(name)                // Read rail state, (name) → Rail(enabled, volts V)
        printf("%-8s %-3s %.2f V%n", name, rail.enabled ? "on" : "off", rail.volts)
    }

    // --- Program a conservative 4.2 V / 500 mA charge profile ---
    // 4.2 V suits a standard Li-ion cell; 500 mA keeps heating low; the 1.5 A input
    // limit lets a USB charger feed both the system and the charger.
    chip.setChargeVoltage(4.2)                       // Set charge target, (volts 4.0/4.1/4.2/4.35/4.4) → void
    chip.setChargeCurrent(0.5)                       // Set charge current, (amps 0–1.5) → void
    chip.setInputCurrentLimit(1.5)                   // Set input current limit, (amps 0.1/0.5/0.9/1.0/1.5/2.0) → void
    chip.setTermination(0.05, true)                  // Set termination current, (amps 0–0.2, enabled) → void

    // --- Arm low-battery warnings and the power events of interest ---
    // The warning levels are 15 % and 5 %; the six sources below raise the IRQ pin.
    chip.configureBatteryWarning(15, 5)              // Set warning levels, (level2 %, level1 %) → void
    events.each { chip.enableInterrupt((int) it[0]) } // Enable interrupt source, (source) → void
    chip.pollInterrupt()                             // Clear stale status, () → int

    // --- Monitor for 120 s ---
    // One status line per second; pollInterrupt() returns and clears every pending event.
    120.times {
        def d = chip.readAll()                       // Read all measurements, () → Readings
        def st = chip.status()                       // Read status flags, () → Status
        printf("%.3f V %3d %%  vbus %b %.2f V  sys %.2f V  die %.1f C  state %d  %s%n",
                d.batteryVoltage, d.batteryPercent, d.vbusPresent, d.vbusVoltage,
                d.systemVoltage, d.dieTemperature, d.chargeState, st.batteryDirection)
        int got = chip.pollInterrupt()               // Read and clear events, () → int
        events.each { e -> if ((got & (int) e[0]) != 0) println("event: " + e[1]) }
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
