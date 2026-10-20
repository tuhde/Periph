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
| `periph-ssd1331` | input | Draws on an SSD1331 96 x 64 RGB OLED (e.g. Waveshare 0.95inch RGB OLED) over SPI. The chip has no BUSY line. Drawing messages change the framebuffer only; show sends it to the display. The hw* messages use the controller's own drawing engine on the display RAM. |
| `periph-st7036` | output | Writes text on an ST7036 character LCD (EA DOGM081 / DOGM162 / DOGM163) in 4-wire serial mode over SPI. The serial mode is write-only; the chip has no BUSY line. Strapping: PSB = GND, E = 3V3, booster capacitors for 3.3 V. |
| `periph-uc1611s` | input | Draws on a UC1611s graphic LCD (EA DOGXL240-7, 240 x 128, black/white) over I2C or 4-wire SPI. On I2C the chip has two addresses, command ( 0x38 + 2n ) and data (one higher); the node opens both. On SPI the node needs the SPI bus / chip-select device and the GPIO lines for CD (D/C) and RST; the module must be strapped for 4-wire SPI (BM0 = D13 = VSS), and payload.status is not available. The module runs from 3.3 V only. |
| `periph-uc8159` | input | Draws on a UC8159 seven-colour e-paper panel (600 x 448, e.g. Waveshare 5.65inch e-Paper (F)) over SPI. Drawing messages change the framebuffer only; show sends it and refreshes the panel, which blocks the node for about 30 seconds. |
| `periph-uc8276c` | input | Draws on a UC8276C black/white/red e-paper panel (400 x 300, e.g. Waveshare 4.2inch e-Paper (B)) over SPI. Drawing messages change the framebuffer only; show sends it and refreshes the panel, which blocks the node for about 17 seconds. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
