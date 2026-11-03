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
                                                                                                    // writes MODE=0x40 and reads it back with RDMR

    console.log(`size: ${sram.size()} bytes`);                                                      // Array size, () → number bytes
                                                                                                    // always 131072

    await sram.writeByte(0x00010, 0xA5);                                                            // Write one byte, (address 0–131071, value 0–255) → Promise<void>
                                                                                                    // WRITE 0x02 + 24-bit address + data
    console.log(`byte @0x10: 0x${hex(await sram.readByte(0x00010))}`);                              // Read one byte, (address 0–131071) → Promise<number>
                                                                                                    // READ 0x03 + 24-bit address

    const pattern = Buffer.from(Array.from({ length: 300 }, (_, i) => i & 0xFF));
    await sram.write(0x00100, pattern);                                                             // Block write, (address, data) → Promise<void>
                                                                                                    // split into transactions of at most maxChunk bytes
    const readback = await sram.read(0x00100, pattern.length);                                      // Block read, (address, length) → Promise<Buffer>
                                                                                                    // sequential read across the chunk boundary
    console.log('block of 300:', readback.equals(pattern) ? 'match' : 'MISMATCH');

    await sram.fill(0x00200, 64, 0xFF);                                                             // Fill a range, (address, length, value=0) → Promise<void>
                                                                                                    // writes value over the range, chunked
    console.log(`fill @0x200: 0x${hex(await sram.readByte(0x00200))}`);

    sram.maxChunk = 64;                                                                             // Limit bytes per transaction, (n 1–256)
                                                                                                    // smaller frames for bus layers with small buffers
    console.log('maxChunk:', sram.maxChunk);
    sram.maxChunk = 256;                                                                            // Limit bytes per transaction, (n 1–256)

    await sram.setMode(SRAM23X1024Full.MODE_PAGE);                                                  // Select operating mode, (mode=MODE_PAGE=0x80) → Promise<void>
                                                                                                    // WRMR then RDMR check; accesses now wrap within 32-byte pages
    console.log(`mode: 0x${hex(await sram.getMode())}`);                                            // Read operating mode, () → Promise<number>
                                                                                                    // RDMR bits 7:6
    await sram.write(0x00110, pattern.subarray(0, 64));                                             // Block write, (address, data) → Promise<void>
                                                                                                    // the driver splits at page boundaries, so the data stays linear
    const paged = await sram.read(0x00110, 64);                                                     // Block read, (address, length) → Promise<Buffer>
    console.log('page mode 64 bytes:', paged.equals(pattern.subarray(0, 64)) ? 'match' : 'MISMATCH');

    await sram.setMode(SRAM23X1024Full.MODE_SEQUENTIAL);                                            // Select operating mode, (mode=MODE_SEQUENTIAL=0x40) → Promise<void>
    await sram.resetIo();                                                                           // Return to SPI mode, () → Promise<void>
                                                                                                    // sends RSTIO (0xFF); harmless while already in SPI mode
    await connection.close();
})();
