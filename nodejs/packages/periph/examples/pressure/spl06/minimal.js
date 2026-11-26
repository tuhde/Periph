'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { SPL06Minimal } = require('../../../src/chips/pressure/spl06');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x77', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x77) → Connection
const sensor = new SPL06Minimal(connection);                             // Create SPL06 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Check ID, read coefficients, apply defaults, () → void
    while (true) {
        const m = await sensor.read();                                   // Measure temperature and pressure, () → {temperature_c: number °C, pressure_pa: number Pa}
        console.log('%s Pa, %s C', m.pressure_pa.toFixed(2), m.temperature_c.toFixed(2));
        await sleep(1000);
    }
}

main().catch(err => { console.error(err); process.exit(1); });
