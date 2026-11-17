///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.0
//DEPS it.uhde:periph-java:1.5.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.LM25066Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x40)) {   // open I²C bus 1, device 0x40, (bus, address) → I2CConnection
            var hotswap = new LM25066Full(connection, 1.0, false);   // construct driver, (connection, rSenseMohm, currentLimitHigh) → LM25066Full

            System.out.println(hotswap.voltageIn());                                                  // input voltage, () → double V
            System.out.println(hotswap.voltageOut());                                                 // output voltage, () → double V
            System.out.println(hotswap.current());                                                    // input current, () → double A
            System.out.println(hotswap.power());                                                      // input power, () → double W
            System.out.println(hotswap.temperature());                                                // MOSFET temperature, () → double °C
            System.out.println(hotswap.voltageAux());                                                 // VAUX pin voltage, () → double V (0 ... 1.16)
            System.out.println(hotswap.readBlock().power);                                            // time-aligned BLOCK_READ, () → Block {diagnostic, current, voltageOut, voltageIn, power, temperature}
            System.out.println(hotswap.readAverage().power);                                          // AVG_BLOCK_READ, () → Block
            System.out.println(hotswap.blackBox().diagnostic);                                        // snapshot at first SMBA, () → Block
            System.out.println(hotswap.voltageInAvg());                                               // averaged VIN, () → double V
            System.out.println(hotswap.voltageOutAvg());                                              // averaged VOUT, () → double V
            System.out.println(hotswap.currentAvg());                                                 // averaged IIN, () → double A
            System.out.println(hotswap.powerAvg());                                                   // averaged PIN, () → double W
            System.out.println(hotswap.powerPeak());                                                  // peak input power, () → double W
            hotswap.clearPowerPeak();                                                      // reset the peak register, () → void
            hotswap.setAveraging(6);                                                       // average 2^6 samples, (exponent 0-12) → void
            System.out.println(hotswap.getAveraging());                                               // () → int
            hotswap.setVinUvWarn(10.0);                                                    // VIN under-voltage warning, (volts, null = off) → void
            hotswap.setVinOvWarn(15.0);                                                    // VIN over-voltage warning, (volts, null = off) → void
            hotswap.setVoutUvWarn(9.0);                                                    // VOUT under-voltage warning, (volts, null = off) → void
            hotswap.setOtWarn(100.0);                                                      // over-temperature warning, (celsius, null = off) → void
            hotswap.setOtFault(125.0);                                                     // over-temperature fault, (celsius, null = off) → void
            hotswap.setIinWarn(20.0);                                                      // input over-current warning, (amps, null = off) → void
            hotswap.setPinWarn(200.0);                                                     // input over-power warning, (watts, null = off) → void
            System.out.println(hotswap.getLimits().vinUvWarn);                                        // all thresholds, () → Limits (null = disabled)
            System.out.println(hotswap.statusByte());                                                 // STATUS_BYTE, () → int
            System.out.println(hotswap.statusWord());                                                 // STATUS_WORD, () → int
            System.out.println(hotswap.statusVout());                                                 // STATUS_VOUT, () → int
            System.out.println(hotswap.statusInput());                                                // STATUS_INPUT, () → int
            System.out.println(hotswap.statusTemperature());                                          // STATUS_TEMPERATURE, () → int
            System.out.println(hotswap.statusCml());                                                  // STATUS_CML, () → int
            System.out.println(hotswap.statusMfr());                                                  // STATUS_MFR_SPECIFIC, () → int
            System.out.println(hotswap.diagnosticWord());                                             // READ_DIAGNOSTIC_WORD, () → int
            hotswap.clearFaults();                                                         // CLEAR_FAULTS (does not turn the MOSFET on), () → void
            System.out.println(hotswap.isOn());                                                       // MOSFET on, () → boolean
            // hotswap.operationOff();                                                     // CUTS THE LOAD RAIL, () → void
            // hotswap.operationOn();                                                      // switch the MOSFET on, () → void
            System.out.println(hotswap.deviceSetup());                                                // DEVICE_SETUP, () → int
            // hotswap.configureDevice(7, false, null);                                    // override RETRY/CL/CB pins, (retryCode, currentLimitHigh, cbRatioHigh) → void
            // hotswap.setGateMask(0x04);                                                  // DANGEROUS: disable fault shut-off, (mask bits 5/4/2) → void
            System.out.println(hotswap.getGateMask());                                                // GATE_MASK, () → int
            System.out.println(hotswap.capability());                                                 // CAPABILITY, () → int
            System.out.println(hotswap.manufacturerId());                                             // MFR_ID, () → String
            System.out.println(hotswap.model());                                                      // MFR_MODEL, () → String
            System.out.println(hotswap.revision());                                                   // MFR_REVISION, () → String
            hotswap.enableInterrupt(LM25066Full.SOURCE_VIN_UV_WARN);                       // unmask an alert source, (source) → void
            hotswap.disableInterrupt(LM25066Full.SOURCE_VIN_UV_WARN);                      // mask an alert source, (source) → void
            System.out.println(hotswap.pollInterrupt());                                              // diagnostic word, () → int
            hotswap.onInterrupt(diag -> System.out.println("alert " + diag));              // subscribe to SMBA, (callback) → void
            Thread.sleep(2000);
            hotswap.offInterrupt();                                                        // unsubscribe, () → void
        }
    }
}
