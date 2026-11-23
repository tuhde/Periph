///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.1
//DEPS it.uhde:periph-groovy:1.5.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Ltc4151Full

def connection = new I2CConnection(1, 0x6A)                 // open I²C bus 1, device 0x6A, (bus, address) → I2CConnection
try {
    def ltc = new Ltc4151Full(connection, 0.02d)            // construct driver, (connection, rSense=0.02 Ω) → Ltc4151Full

    println ltc.voltage()                                   // read supply voltage, () → double V
                                                            // VIN code × 25 mV
    println ltc.current()                                   // read load current, () → double A
                                                            // sense voltage / rSense
    println ltc.power()                                     // read load power, () → double W
                                                            // one block read of SENSE and VIN
    println ltc.senseVoltage()                              // read SENSE+ − SENSE−, () → double V
    println ltc.adinVoltage()                               // read ADIN pin voltage, () → double V
    def all = ltc.readAll()                                 // consistent read of all channels, () → Ltc4151Full.Readings
                                                            // one block read of registers 0x00–0x05
    println "${all.vin} V, ${all.current} A, ${all.power} W"
    println ltc.readRaw(Ltc4151Full.VIN)                    // read raw 12-bit code, (channel 0–2) → int
                                                            // SENSE = 0, VIN = 1, ADIN = 2

    ltc.setSenseResistor(0.01d)                             // change sense resistor, (ohms) → void
    println ltc.getSenseResistor()                          // read sense resistor, () → double Ω
    ltc.setSenseResistor(0.02d)

    println ltc.snapshot(Ltc4151Full.SENSE)                 // one snapshot conversion, (channel 0–2) → double V
                                                            // returns to continuous scan; IOException after 100 ms
    println ltc.isSnapshotMode()                            // snapshot mode active, () → boolean
    println ltc.isBusy(Ltc4151Full.VIN)                     // channel busy, (channel 0–2) → boolean
    ltc.setContinuous()                                     // back to continuous scan, () → void

    ltc.setStuckBusTimer(true)                              // stuck-bus reset timer, (enabled) → void
    ltc.setPageRw(true)                                     // I²C page read/write, (enabled) → void
    printf("0x%02X%n", ltc.readControl())                   // read CONTROL, () → int
    ltc.writeControl(0x0C)                                  // write CONTROL, (value) → void
                                                            // test mode bit 4 is always masked to 0

    println Ltc4151Full.temperatureFromAdin(900, 1200)      // NTC example circuit, (adinCode, vinCode) → double °C
} finally {
    connection.close()
}
