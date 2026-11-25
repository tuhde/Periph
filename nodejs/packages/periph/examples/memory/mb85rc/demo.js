'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { MB85RCFull } = require('../../../src/chips/memory/mb85rc');

async function main() {
    const connection = new I2CConnection(1, 0x50, null, null, 2);                // Create I2C connection, (bus=1, addr=0x50, intPin=null, enPin=null, regBytes=2) → Connection
    const fram = new MB85RCFull(connection);                                     // Create MB85RC driver, (connection) → void

    // --- Persistent boot counter ---
    // A 4-byte big-endian counter at 0x0000 survives power loss. FRAM has no
    // write cycle, so the increment is committed the moment the write ends.
    const counter = (await fram.read(0x0000, 4)).readUInt32BE(0) + 1;            // Sequential read, (address, length) → Buffer
    const cbuf = Buffer.alloc(4);
    cbuf.writeUInt32BE(counter, 0);
    await fram.write(0x0000, cbuf);                                              // Sequential write, (address, data) → void
    console.log('Boot count:', counter);

    // --- Ring-buffer logger, back-to-back with no delays ---
    // 1,000 records of 16 bytes (index + counter + padding) are written one
    // after the other without any pause — an EEPROM would need ACK polling
    // after every page, FRAM does not.
    const LOG_BASE = 0x0100, RECORD = 16, COUNT = 1000, RING = 200;
    const t0 = Date.now();
    for (let i = 0; i < COUNT; i++) {
        const rec = Buffer.alloc(RECORD);
        rec.writeUInt32BE(i, 0);
        rec.writeUInt32BE(counter, 4);
        await fram.write(LOG_BASE + (i % RING) * RECORD, rec);                   // Sequential write, (address, data) → void
    }
    const dt = (Date.now() - t0) / 1000;
    console.log('Logged', COUNT * RECORD, 'bytes in', dt.toFixed(3), 's =', Math.round(COUNT * RECORD / dt), 'B/s');

    // --- Verify the last five records ---
    // Read back the newest records from the ring and check their contents.
    let ok = true;
    for (let i = COUNT - 5; i < COUNT; i++) {
        const rec = await fram.read(LOG_BASE + (i % RING) * RECORD, RECORD);     // Sequential read, (address, length) → Buffer
        if (rec.readUInt32BE(0) !== i || rec.readUInt32BE(4) !== counter) ok = false;
    }
    console.log('Last 5 records verified:', ok);

    // --- Clear the log area ---
    // fill() zeroes the whole ring in chunked transactions.
    await fram.fill(LOG_BASE, RING * RECORD, 0);                                 // Fill a range, (address, length, value=0) → void
    console.log('Log cleared; array size:', fram.size(), 'bytes');               // Get array size, () → number

    // --- Device ID (needs the reserved address 0x7C) ---
    // Some hosts (Linux i2c-dev) refuse addresses 0x78-0x7F; skip then.
    try {
        const idConnection = new I2CConnection(1, 0x7C, null, null, 1);          // Create Device ID connection, (bus=1, addr=0x7C, intPin=null, enPin=null, regBytes=1) → Connection
        console.log('Device ID:', await fram.readDeviceId(idConnection, 0x50));   // Read Device ID, (idConnection, address=0x50) → {manufacturer, density, product}
    } catch (e) {
        console.log('Device ID not available');
    }
}

main().catch(console.error);
