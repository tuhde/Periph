'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { BH1750Full } = require('../../../src/chips/light/bh1750');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x23', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x23) → Connection
const sensor = new BH1750Full(connection);                               // Create BH1750 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Power on, () → void

    // --- Light meter ---
    // One reading per mode, ten continuous values per second for 5 s (cover and
    // uncover the sensor), the effect of the sensitivity, reset and a power cycle.
    for (const [name, mode] of [['H (1 lx)', BH1750Full.MODE_H], ['H2 (0.5 lx)', BH1750Full.MODE_H2], ['L (4 lx)', BH1750Full.MODE_L]]) {
        const start = Date.now();
        const lux = await sensor.measureOnce(mode);                      // One-time measurement, (mode) → number lx
        console.log(name, lux.toFixed(2), 'lx', Date.now() - start, 'ms');
    }

    await sensor.startContinuous(BH1750Full.MODE_H);                     // Start continuous mode, (mode) → void
    for (let i = 0; i < 50; i++) {
        await sleep(100);
        console.log((await sensor.readContinuous()).toFixed(1), 'lx');   // Read the latest result, () → number lx
    }

    for (const factor of [0.45, 3.68]) {
        await sensor.setSensitivity(factor);                             // Set sensitivity, (factor) → void
        const lux = await sensor.measureOnce(BH1750Full.MODE_H);
        console.log('x' + factor, 'MTreg', sensor.getMeasurementTimeRegister(), 'raw', await sensor.readRaw(), lux.toFixed(2), 'lx', 'max', (sensor.measurementTime() * 1000).toFixed(0), 'ms');
    }
    await sensor.setSensitivity(1.0);

    await sensor.reset();                                                // Clear the data register, () → void
    await sensor.powerDown();                                            // Power down, () → void
    await sensor.powerOn();                                              // Power on, () → void

    // Log-scale bar graph
    for (let i = 0; i < 20; i++) {
        const lux = await sensor.measureOnce(BH1750Full.MODE_H);
        const bar = Math.floor(Math.max(0, Math.log10(Math.max(lux, 1))) * 8);
        console.log(lux.toFixed(1).padStart(9), 'lx |' + '#'.repeat(bar));
        await sleep(500);
    }
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
