'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { LM25066Full } = require('../../../src/chips/power/lm25066');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
    const connection = new I2CConnection(1, 0x40);              // Create I²C connection, (bus, addr=0x40) → Connection
    const hotswap = new LM25066Full(connection, 1.0, false);    // Create LM25066 driver, 12 V rail with 1 mΩ sense resistor

    console.log(await hotswap.manufacturerId(), await hotswap.model(), await hotswap.revision());   // Identify the chip, () → string each
    await hotswap.clearFaults();                                // Release SMBA after power-up, () → Promise<void>

    // Hot-swap rail monitor: one line per second for a minute
    for (let i = 0; i < 60; i++) {
        if (i === 30) {
            await hotswap.setAveraging(6);                      // 64-sample averaging, (exponent) → Promise<void>
            await hotswap.setVinUvWarn(10.0);                   // VIN under-voltage warning at 10 V, (volts) → Promise<void>
            await hotswap.setIinWarn(20.0);                     // Input over-current warning at 20 A, (amps) → Promise<void>
            await hotswap.enableInterrupt(LM25066Full.SOURCE_VIN_UV_WARN | LM25066Full.SOURCE_IIN_WARN);   // Unmask both alerts, (source)
        }
        console.log(`VIN ${(await hotswap.voltageIn()).toFixed(2)} V  VOUT ${(await hotswap.voltageOut()).toFixed(2)} V  ` +
            `IIN ${(await hotswap.current()).toFixed(3)} A (avg ${(await hotswap.currentAvg()).toFixed(3)})  ` +
            `PIN ${(await hotswap.power()).toFixed(2)} W  T ${(await hotswap.temperature()).toFixed(1)} C`);
        await sleep(1000);
    }

    console.log(`peak power ${(await hotswap.powerPeak()).toFixed(2)} W`);          // Peak input power, () → number W
    console.log('diagnostic 0x' + (await hotswap.pollInterrupt()).toString(16));    // Alert flags, () → number
    await hotswap.clearFaults();                                // Finish servicing, () → Promise<void>

    if (process.argv.includes('--control')) {
        await hotswap.operationOff();                           // CUTS THE LOAD RAIL, () → Promise<void>
        await sleep(1000);
        await hotswap.operationOn();                            // Switch the MOSFET back on, () → Promise<void>
    }
    await connection.close();
}

main();
