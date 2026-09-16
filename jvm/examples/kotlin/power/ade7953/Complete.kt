///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Ade7953Full

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toIntOrNull() ?: 1
    val addr = System.getenv("I2C_ADDR")?.removePrefix("0x")?.toIntOrNull(16) ?: 0x38

    I2CConnection(bus, addr).use { connection ->
        val ade = Ade7953Full(connection, 251.0, 30.0)                 // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V)

        println("version: 0x%02X".format(ade.version()))                // Read silicon version, () → Int
        println("V=%.2f".format(ade.voltage()))                         // Read bus voltage, () → Double V
                                                                          // converts raw VRMS to volts using voltage_gain
        println("I_a=%.3f".format(ade.current()))                       // Read load current, () → Double A
                                                                          // converts raw IRMSA to amperes using current_gain
        println("P_a=%.2f".format(ade.activePower()))                   // Read active power, () → Double W
                                                                          // converts raw AWATT (instantaneous, 6.99 kHz) to watts
        println("E_a=%.4f".format(ade.activeEnergy()))                  // Read active energy, () → Double Wh
                                                                          // converts raw AENERGYA accumulated LSBs to watt-hours
        println("PF=%.3f".format(ade.powerFactor()))                    // Read power factor, () → Double ratio
                                                                          // converts raw PFA (1 LSB = 2^-15) to a −1.0..+1.0 ratio
        println("f=%.2f".format(ade.lineFrequency()))                   // Read line frequency, () → Double Hz

        ade.configureChannelB(30.0)                                       // Set Channel B calibration, (current_gain_b) → Unit
        println("I_b=%.3f".format(ade.currentB()))                       // Read Current Channel B, () → Double A

        ade.configureOvervoltage(260.0)                                   // Configure overvoltage, (threshold) → Unit
                                                                          // threshold in volts (same scale as voltage())
        ade.configureOvercurrent(40.0)                                    // Configure overcurrent, (threshold) → Unit
                                                                          // threshold in amperes; applies to BOTH current channels

        ade.reset()                                                       // Software reset, () → Unit
                                                                          // waits 110 ms then re-runs the mandatory power-up sequence
    }
}