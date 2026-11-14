'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { AM2315Minimal } = require('../../../src/chips/environmental/am2315');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x5C', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x5C) → Connection
const sensor = new AM2315Minimal(connection);                             // Create AM2315 driver, (connection) → void

async function main() {
    while (true) {
        const r = await sensor.read();                                   // Read temperature and humidity, () → {temperature_c, humidity_pct}
        console.log('%s C, %s %%RH', r.temperature_c.toFixed(1), r.humidity_pct.toFixed(1));
        await sleep(2000);
    }
}

main().catch(err => { console.error(err); process.exit(1); });
