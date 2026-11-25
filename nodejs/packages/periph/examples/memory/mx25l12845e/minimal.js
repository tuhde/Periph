"use strict";
// Wiring: SPI0 (MOSI, MISO, SCK, CE0) to the MX25L12845E, 3.3 V. Override with SPI_BUS, SPI_DEV.
// All erases and writes stay inside the last 64 KiB of the chip (0xFF0000..0xFFFFFF).
const { SPIConnection } = require('../../../src/connection/spi');                                // SPIConnection class, (bus, dev, options) → SPIConnection
const { MX25L12845EMinimal } = require('../../../src/chips/memory/mx25l12845e');                           // MX25L12845EMinimal class, (connection, options) → MX25L12845EMinimal

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);
const SCRATCH = 0xFF0000;
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const hex = (b) => Buffer.from(b).toString('hex').match(/../g).join(' ');

(async () => {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 0, maxSpeedHz: 8_000_000 });  // Open SPI bus, (busNumber, deviceNumber, mode=0, maxSpeedHz=8e6) → SPIConnection
    const flash = new MX25L12845EMinimal(connection);                                           // Create MX25L12845E driver, (connection, options) → MX25L12845EMinimal
    await flash.init();                                                                         // Release deep power-down, check the JEDEC ID, () → Promise<void>

    console.log(`JEDEC ID ${hex(await flash.jedecId())}, ${flash.size() / (1024 * 1024)} MiB`);  // Read JEDEC ID, () → Promise<Buffer>; capacity in bytes, () → number
    for (let counter = 0; counter < 10; counter++) {
        await flash.writeBytes(SCRATCH, Buffer.from([counter]));                                // Write bytes (erases the sector when needed), (address, data) → Promise<void>
        const value = (await flash.readBytes(SCRATCH, 1))[0];                                   // Read bytes, (address, length) → Promise<Buffer>
        console.log(`wrote 0x${counter.toString(16).padStart(2, '0')}, read 0x${value.toString(16).padStart(2, '0')}`);
        await sleep(500);
    }
    await flash.eraseSector(SCRATCH);                                                           // Erase one 4 KiB sector, (address) → Promise<void>
    console.log('done, scratch sector erased');
})();
