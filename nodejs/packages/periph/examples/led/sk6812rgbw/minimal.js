'use strict';

const { NeoPixelTransport } = require('../../../src/transport/neopixel');
const { SK6812RGBWMinimal } = require('../../../src/chips/led/sk6812rgbw');

const SPI_BUS    = parseInt(process.env.SPI_BUS    || '0', 10);
const SPI_DEVICE = parseInt(process.env.SPI_DEVICE || '0', 10);

const transport = new NeoPixelTransport(SPI_BUS, SPI_DEVICE);  // Create NeoPixel transport, (busNumber, deviceNumber)
const strip = new SK6812RGBWMinimal(transport, 30);             // Create SK6812RGBW driver, (transport, n=30 pixels)

strip.fill(255, 0, 0);                                         // Fill all pixels red, (r=0–255, g=0–255, b=0–255, w=0–255) → void
setTimeout(() => {
    strip.fill(0, 255, 0);                                     // Fill all pixels green, (r=0–255, g=0–255, b=0–255, w=0–255) → void
    setTimeout(() => {
        strip.fill(0, 0, 255);                                 // Fill all pixels blue, (r=0–255, g=0–255, b=0–255, w=0–255) → void
        setTimeout(() => {
            strip.fill(0, 0, 0, 255);                          // Fill all pixels white (W channel), (r=0–255, g=0–255, b=0–255, w=0–255) → void
            setTimeout(() => {
                strip.off();                                   // Turn off all pixels, () → void
                transport.close();
            }, 1000);
        }, 1000);
    }, 1000);
}, 1000);
