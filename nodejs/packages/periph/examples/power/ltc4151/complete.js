'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { LTC4151Full } = require('../../../src/chips/power/ltc4151');

const connection = new I2CConnection(1, 0x6A);                            // Create I²C connection, (bus=1, addr=0x6A) → Connection
const ltc = new LTC4151Full(connection, 0.02);                            // Create LTC4151 driver, (connection, rSense=0.02 Ω) → void

(async () => {
    console.log(await ltc.voltage());                                     // Read supply voltage, () → number V
                                                                          // VIN code × 25 mV
    console.log(await ltc.current());                                     // Read load current, () → number A
                                                                          // sense voltage / rSense
    console.log(await ltc.power());                                       // Read load power, () → number W
                                                                          // one block read of SENSE and VIN
    console.log(await ltc.senseVoltage());                                // Read SENSE+ − SENSE−, () → number V
    console.log(await ltc.adinVoltage());                                 // Read ADIN pin voltage, () → number V
    console.log(await ltc.readAll());                                     // Consistent read of all channels, () → {vin, senseV, current, power, adin}
    console.log(await ltc.readRaw(LTC4151Full.VIN));                      // Read raw 12-bit code, (channel 0–2) → number

    ltc.setSenseResistor(0.01);                                           // Change sense resistor, (ohms) → void
    console.log(ltc.getSenseResistor());                                  // Read sense resistor, () → number Ω
    ltc.setSenseResistor(0.02);

    console.log(await ltc.snapshot(LTC4151Full.SENSE));                   // One snapshot conversion, (channel 0–2) → number V
                                                                          // returns to continuous scan afterwards
    console.log(await ltc.isSnapshotMode());                              // Snapshot mode active, () → boolean
    console.log(await ltc.isBusy(LTC4151Full.VIN));                       // Channel busy, (channel 0–2) → boolean
    await ltc.setContinuous();                                            // Back to continuous scan, () → void

    await ltc.setStuckBusTimer(true);                                     // Stuck-bus reset timer, (enabled) → void
    await ltc.setPageRw(true);                                            // I²C page read/write, (enabled) → void
    console.log('0x' + (await ltc.readControl()).toString(16));           // Read CONTROL, () → number
    await ltc.writeControl(0x0C);                                         // Write CONTROL, (value) → void
                                                                          // test mode bit 4 is always masked to 0

    console.log(LTC4151Full.temperatureFromAdin(900, 1200));              // NTC example circuit, (adinCode, vinCode) → number °C

    await connection.close();
})();
