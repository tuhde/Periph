"use strict";
// Wiring: SPI0 (MOSI, MISO, SCK) plus a free GPIO for the card's chip select (default line 25 on /dev/gpiochip0):
// the kernel cannot hold chip select across calls, so the connection drives CS itself (spi-device noChipSelect).
// Override with SPI_BUS, SPI_DEV, GPIOCHIP, CS_LINE. 3.3 V cards only.
const { Default } = require('opengpio');                                               // opengpio Default chip factory
const { SPIConnection } = require('../../../src/connection/spi');                                 // SPIConnection class, (bus, dev, options) → SPIConnection
const { GpioOutputPin } = require('../../../src/connection/output_pin');                          // GpioOutputPin class, (output) → GpioOutputPin
const { SDSPIFull } = require('../../../src/chips/memory/sd_spi');                                      // SDSPIFull class, (connection, options) → SDSPIFull

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);
const GPIOCHIP = process.env.GPIOCHIP || '/dev/gpiochip0';
const CS_LINE = parseInt(process.env.CS_LINE || '25', 10);

const open = () => new SPIConnection(SPI_BUS, SPI_DEV, {                                 // Open SPI bus with GPIO chip select, (busNumber, deviceNumber, mode, maxSpeedHz, csPin) → SPIConnection
    mode: 0,
    maxSpeedHz: 400_000,                                                                 // SD init needs <= 400 kHz; the driver raises it afterwards
    csPin: new GpioOutputPin(Default.output({ chip: GPIOCHIP, line: CS_LINE })),
});

const BLOCKS = 64;

(async () => {
    // --- Bring up the card ---
    // The card starts in SD mode; the driver sends 80 clocks with CS high, CMD0 with CS
    // low to enter SPI mode, then negotiates the card type. SCK runs at 400 kHz during
    // init and is raised to 12 MHz afterwards.
    const sd = new SDSPIFull(open());                                                    // Create SD driver, (connection, options) → SDSPIFull
    await sd.init();                                                                     // Run the SPI-mode init sequence, () → Promise<void>
    const info = sd.info();                                                              // Geometry and capabilities, () → object
    console.log(`card ${info.name}, ${info.blockCount / 2048} MiB`);

    // --- Log samples to a raw block range (filesystem-free data logger) ---
    // Each 512-byte block holds a header (sequence number, tick) and 126 samples.
    // The last blocks of the card are used and restored afterwards, so a card that
    // carries a filesystem keeps its data (only the tail of the volume is touched).
    const first = sd.blockCount() - BLOCKS;
    const saved = await sd.read(first, BLOCKS);                                          // Read blocks, (block, count) → Promise<Buffer>
    const t0 = Date.now();
    for (let seq = 0; seq < BLOCKS; seq++) {
        const block = Buffer.alloc(512);
        block.writeUInt32LE(seq, 0);
        block.writeUInt32LE((Date.now() - t0) >>> 0, 4);
        for (let i = 2; i < 128; i++) block.writeUInt32LE(seq * 1000 + i, i * 4);
        await sd.write(first + seq, block);                                              // Write blocks, (block, data) → Promise<void>
    }
    await sd.sync();                                                                     // Wait until programming is done, () → Promise<void>
    console.log(`wrote ${BLOCKS} blocks in ${Date.now() - t0} ms`);

    // --- Verify by reading back ---
    // Read every block back and recompute the expected sample values; any single
    // mismatch (or a CRC16 error thrown by the driver) is counted.
    let bad = 0;
    for (let seq = 0; seq < BLOCKS; seq++) {
        let block;
        try { block = await sd.read(first + seq, 1); } catch (_e) { bad++; continue; }  // Read blocks, (block, count) → Promise<Buffer>
        let ok = block.readUInt32LE(0) === seq;
        for (let i = 2; i < 128 && ok; i++) ok = block.readUInt32LE(i * 4) === seq * 1000 + i;
        if (!ok) bad++;
    }
    console.log(`verify: ${bad} bad blocks`);

    // --- Restore the original contents ---
    await sd.write(first, saved);
    await sd.sync();
    console.log('restored');
})();
