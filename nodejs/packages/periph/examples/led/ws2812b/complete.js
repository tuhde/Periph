'use strict';

const { NeoPixelTransport } = require('../../../src/transport/neopixel');
const { WS2812BFull } = require('../../../src/chips/led/ws2812b');

const SPI_BUS    = parseInt(process.env.SPI_BUS    || '0', 10);
const SPI_DEVICE = parseInt(process.env.SPI_DEVICE || '0', 10);

const transport = new NeoPixelTransport(SPI_BUS, SPI_DEVICE);  // Create NeoPixel transport, (busNumber, deviceNumber)
const strip = new WS2812BFull(transport, 8);                   // Create WS2812B full driver, (transport, n=8 pixels)

// fill — set all pixels and send immediately
strip.fill(255, 0, 0);                                         // Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255) → void
                                                               // stores GRB in buffer and calls transport.write()

// set individual pixels then show
strip.set_pixel(0, 255, 0, 0);                                 // Set pixel 0 in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255) → void
                                                               // writes G,R,B bytes into internal buffer at position index*3
strip.set_pixel(1, 0, 255, 0);                                 // Set pixel 1 in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255) → void
                                                               // writes G,R,B bytes into internal buffer at position index*3
strip.set_pixel(2, 0, 0, 255);                                 // Set pixel 2 in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255) → void
                                                               // writes G,R,B bytes into internal buffer at position index*3
strip.show();                                                  // Transmit buffer to strip, () → void
                                                               // applies brightness scaling then calls transport.write()

// set_pixels — write multiple pixels at once
strip.set_pixels([[255,0,0],[0,255,0],[0,0,255],               // Set pixels from array of [r,g,b], (colors=Array<[r,g,b]>) → void
                  [255,255,0],[0,255,255],[255,0,255],
                  [128,128,128],[255,255,255]]);
                                                               // writes entries sequentially from pixel 0; ignores extras beyond strip length
strip.show();                                                  // Transmit buffer to strip, () → void
                                                               // applies brightness scaling then calls transport.write()

// brightness — global scale applied at show() time
strip.brightness = 64;                                         // Set global brightness, (value=0–255)
                                                               // stored value is scaled: sent = stored * brightness / 255
strip.show();                                                  // Transmit buffer to strip, () → void
                                                               // applies brightness scaling then calls transport.write()
strip.brightness = 255;                                        // Set global brightness, (value=0–255)
                                                               // stored value is scaled: sent = stored * brightness / 255

// fill_hsv — fill all pixels from HSV colour
strip.fill_hsv(0.0, 1.0, 1.0);                                // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                               // converts HSV to RGB then calls fill(); hue 0.0 = red
strip.fill_hsv(0.333, 1.0, 1.0);                              // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                               // converts HSV to RGB then calls fill(); hue 0.333 = green
strip.fill_hsv(0.667, 1.0, 1.0);                              // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                               // converts HSV to RGB then calls fill(); hue 0.667 = blue

// rotate — shift pixel buffer left, then show
strip.set_pixels([[255, 0, 0], ...Array(7).fill([0, 0, 0])]); // Set pixels from array of [r,g,b], (colors=Array<[r,g,b]>) → void
                                                               // writes entries sequentially from pixel 0; ignores extras beyond strip length
strip.show();                                                  // Transmit buffer to strip, () → void
                                                               // applies brightness scaling then calls transport.write()
for (let i = 0; i < 7; i++) {
    strip.rotate(1);                                           // Rotate pixel buffer left, (steps=1) → void
                                                               // shifts buffer by steps pixel positions; wraps around; does not send
    strip.show();                                              // Transmit buffer to strip, () → void
                                                               // applies brightness scaling then calls transport.write()
}

strip.off();                                                   // Turn off all pixels, () → void
                                                               // equivalent to fill(0, 0, 0)
transport.close();
