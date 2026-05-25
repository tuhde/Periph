# node-red-contrib-periph-led

Node-RED nodes for addressable LED chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-led`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-led
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `sk6812rgbw-device` | config | SPI bus and strand length for SK6812RGBW |
| `periph-sk6812rgbw` | output | Write RGBW pixel data to an SK6812RGBW strand |
| `ws2812b-device` | config | SPI bus and strand length for WS2812B |
| `periph-ws2812b` | output | Write RGB pixel data to a WS2812B strand |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
