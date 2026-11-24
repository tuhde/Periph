'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { SHTC3Minimal } = require('../../../src/chips/humidity/shtc3');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x70', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x70) → Connection
const sensor = new SHTC3Minimal(connection);                            // Create SHTC3 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Soft reset and ID check, () → void
    while (true) {
        const m = await sensor.read();                                   // Read temperature and humidity, () → {temperature_c: number °C, humidity_pct: number %RH}
        console.log('%s C, %s %%RH', m.temperature_c.toFixed(2), m.humidity_pct.toFixed(1));
        await sleep(2000);
    }
}

main().catch(err => { console.error(err); process.exit(1); });
