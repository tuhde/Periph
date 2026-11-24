'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { BH1750Minimal } = require('../../../src/chips/light/bh1750');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x23', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x23) → Connection
const sensor = new BH1750Minimal(connection);                            // Create BH1750 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Power on, () → void
    while (true) {
        const lux = await sensor.read();                                 // Measure illuminance, () → number lx
        console.log('%s lx', lux.toFixed(1));
        await sleep(1000);
    }
}

main().catch(err => { console.error(err); process.exit(1); });
