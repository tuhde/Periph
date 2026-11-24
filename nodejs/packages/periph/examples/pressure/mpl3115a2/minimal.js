'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { MPL3115A2Minimal } = require('../../../src/chips/pressure/mpl3115a2');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x60', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x60) → Connection
const sensor = new MPL3115A2Minimal(connection);                                       // Create MPL3115A2 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Reset and apply defaults, () → void
    while (true) {
        const m = await sensor.read();                                   // Read pressure and temperature, () → {pressure_pa: number Pa, temperature_c: number °C}
        console.log('%s Pa, %s C', m.pressure_pa.toFixed(2), m.temperature_c.toFixed(2));
        await sleep(2000);
    }
}

main().catch(err => { console.error(err); process.exit(1); });
