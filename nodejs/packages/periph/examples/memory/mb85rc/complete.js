'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { MB85RCFull } = require('../../../src/chips/memory/mb85rc');

async function main() {
    const connection = new I2CConnection(1, 0x50, null, null, 2);                // Create I2C connection, (bus=1, addr=0x50, intPin=null, enPin=null, regBytes=2) → Connection
    const fram = new MB85RCFull(connection);                                     // Create MB85RC driver, (connection) → void

    console.log('Size:', fram.size(), 'bytes');                                  // Get array size, () → number
                                                                                 // always 32768 for the MB85RC256V
    fram.maxChunk = 16;                                                          // Set max bytes per bus transaction, (maxChunk=30) → void
    console.log('maxChunk:', fram.maxChunk);                                     // Get max bytes per bus transaction, () → number

    await fram.writeByte(0x0010, 0xA5);                                          // Write a single byte, (address 0–32767, value 0–255) → void
                                                                                 // no write cycle, no polling
    console.log('Byte:', (await fram.readByte(0x0010)).toString(16));            // Read a single byte, (address 0–32767) → number
                                                                                 // random read

    await fram.write(0x0100, Buffer.from(Array.from({ length: 40 }, (_, i) => i))); // Sequential write, (address, data) → void
                                                                                 // split into maxChunk-byte transactions
    const data = await fram.read(0x0100, 40);                                    // Sequential read, (address, length) → Buffer
                                                                                 // split into maxChunk-byte transactions
    console.log('Block:', data.toString('hex'));

    await fram.fill(0x0100, 40, 0xFF);                                           // Fill a range, (address, length, value=0) → void
                                                                                 // writes value over the range in chunks
    console.log('After fill:', (await fram.read(0x0100, 8)).toString('hex'));

    try {
        const idConnection = new I2CConnection(1, 0x7C, null, null, 1);          // Create Device ID connection, (bus=1, addr=0x7C, intPin=null, enPin=null, regBytes=1) → Connection
        const id = await fram.readDeviceId(idConnection, 0x50);                  // Read Device ID, (idConnection, address=0x50) → {manufacturer, density, product}
                                                                                 // reads 3 bytes via reserved slave address 0x7C
        console.log('Device ID:', id);
    } catch (e) {
        console.log('Device ID not readable on this bus');
    }
}

main().catch(console.error);
