'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { AS7262Minimal } = require('../../../src/chips/color/as7262');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x49', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x49) → Connection
const sensor = new AS7262Minimal(connection);                                         // Create AS7262 driver, (connection) → void

async function main() {
    await sensor.init();                                                     // Reset and configure defaults, () → Promise<void>
    while (true) {
        const d = await sensor.read();                                       // Read six calibrated channels, () → Promise<{violet, blue, green, yellow, orange, red}>
        console.log(d);
        console.log((await sensor.temperature()) + ' C');                    // Read device temperature, () → Promise<number> °C
        await sleep(2000);
    }
}

main().catch(err => { console.error(err); process.exit(1); });
