'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { AT24C64Full } = require('../../../src/chips/memory/at24c64');

async function main() {
    const connection = new I2CConnection(1, 0x50, null, null, 2);                // Create I2C connection, (bus=1, addr=0x50, intPin=null, enPin=null, regBytes=2) → Connection
    const ee = new AT24C64Full(connection);                                    // Create AT24C64 driver, (connection) → void

    console.log('Size:', ee.size(), 'bytes');                                    // Get array size, () → number
                                                                                 // always 8192
    console.log('Page:', ee.pageSize(), 'bytes');                                // Get write page size, () → number
                                                                                 // always 32
    ee.maxChunk = 16;                                                            // Set max bytes per bus transaction, (maxChunk=30) → void
    console.log('maxChunk:', ee.maxChunk);                                       // Get max bytes per bus transaction, () → number

    await ee.writeByte(0x0010, 0xA5);                                            // Write a single byte, (address 0–8191, value 0–255) → void
                                                                                 // ACK-polled until the write cycle ends
    console.log('Byte:', (await ee.readByte(0x0010)).toString(16));              // Read a single byte, (address 0–8191) → number
                                                                                 // random read

    await ee.write(0x0100, Buffer.from(Array.from({ length: 40 }, (_, i) => i))); // Write data, (address, data) → void
                                                                                 // split at 32-byte page boundaries and maxChunk, ACK-polled per chunk
    const data = await ee.read(0x0100, 40);                                      // Sequential read, (address, length) → Buffer
                                                                                 // split into maxChunk-byte transactions
    console.log('Block:', data.toString('hex'));

    await ee.writePage(0x0180, Buffer.from(Array.from({ length: 16 }, (_, i) => i))); // Write within one page, (address, data) → void
                                                                                 // rejects with RangeError if the data crosses a page boundary
    await ee.fill(0x0100, 40, 0xFF);                                             // Fill a range, (address, length, value=0) → void
                                                                                 // page-wise writes of value
    console.log('After fill:', (await ee.read(0x0100, 8)).toString('hex'));

    console.log('Ready:', await ee.waitReady(10));                               // ACK-poll the chip, (timeoutMs) → boolean
                                                                                 // true once the chip answers its address
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
