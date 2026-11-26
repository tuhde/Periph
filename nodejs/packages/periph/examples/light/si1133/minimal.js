'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { Si1133Minimal } = require('../../../src/chips/light/si1133');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x55', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x55) → Connection
const sensor = new Si1133Minimal(connection);                            // Create Si1133 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Check PART_ID, reset, UV + white channels, () → void
    while (true) {
        const m = await sensor.read();                                   // Forced measurement, () → {uv: number counts, white: number counts}
        console.log('UV %d, white %d', m.uv, m.white);
        await sleep(1000);
    }
}

main().catch(err => { console.error(err); process.exit(1); });
