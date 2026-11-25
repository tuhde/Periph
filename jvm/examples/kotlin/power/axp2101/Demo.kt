///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.AXP2101Full

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toIntOrNull() ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { Integer.parseInt(it.replaceFirst("^0[xX]".toRegex(), ""), 16) } ?: 0x34
    val rails = listOf("dcdc1", "dcdc2", "dcdc3", "dcdc4", "aldo1", "aldo2", "aldo3", "aldo4",
                       "bldo1", "bldo2", "cpusldo", "dldo1", "dldo2")
    val events = listOf(
        AXP2101Full.SOURCE_VBUS_INSERT to "VBUS inserted",
        AXP2101Full.SOURCE_VBUS_REMOVE to "VBUS removed",
        AXP2101Full.SOURCE_CHARGE_START to "charging started",
        AXP2101Full.SOURCE_CHARGE_DONE to "charging done",
        AXP2101Full.SOURCE_PWRON_SHORT to "power key short press",
        AXP2101Full.SOURCE_SOC_WARN_L2 to "battery warning level 2")
    I2CConnection(bus, addr).use { connection ->
        val chip = AXP2101Full(connection)                  // Create AXP2101 Full driver, (connection)

        // --- Report why the board started and what every rail is doing ---
        // eFuse defaults differ per product, so the rails are read, never assumed.
        println("power-on source: 0x%02X".format(chip.powerOnSource()))  // Read power-on flags, () → Int
        for (name in rails) {
            val rail = chip.getRail(name)                    // Read rail state, (name) → Rail(enabled, volts V)
            println("%-8s %-3s %.2f V".format(name, if (rail.enabled) "on" else "off", rail.volts))
        }

        // --- Program a conservative 4.2 V / 500 mA charge profile ---
        // 4.2 V suits a standard Li-ion cell; 500 mA keeps heating low; the 1.5 A input
        // limit lets a USB charger feed both the system and the charger.
        chip.setChargeVoltage(4.2)                           // Set charge target, (volts 4.0/4.1/4.2/4.35/4.4) → Unit
        chip.setChargeCurrent(0.5)                           // Set charge current, (amps 0–1.5) → Unit
        chip.setInputCurrentLimit(1.5)                       // Set input current limit, (amps 0.1/0.5/0.9/1.0/1.5/2.0) → Unit
        chip.setTermination(0.05, true)                      // Set termination current, (amps 0–0.2, enabled) → Unit

        // --- Arm low-battery warnings and the power events of interest ---
        // The warning levels are 15 % and 5 %; the six sources below raise the IRQ pin.
        chip.configureBatteryWarning(15, 5)                  // Set warning levels, (level2 %, level1 %) → Unit
        for ((mask, _) in events) {
            chip.enableInterrupt(mask)                       // Enable interrupt source, (source) → Unit
        }
        chip.pollInterrupt()                                 // Clear stale status, () → Int

        // --- Monitor for 120 s ---
        // One status line per second; pollInterrupt() returns and clears every pending event.
        repeat(120) {
            val d = chip.readAll()                           // Read all measurements, () → Readings
            val st = chip.status()                           // Read status flags, () → Status
            println("%.3f V %3d %%  vbus %b %.2f V  sys %.2f V  die %.1f C  state %d  %s".format(
                d.batteryVoltage, d.batteryPercent, d.vbusPresent, d.vbusVoltage,
                d.systemVoltage, d.dieTemperature, d.chargeState, st.batteryDirection))
            val got = chip.pollInterrupt()                   // Read and clear events, () → Int
            for ((mask, text) in events) {
                if (got and mask != 0) println("event: $text")
            }
            Thread.sleep(1000)
        }
    }
}
