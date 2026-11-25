'use strict';

const { SPIConnection } = require('../../../src/connection/spi');                    // SPIConnection class, (bus, dev, options) → SPIConnection
const { SRAM23X1024Minimal } = require('../../../src/chips/memory/_23x1024');                         // SRAM23X1024Minimal class, (connection) → SRAM23X1024Minimal

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);

(async () => {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 0, maxSpeedHz: 8_000_000 });    // Open SPI bus, (busNumber, deviceNumber, mode=0, maxSpeedHz=8e6) → SPIConnection
    const sram = new SRAM23X1024Minimal(connection);                                                // Create 23X1024 driver, (connection) → SRAM23X1024Minimal
    await sram.init();                                                                              // Select Sequential mode and verify it, () → Promise<void>

    let counter = 0;
    while (true) {
        await sram.writeByte(0x00000, counter);                                                     // Write one byte, (address 0–131071, value 0–255) → Promise<void>
        const value = await sram.readByte(0x00000);                                                 // Read one byte, (address 0–131071) → Promise<number>
        console.log(`wrote 0x${counter.toString(16).padStart(2, '0')}, read 0x${value.toString(16).padStart(2, '0')}`);
        counter = (counter + 1) & 0xFF;
        await new Promise(r => setTimeout(r, 500));
    }
})();
