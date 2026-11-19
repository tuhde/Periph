'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { HS30xxMinimal } = require('../../../src/chips/humidity/hs30xx');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x44', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x44) → Connection
const sensor = new HS30xxMinimal(connection);                            // Create HS30xx driver, (connection) → void

async function main() {
    while (true) {
        const d = await sensor.read();                                   // Read temperature and humidity, () → Promise<{temperature_c, humidity_pct}>
        console.log('%s C, %s %%RH', d.temperature_c.toFixed(2), d.humidity_pct.toFixed(1));
        await sleep(2000);
    }
}

main().catch(err => { console.error(err); process.exit(1); });
