"use strict";
// Wiring: SPI0 (MOSI, MISO, SCK, CE0) to the MX25L12845E, 3.3 V. Override with SPI_BUS, SPI_DEV.
// All erases and writes stay inside the last 64 KiB of the chip (0xFF0000..0xFFFFFF).
const { SPIConnection } = require('../../../src/connection/spi');                                // SPIConnection class, (bus, dev, options) → SPIConnection
const { MX25L12845EFull } = require('../../../src/chips/memory/mx25l12845e');                           // MX25L12845EFull class, (connection, options) → MX25L12845EFull

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);
const SCRATCH = 0xFF0000;
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const hex = (b) => Buffer.from(b).toString('hex').match(/../g).join(' ');

const RING = 0xFFC000;
const SECTORS = 4;
const SLOT_LEN = 32;
const SLOTS = SECTORS * 4096 / SLOT_LEN;

(async () => {
    // --- Bring up the chip ---
    // Plain single-I/O SPI: init() releases deep power-down, checks the JEDEC ID and
    // waits until the chip is idle. The serial-number area is the factory ESN on parts that have one.
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 0, maxSpeedHz: 8_000_000 });  // Open SPI bus, (busNumber, deviceNumber, mode=0, maxSpeedHz=8e6) → SPIConnection
    const flash = new MX25L12845EFull(connection);                                              // Create MX25L12845E driver, (connection, options) → MX25L12845EFull
    await flash.init();                                                                         // Release deep power-down, check the JEDEC ID, () → Promise<void>
    console.log(`MX25L12845E, ${flash.size() / (1024 * 1024)} MiB, serial ${hex(await flash.serialNumber())}`);  // Read the serial-number OTP area, () → Promise<Buffer>

    // --- Append records to a ring of four sectors ---
    // 32-byte records (sequence, tick, payload) fill a sector, 128 per sector. A blank slot
    // (all 0xFF) needs no erase, so appending is just a page program; only when the writer
    // enters the next sector is that sector erased first - one erase per 128 records.
    for (let s = 0; s < SECTORS; s++) await flash.eraseSector(RING + s * 4096);                 // Erase one 4 KiB sector, (address) → Promise<void>
    const t0 = Date.now();
    let erases = 0;
    let slot = 0;
    for (let seq = 0; seq < 300; seq++) {
        if (slot % 128 === 0 && seq >= SLOTS) {                                                 // wrapped into the next sector: make it blank
            await flash.eraseSector(RING + Math.floor(slot / 128) * 4096);
            erases++;
        }
        const rec = Buffer.alloc(SLOT_LEN, 0xA5);
        rec.writeUInt32LE(seq, 0);
        rec.writeUInt32LE(Date.now() - t0, 4);
        await flash.pageProgram(RING + slot * SLOT_LEN, rec);                                   // Program one record (inside a page), (address, data) → Promise<void>
        slot = (slot + 1) % SLOTS;
    }
    console.log(`appended 300 records in ${Date.now() - t0} ms, ${erases} sector erases`);

    // --- Verify by scanning the ring ---
    // Every slot must carry a valid sequence number and the 0xA5 payload.
    let good = 0;
    let newest = 0;
    const all = await flash.readBytes(RING, SECTORS * 4096);                                    // Read bytes, (address, length) → Promise<Buffer>
    for (let s = 0; s < SLOTS; s++) {
        const rec = all.subarray(s * SLOT_LEN, (s + 1) * SLOT_LEN);
        const seq = rec.readUInt32LE(0);
        if (seq !== 0xFFFFFFFF && rec[20] === 0xA5) {
            good++;
            newest = Math.max(newest, seq);
        }
    }
    console.log(`ring holds ${good} valid records, newest sequence number ${newest}`);

    // --- Leave the chip clean ---
    for (let s = 0; s < SECTORS; s++) await flash.eraseSector(RING + s * 4096);
    console.log('ring erased');
})();
