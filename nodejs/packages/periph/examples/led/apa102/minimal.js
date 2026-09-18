'use strict';

const { SPIConnection } = require('../../../src/connection/spi');
const { APA102Minimal } = require('../../../src/chips/led/apa102');

const SPI_BUS    = parseInt(process.env.SPI_BUS    || '0', 10);
const SPI_DEVICE = parseInt(process.env.SPI_DEVICE || '0', 10);

const connection = new SPIConnection(SPI_BUS, SPI_DEVICE, { mode: 0, maxSpeedHz: 1_000_000 });  // Create SPI connection, (busNumber, deviceNumber, mode=0, 1MHz)
const strip = new APA102Minimal(connection, 30);                                                // Create APA102 driver, (connection, n=30 pixels)

(async () => {
    await strip.fill(255, 0, 0);                                                                 // Fill all pixels red, (r=0–255, g=0–255, b=0–255) → void
    await new Promise(r => setTimeout(r, 1000));
    await strip.fill(0, 255, 0);                                                                 // Fill all pixels green, (r=0–255, g=0–255, b=0–255) → void
    await new Promise(r => setTimeout(r, 1000));
    await strip.fill(0, 0, 255);                                                                 // Fill all pixels blue, (r=0–255, g=0–255, b=0–255) → void
    await new Promise(r => setTimeout(r, 1000));
    await strip.off();                                                                           // Turn off all pixels, () → void
    await connection.close();
})();