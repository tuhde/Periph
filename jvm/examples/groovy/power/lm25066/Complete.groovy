///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.2
//DEPS it.uhde:periph-groovy:1.5.2

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.LM25066Full
import java.util.function.IntConsumer

def connection = new I2CConnection(1, 0x40)   // open I²C bus 1, device 0x40, (bus, address) → I2CConnection
try {
    def hotswap = new LM25066Full(connection, 1.0d, false)   // construct driver, (connection, rSenseMohm, currentLimitHigh) → LM25066Full

    println(hotswap.voltageIn())                                                   // input voltage, () → double V
    println(hotswap.voltageOut())                                                  // output voltage, () → double V
    println(hotswap.current())                                                     // input current, () → double A
    println(hotswap.power())                                                       // input power, () → double W
    println(hotswap.temperature())                                                 // MOSFET temperature, () → double °C
    println(hotswap.voltageAux())                                                  // VAUX pin voltage, () → double V (0 ... 1.16)
    println(hotswap.readBlock().power)                                             // time-aligned BLOCK_READ, () → Block {diagnostic, current, voltageOut, voltageIn, power, temperature}
    println(hotswap.readAverage().power)                                           // AVG_BLOCK_READ, () → Block
    println(hotswap.blackBox().diagnostic)                                         // snapshot at first SMBA, () → Block
    println(hotswap.voltageInAvg())                                                // averaged VIN, () → double V
    println(hotswap.voltageOutAvg())                                               // averaged VOUT, () → double V
    println(hotswap.currentAvg())                                                  // averaged IIN, () → double A
    println(hotswap.powerAvg())                                                    // averaged PIN, () → double W
    println(hotswap.powerPeak())                                                   // peak input power, () → double W
    hotswap.clearPowerPeak()                                                       // reset the peak register, () → void
    hotswap.setAveraging(6)                                                        // average 2^6 samples, (exponent 0-12) → void
    println(hotswap.getAveraging())                                                // () → int
    hotswap.setVinUvWarn(10.0d)                                                    // VIN under-voltage warning, (volts, null = off) → void
    hotswap.setVinOvWarn(15.0d)                                                    // VIN over-voltage warning, (volts, null = off) → void
    hotswap.setVoutUvWarn(9.0d)                                                    // VOUT under-voltage warning, (volts, null = off) → void
    hotswap.setOtWarn(100.0d)                                                      // over-temperature warning, (celsius, null = off) → void
    hotswap.setOtFault(125.0d)                                                     // over-temperature fault, (celsius, null = off) → void
    hotswap.setIinWarn(20.0d)                                                      // input over-current warning, (amps, null = off) → void
    hotswap.setPinWarn(200.0d)                                                     // input over-power warning, (watts, null = off) → void
    println(hotswap.getLimits().vinUvWarn)                                         // all thresholds, () → Limits (null = disabled)
    println(hotswap.statusByte())                                                  // STATUS_BYTE, () → int
    println(hotswap.statusWord())                                                  // STATUS_WORD, () → int
    println(hotswap.statusVout())                                                  // STATUS_VOUT, () → int
    println(hotswap.statusInput())                                                 // STATUS_INPUT, () → int
    println(hotswap.statusTemperature())                                           // STATUS_TEMPERATURE, () → int
    println(hotswap.statusCml())                                                   // STATUS_CML, () → int
    println(hotswap.statusMfr())                                                   // STATUS_MFR_SPECIFIC, () → int
    println(hotswap.diagnosticWord())                                              // READ_DIAGNOSTIC_WORD, () → int
    hotswap.clearFaults()                                                          // CLEAR_FAULTS (does not turn the MOSFET on), () → void
    println(hotswap.isOn())                                                        // MOSFET on, () → boolean
    // hotswap.operationOff()                                                      // CUTS THE LOAD RAIL, () → void
    // hotswap.operationOn()                                                       // switch the MOSFET on, () → void
    println(hotswap.deviceSetup())                                                 // DEVICE_SETUP, () → int
    // hotswap.configureDevice(7, false, null)                                     // override RETRY/CL/CB pins, (retryCode, currentLimitHigh, cbRatioHigh) → void
    // hotswap.setGateMask(0x04)                                                   // DANGEROUS: disable fault shut-off, (mask bits 5/4/2) → void
    println(hotswap.getGateMask())                                                 // GATE_MASK, () → int
    println(hotswap.capability())                                                  // CAPABILITY, () → int
    println(hotswap.manufacturerId())                                              // MFR_ID, () → String
    println(hotswap.model())                                                       // MFR_MODEL, () → String
    println(hotswap.revision())                                                    // MFR_REVISION, () → String
    hotswap.enableInterrupt(LM25066Full.SOURCE_VIN_UV_WARN)                        // unmask an alert source, (source) → void
    hotswap.disableInterrupt(LM25066Full.SOURCE_VIN_UV_WARN)                       // mask an alert source, (source) → void
    println(hotswap.pollInterrupt())                                               // diagnostic word, () → int
    hotswap.onInterrupt({ int diag -> println("alert " + diag) } as IntConsumer)   // subscribe to SMBA, (callback) → void
    Thread.sleep(2000)
    hotswap.offInterrupt()                                                         // unsubscribe, () → void
} finally {
    connection.close()
}
