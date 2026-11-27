'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { PN532Minimal } = require('../../../src/chips/rfid/pn532');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x24', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x24) → Connection
const reader = new PN532Minimal(connection);                             // Create PN532 driver, (connection) → void

async function main() {
    await reader.init();                                                 // Wake, check firmware, SAM normal mode, finite retries, () → void
    while (true) {
        const uid = await reader.readUid();                              // Detect a type A card, (timeout=1.0 s) → Buffer | null
        console.log('UID', uid ? uid.toString('hex').toUpperCase() : null);
        await sleep(1000);
    }
}

main().catch(err => { console.error(err); process.exit(1); });
