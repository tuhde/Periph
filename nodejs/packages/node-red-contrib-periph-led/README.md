# node-red-contrib-periph-led

Node-RED nodes for LED driver chips — part of the [Periph](https://github.com/tuhde/Periph) library.

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
| `periph-apa102` | output | Controls an APA102 addressable RGB LED strip over SPI (synchronous two-wire protocol). |
| `ht16k33-device` | config | I²C bus and address for a HT16K33 |
| `periph-ht16k33` | input | Controls a HT16K33 16 × 8 LED matrix driver over I²C: set pixels, write the display RAM, change brightness and blink rate, and read the 13 × 3 key matrix. |
| `is31fl3731-device` | config | I²C bus and address for an IS31FL3731 |
| `periph-is31fl3731` | input | Controls an IS31FL3731 144-LED matrix driver (9 × 16, 8-bit PWM per LED) over I²C. The node draws into frame 1 in Picture mode. |
| `sk6812rgbw-device` | config | SPI bus and device index for a SK6812RGBW |
| `periph-sk6812rgbw` | output | Controls an SK6812RGBW addressable RGBW LED strip over SPI (NeoPixel protocol). |
| `ws2812b-device` | config | SPI bus and device index for a WS2812B |
| `periph-ws2812b` | output | Controls a WS2812B addressable RGB LED strip over SPI (NeoPixel protocol). |
| `ws2814-device` | config | SPI bus and device index for a WS2814 |
| `periph-ws2814` | output | Controls a WS2814 addressable RGBW LED strip over SPI (NeoPixel protocol). |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
