'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { Si1145Minimal } = require('../../../src/chips/light/si1145');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x60', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x60) → Connection
const sensor = new Si1145Minimal(connection);                            // Create Si1145 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Check PART_ID, HW_KEY, UV + ALS + PS1, () → void
    while (true) {
        const m = await sensor.read();                                   // Forced PS + ALS, () → {proximity, uv_index, visible, infrared}
        console.log('prox %d  UV %s  vis %d  ir %d', m.proximity, m.uv_index.toFixed(2), m.visible, m.infrared);
        await sleep(1000);
    }
}

main().catch(err => { console.error(err); process.exit(1); });
