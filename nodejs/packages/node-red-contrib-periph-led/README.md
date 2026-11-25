# node-red-contrib-periph-led

Node-RED nodes for LED driver chips: APA102, HT16K33, IS31FL3731, PCA9685, SAA1064, SK6812RGBW, WS2812B, WS2814 — part of the [Periph](https://github.com/tuhde/Periph) library.

**Supported chips (8):** APA102, HT16K33, IS31FL3731, PCA9685, SAA1064, SK6812RGBW, WS2812B, WS2814.

Each chip has a sensor/actuator node plus a shared `-device` config node that holds the bus settings. Importable example flows are in [`examples/`](https://github.com/tuhde/Periph/tree/main/nodejs/packages/node-red-contrib-periph-led/examples).

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-led`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-led
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `apa102-device` | config | SPI bus and device index for an APA102 |
| `apa102` | output | Controls an APA102 addressable RGB LED strip over SPI (synchronous two-wire protocol). |
| `ht16k33-device` | config | I²C bus and address for a HT16K33 |
| `ht16k33` | input | Controls a HT16K33 16 × 8 LED matrix driver over I²C: set pixels, write the display RAM, change brightness and blink rate, and read the 13 × 3 key matrix. |
| `is31fl3731-device` | config | I²C bus and address for an IS31FL3731 |
| `is31fl3731` | input | Controls an IS31FL3731 144-LED matrix driver (9 × 16, 8-bit PWM per LED) over I²C. The node draws into frame 1 in Picture mode. |
| `pca9685-device` | config | I²C bus and address for a PCA9685 |
| `pca9685` | input | Controls the 16 PWM channels of a PCA9685 (12-bit, 24–1526 Hz) over I²C. |
| `saa1064-device` | config | I²C bus and address for a SAA1064 |
| `saa1064` | input | Drives a 4-digit 7-segment display through an SAA1064 over I²C. |
| `sk6812rgbw-device` | config | SPI bus and device index for a SK6812RGBW |
| `sk6812rgbw` | output | Controls an SK6812RGBW addressable RGBW LED strip over SPI (NeoPixel protocol). |
| `ws2812b-device` | config | SPI bus and device index for a WS2812B |
| `ws2812b` | output | Controls a WS2812B addressable RGB LED strip over SPI (NeoPixel protocol). |
| `ws2814-device` | config | SPI bus and device index for a WS2814 |
| `ws2814` | output | Controls a WS2814 addressable RGBW LED strip over SPI (NeoPixel protocol). |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
