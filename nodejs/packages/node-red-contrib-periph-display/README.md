# node-red-contrib-periph-display

Node-RED nodes for display driver chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-display`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-display
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `periph-il0373` | input | Draws on an IL0373 black/white e-paper panel (104 x 212, e.g. Waveshare 2.13inch e-Paper (D)) over SPI. Drawing messages change the framebuffer only; show sends it and refreshes the panel, which blocks the node until the controller is ready again. |
| `pcf8576-device` | config | I²C bus and address for a PCF8576 |
| `periph-pcf8576` | input | Writes 7-segment digits or raw data to a PCF8576 40x4 universal LCD segment driver over I²C. The chip is write-only; any incoming message triggers a single write to display RAM. |
| `periph-uc8159` | input | Draws on a UC8159 seven-colour e-paper panel (600 x 448, e.g. Waveshare 5.65inch e-Paper (F)) over SPI. Drawing messages change the framebuffer only; show sends it and refreshes the panel, which blocks the node for about 30 seconds. |
| `periph-uc8276c` | input | Draws on a UC8276C black/white/red e-paper panel (400 x 300, e.g. Waveshare 4.2inch e-Paper (B)) over SPI. Drawing messages change the framebuffer only; show sends it and refreshes the panel, which blocks the node for about 17 seconds. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
