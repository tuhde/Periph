'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { AT24C02NMinimal } = require('../../../src/chips/memory/at24c02n');

async function main() {
    const connection = new I2CConnection(1, 0x50);                // Create I2C connection, (bus=1, addr=0x50) → Connection
    const ee = new AT24C02NMinimal(connection);                                 // Create AT24C02N driver, (connection) → void

    await ee.writeByte(0x0000, 0xA5);                                            // Write a single byte, (address 0–255, value 0–255) → void
    console.log('Byte:', (await ee.readByte(0x0000)).toString(16));              // Read a single byte, (address 0–255) → number
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
