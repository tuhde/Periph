'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { HTS221Minimal } = require('../../../src/chips/humidity/hts221');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x5F', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x5F) → Connection
const sensor = new HTS221Minimal(connection);                            // Create HTS221 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Read calibration and start one-shot mode, () → void
    while (true) {
        const m = await sensor.read();                                   // Read temperature and humidity, () → {temperature_c: number °C, humidity_pct: number %rH}
        console.log('%s C, %s %%rH', m.temperature_c.toFixed(2), m.humidity_pct.toFixed(1));
        await sleep(2000);
    }
}

main().catch(err => { console.error(err); process.exit(1); });
