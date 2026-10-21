'use strict';

const { SPIConnection } = require('../../../src/connection/spi');                    // SPIConnection class, (bus, dev, options) → SPIConnection
const { SRAM23X1024Full } = require('../../../src/chips/memory/_23x1024');                         // SRAM23X1024Full class, (connection) → SRAM23X1024Full

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);

const hex = (v) => v.toString(16).toUpperCase().padStart(2, '0');

(async () => {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 0, maxSpeedHz: 8_000_000 });    // Open SPI bus, (busNumber, deviceNumber, mode=0, maxSpeedHz=8e6) → SPIConnection
    const sram = new SRAM23X1024Full(connection);                                                   // Create 23X1024 Full driver, (connection) → SRAM23X1024Full
    await sram.init();                                                                              // Select Sequential mode and verify it, () → Promise<void>

    // --- Store a 256-entry lookup table in external SRAM ---
    // A sine table is the kind of data a microcontroller with little RAM
    // keeps outside: written once, then read back entry by entry.
    const table = Buffer.from(Array.from({ length: 256 }, (_, i) => Math.floor(127.5 + 127.5 * Math.sin(2 * Math.PI * i / 256))));
    await sram.write(0x00000, table);                                                               // Block write, (address, data) → Promise<void>
    const check = await sram.read(0x00000, table.length);                                           // Block read, (address, length) → Promise<Buffer>
    console.log(`sine table: ${check.equals(table) ? 'ok' : 'MISMATCH'} (sin(64/256 turn) = ${await sram.readByte(64)})`);   // Read one byte, (address) → Promise<number>

    // --- Wipe the whole array and spot-check it ---
    // Zero write time means the 128 KiB fill runs at SPI speed with no
    // waiting; the first, middle and last byte prove the full range.
    await sram.fill(0x00000, sram.size(), 0xA5);                                                    // Fill a range, (address, length, value=0) → Promise<void>
    console.log(`fill spot check: 0x${hex(await sram.readByte(0x00000))} 0x${hex(await sram.readByte(0x10000))} 0x${hex(await sram.readByte(0x1FFFF))}`);   // Read one byte, (address) → Promise<number>

    // --- Page mode: a write that crosses a 32-byte page boundary ---
    // The hardware would wrap inside the page; the driver splits the
    // transfer so the 64 bytes starting at address 16 still read back in order.
    await sram.setMode(SRAM23X1024Full.MODE_PAGE);                                                  // Select operating mode, (mode=MODE_PAGE=0x80) → Promise<void>
    const ramp = Buffer.from(Array.from({ length: 64 }, (_, i) => 0x40 + i));
    await sram.write(16, ramp);                                                                     // Block write, (address, data) → Promise<void>
    const rampBack = await sram.read(16, ramp.length);                                              // Block read, (address, length) → Promise<Buffer>
    console.log('page-crossing write:', rampBack.equals(ramp) ? 'ok' : 'MISMATCH');
    await sram.setMode(SRAM23X1024Full.MODE_SEQUENTIAL);                                            // Select operating mode, (mode=MODE_SEQUENTIAL=0x40) → Promise<void>

    // --- Measure throughput for a 4 KiB transfer ---
    // Sequential mode streams the block in 256-byte transactions;
    // process.hrtime.bigint() times them.
    const block = Buffer.from(Array.from({ length: 4096 }, (_, i) => i & 0xFF));
    const t0 = process.hrtime.bigint();
    await sram.write(0x01000, block);                                                               // Block write, (address, data) → Promise<void>
    const t1 = process.hrtime.bigint();
    await sram.read(0x01000, block.length);                                                         // Block read, (address, length) → Promise<Buffer>
    const t2 = process.hrtime.bigint();
    console.log(`4 KiB write: ${Number(t1 - t0) / 1e6} ms, read: ${Number(t2 - t1) / 1e6} ms`);
    await connection.close();
})();
