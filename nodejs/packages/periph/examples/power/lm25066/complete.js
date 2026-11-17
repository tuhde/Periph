'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { LM25066Full } = require('../../../src/chips/power/lm25066');

async function main() {
    const connection = new I2CConnection(1, 0x40);              // Create I²C connection, (bus, addr=0x40) → Connection
    const hotswap = new LM25066Full(connection, 1.0, false);    // Create LM25066 driver, (connection, rSenseMohm, currentLimitHigh)

    console.log(await hotswap.voltageIn());                                      // Input voltage, () → number V
    console.log(await hotswap.voltageOut());                                     // Output voltage, () → number V
    console.log(await hotswap.current());                                        // Input current, () → number A
    console.log(await hotswap.power());                                          // Input power, () → number W
    console.log(await hotswap.temperature());                                    // MOSFET temperature, () → number °C
    console.log(await hotswap.voltageAux());                                     // VAUX pin voltage, () → number V (0 ... 1.16)
    console.log(await hotswap.readBlock());                                      // Time-aligned BLOCK_READ, () → {diagnostic, current, voltageOut, voltageIn, power, temperature}
    console.log(await hotswap.readAverage());                                    // AVG_BLOCK_READ, () → object
    console.log(await hotswap.blackBox());                                       // Snapshot at first SMBA, () → object
    console.log(await hotswap.voltageInAvg(), await hotswap.voltageOutAvg());    // Averaged VIN / VOUT, () → number V each
    console.log(await hotswap.currentAvg(), await hotswap.powerAvg());           // Averaged IIN / PIN, () → number each
    console.log(await hotswap.powerPeak());                                      // Peak input power, () → number W
    await hotswap.clearPowerPeak();                                              // Reset the peak register, () → Promise<void>
    await hotswap.setAveraging(6);                                               // Average 2^6 samples, (exponent 0-12) → Promise<void>
    console.log(await hotswap.getAveraging());                                   // () → number
    await hotswap.setVinUvWarn(10.0);                                            // VIN under-voltage warning, (volts | null) → Promise<void>
    await hotswap.setVinOvWarn(15.0);                                            // VIN over-voltage warning, (volts | null) → Promise<void>
    await hotswap.setVoutUvWarn(9.0);                                            // VOUT under-voltage warning, (volts | null) → Promise<void>
    await hotswap.setOtWarn(100.0);                                              // Over-temperature warning, (celsius | null) → Promise<void>
    await hotswap.setOtFault(125.0);                                             // Over-temperature fault, (celsius | null) → Promise<void>
    await hotswap.setIinWarn(20.0);                                              // Input over-current warning, (amps | null) → Promise<void>
    await hotswap.setPinWarn(200.0);                                             // Input over-power warning, (watts | null) → Promise<void>
    console.log(await hotswap.getLimits());                                      // All thresholds, () → object (null = disabled)
    console.log(await hotswap.statusByte(), await hotswap.statusWord());         // STATUS_BYTE / STATUS_WORD, () → number each
    console.log(await hotswap.statusVout(), await hotswap.statusInput());        // Per-group status bytes, () → number each
    console.log(await hotswap.statusTemperature(), await hotswap.statusCml(), await hotswap.statusMfr()); // STATUS_TEMPERATURE / _CML / _MFR_SPECIFIC
    console.log(await hotswap.diagnosticWord());                                 // READ_DIAGNOSTIC_WORD, () → number
    await hotswap.clearFaults();                                                 // CLEAR_FAULTS (does not turn the MOSFET on), () → Promise<void>
    console.log(await hotswap.isOn());                                           // MOSFET on, () → boolean
    // await hotswap.operationOff();                                             // CUTS THE LOAD RAIL, () → Promise<void>
    // await hotswap.operationOn();                                              // Switch the MOSFET on, () → Promise<void>
    console.log(await hotswap.deviceSetup());                                    // DEVICE_SETUP, () → number
    // await hotswap.configureDevice({ retryCode: 7, currentLimitHigh: false }); // Override RETRY/CL/CB pins, ({retryCode, currentLimitHigh, cbRatioHigh})
    // await hotswap.setGateMask(0x04);                                          // DANGEROUS: disable fault shut-off, (mask bits 5/4/2) → Promise<void>
    console.log(await hotswap.getGateMask(), await hotswap.capability());        // GATE_MASK / CAPABILITY, () → number each
    console.log(await hotswap.manufacturerId(), await hotswap.model(), await hotswap.revision()); // MFR_ID / MFR_MODEL / MFR_REVISION, () → string each
    await hotswap.enableInterrupt(LM25066Full.SOURCE_VIN_UV_WARN);               // Unmask an alert source, (source) → Promise<void>
    await hotswap.disableInterrupt(LM25066Full.SOURCE_VIN_UV_WARN);              // Mask an alert source, (source) → Promise<void>
    console.log(await hotswap.pollInterrupt());                                  // Diagnostic word, () → number
    await hotswap.onInterrupt((diag) => console.log("alert", diag));             // Subscribe to SMBA, (callback, {intPin}) → Promise<void>
    await hotswap.offInterrupt();                                                // Unsubscribe, () → Promise<void>
    await connection.close();
}

main();
