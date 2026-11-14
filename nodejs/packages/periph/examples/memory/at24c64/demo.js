'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { AT24C64Full } = require('../../../src/chips/memory/at24c64');

async function main() {
    const connection = new I2CConnection(1, 0x50, null, null, 2);                // Create I2C connection, (bus=1, addr=0x50, intPin=null, enPin=null, regBytes=2) → Connection
    const ee = new AT24C64Full(connection);                                    // Create AT24C64 driver, (connection) → void

    // --- Persistent boot counter: 4-byte big-endian value at address 0 ---
    const counter = ((await ee.read(0x0000, 4)).readUInt32BE(0) + 1) >>> 0;      // Sequential read, (address, length) → Buffer
    const cb = Buffer.alloc(4);
    cb.writeUInt32BE(counter, 0);
    await ee.write(0x0000, cb);                                                  // Write data, (address, data) → void
    console.log('Boot counter:', counter);

    // --- Page-write benchmark: 3 pages, 32-byte aligned ---
    const size = 3 * ee.pageSize();                                              // Get write page size, () → number
    const pattern = Buffer.from(Array.from({ length: size }, (_, i) => (i * 7) & 0xFF));
    const t0 = process.hrtime.bigint();
    await ee.write(0x0080, pattern);                                             // Write data, (address, data) → void
    const ms = Number(process.hrtime.bigint() - t0) / 1e6;
    console.log('Wrote %d bytes in %s ms (%s B/s incl. ACK-polled write cycles)', size, ms.toFixed(1), Math.round(size / (ms / 1000)));
    console.log('Read-back matches:', (await ee.read(0x0080, size)).equals(pattern)); // Sequential read, (address, length) → Buffer

    // --- A write that crosses a page boundary is refused ---
    try {
        await ee.writePage(0x00FC, Buffer.alloc(8));                             // Write within one page, (address, data) → void
    } catch (e) {
        console.log('writePage refused:', e.message);
    }

    await ee.fill(0x0080, size);                                                 // Fill a range, (address, length, value=0) → void
    console.log('Zeroed:', (await ee.read(0x0080, size)).equals(Buffer.alloc(size)));
    console.log('Size', ee.size(), 'ready', await ee.waitReady(10));             // Get array size, () → number ; ACK-poll the chip, (timeoutMs) → boolean
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
