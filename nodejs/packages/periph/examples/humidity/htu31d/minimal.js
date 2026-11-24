'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { HTU31DMinimal } = require('../../../src/chips/humidity/htu31d');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x40', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x40) → Connection
const sensor = new HTU31DMinimal(connection);                            // Create HTU31D driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Soft reset, () → void
    while (true) {
        const m = await sensor.read();                                   // Read temperature and humidity, () → {temperature_c: number °C, humidity_pct: number %RH}
        console.log('%s C, %s %%RH', m.temperature_c.toFixed(2), m.humidity_pct.toFixed(1));
        await sleep(2000);
    }
}

main().catch(err => { console.error(err); process.exit(1); });
