'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { CAT24C512Minimal } = require('../../../src/chips/memory/cat24c512');

async function main() {
    const connection = new I2CConnection(1, 0x50, null, null, 2);                // Create I2C connection, (bus=1, addr=0x50, intPin=null, enPin=null, regBytes=2) → Connection
    const ee = new CAT24C512Minimal(connection);                                 // Create CAT24C512 driver, (connection) → void

    await ee.writeByte(0x0000, 0xA5);                                            // Write a single byte, (address 0–65535, value 0–255) → void
    console.log('Byte:', (await ee.readByte(0x0000)).toString(16));              // Read a single byte, (address 0–65535) → number
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
