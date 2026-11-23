///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.2
//DEPS it.uhde:periph-kotlin:1.5.2

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Ltc4151Full

fun main() {
    I2CConnection(1, 0x6A).use { connection ->              // open I²C bus 1, device 0x6A, (bus, address) → I2CConnection
        val ltc = Ltc4151Full(connection, 0.02)             // construct driver, (connection, rSense=0.02 Ω) → Ltc4151Full

        println(ltc.voltage())                              // read supply voltage, () → Double V
                                                            // VIN code × 25 mV
        println(ltc.current())                              // read load current, () → Double A
                                                            // sense voltage / rSense
        println(ltc.power())                                // read load power, () → Double W
                                                            // one block read of SENSE and VIN
        println(ltc.senseVoltage())                         // read SENSE+ − SENSE−, () → Double V
        println(ltc.adinVoltage())                          // read ADIN pin voltage, () → Double V
        println(ltc.readAll())                              // consistent read of all channels, () → Ltc4151Full.Readings
                                                            // one block read of registers 0x00–0x05
        println(ltc.readRaw(Ltc4151Full.VIN))               // read raw 12-bit code, (channel 0–2) → Int
                                                            // SENSE = 0, VIN = 1, ADIN = 2

        ltc.setSenseResistor(0.01)                          // change sense resistor, (ohms) → Unit
        println(ltc.getSenseResistor())                     // read sense resistor, () → Double Ω
        ltc.setSenseResistor(0.02)

        println(ltc.snapshot(Ltc4151Full.SENSE))            // one snapshot conversion, (channel 0–2) → Double V
                                                            // returns to continuous scan; IOException after 100 ms
        println(ltc.isSnapshotMode())                       // snapshot mode active, () → Boolean
        println(ltc.isBusy(Ltc4151Full.VIN))                // channel busy, (channel 0–2) → Boolean
        ltc.setContinuous()                                 // back to continuous scan, () → Unit

        ltc.setStuckBusTimer(true)                          // stuck-bus reset timer, (enabled) → Unit
        ltc.setPageRw(true)                                 // I²C page read/write, (enabled) → Unit
        println("0x%02X".format(ltc.readControl()))         // read CONTROL, () → Int
        ltc.writeControl(0x0C)                              // write CONTROL, (value) → Unit
                                                            // test mode bit 4 is always masked to 0

        println(Ltc4151Full.temperatureFromAdin(900, 1200))  // NTC example circuit, (adinCode, vinCode) → Double °C
    }
}
