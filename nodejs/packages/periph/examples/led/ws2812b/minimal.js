'use strict';

const { NeoPixelConnection } = require('../../../src/connection/neopixel');
const { WS2812BMinimal } = require('../../../src/chips/led/ws2812b');

const SPI_BUS    = parseInt(process.env.SPI_BUS    || '0', 10);
const SPI_DEVICE = parseInt(process.env.SPI_DEVICE || '0', 10);

const connection = new NeoPixelConnection(SPI_BUS, SPI_DEVICE);  // Create NeoPixel connection, (busNumber, deviceNumber)
const strip = new WS2812BMinimal(connection, 30);                // Create WS2812B driver, (connection, n=30 pixels)

(async () => {
    await strip.fill(255, 0, 0);                                // Fill all pixels red, (r=0–255, g=0–255, b=0–255) → void
    await new Promise(r => setTimeout(r, 1000));
    await strip.fill(0, 255, 0);                                // Fill all pixels green, (r=0–255, g=0–255, b=0–255) → void
    await new Promise(r => setTimeout(r, 1000));
    await strip.fill(0, 0, 255);                                // Fill all pixels blue, (r=0–255, g=0–255, b=0–255) → void
    await new Promise(r => setTimeout(r, 1000));
    await strip.off();                                          // Turn off all pixels, () → void
    await connection.close();
})();
