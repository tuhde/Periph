'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { Si1132Minimal } = require('../../../src/chips/light/si1132');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x60', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x60) → Connection
const sensor = new Si1132Minimal(connection);                                 // Create Si1132 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Verify PART_ID and start forced mode, () → void
    while (true) {
        const m = await sensor.read();                                   // Read UV index and ALS counts, () → {uv_index: number, visible: number counts, infrared: number counts}
        console.log('UV %s, visible %d, infrared %d', m.uv_index.toFixed(2), m.visible, m.infrared);
        await sleep(1000);
    }
}

main().catch(err => { console.error(err); process.exit(1); });
