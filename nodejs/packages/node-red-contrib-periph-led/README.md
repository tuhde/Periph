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
| `sk6812rgbw-device` | config | SPI bus and device index for a SK6812RGBW |
| `periph-sk6812rgbw` | output | Controls an SK6812RGBW addressable RGBW LED strip over SPI (NeoPixel protocol). |
| `ws2812b-device` | config | SPI bus and device index for a WS2812B |
| `periph-ws2812b` | output | Controls a WS2812B addressable RGB LED strip over SPI (NeoPixel protocol). |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
