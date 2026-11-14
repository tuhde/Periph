'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { SHT31Minimal } = require('../../../src/chips/humidity/sht31');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x44', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x44) → Connection
const sensor = new SHT31Minimal(connection);                             // Create SHT31 driver, (connection) → void

async function main() {
    while (true) {
        const [t, rh] = await sensor.read();                             // Read temperature and humidity, () → [number °C, number %RH]
        console.log('%s C, %s %%RH', t.toFixed(2), rh.toFixed(1));
        await sleep(2000);
    }
}

main().catch(err => { console.error(err); process.exit(1); });
