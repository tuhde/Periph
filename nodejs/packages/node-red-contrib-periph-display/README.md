# node-red-contrib-periph-display

Node-RED nodes for display driver chips: IL0373, PCF8576, PCF8578, PCF8579, SH1106, SSD1305, SSD1306, SSD1331, ST7036, UC1611S, UC8159, UC8276C — part of the [Periph](https://github.com/tuhde/Periph) library.

**Supported chips (12):** IL0373, PCF8576, PCF8578, PCF8579, SH1106, SSD1305, SSD1306, SSD1331, ST7036, UC1611S, UC8159, UC8276C.

Each chip has a sensor/actuator node plus a shared `-device` config node that holds the bus settings. Importable example flows are in [`examples/`](https://github.com/tuhde/Periph/tree/main/nodejs/packages/node-red-contrib-periph-display/examples).

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-display`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-display
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `il0373-device` | config | SPI bus and device index for an IL0373 |
| `il0373` | input | Draws on an IL0373 black/white e-paper panel (104 x 212, e.g. Waveshare 2.13inch e-Paper (D)) over SPI. Drawing messages change the framebuffer only; show sends it and refreshes the panel, which blocks the node until the controller is ready again. |
| `pcf8576-device` | config | I²C bus and address for a PCF8576 |
| `pcf8576` | input | Writes 7-segment digits or raw data to a PCF8576 40x4 universal LCD segment driver over I²C. The chip is write-only; any incoming message triggers a single write to display RAM. |
| `pcf8578-device` | config | I²C bus and address for a PCF8578 |
| `pcf8578` | input | Draws on a PCF8578 row/column LCD dot-matrix driver over I²C (stand-alone mixed mode). Any incoming message triggers the writes described below. The device is configured via a pcf8578-device config node. |
| `pcf8579-device` | config | I²C bus and address for a PCF8579 |
| `pcf8579` | input | Draws into the display RAM of a PCF8579 LCD column driver over I²C. The PCF8579 cannot run alone: a PCF8578 (or an equivalent source) must supply the clock, frame sync and row waveforms. Any incoming message triggers the writes described below. The device is configured via a pcf8579-device config node. |
| `sh1106-device` | config | I²C bus and address for a SH1106 |
| `sh1106` | input | Draws on an SH1106 monochrome OLED (132 x 64 RAM, typically a 1.3" 128 x 64 module) over I2C ( 0x3C / 0x3D ) or 4-wire SPI. On SPI the node needs the SPI bus / chip-select device and the GPIO lines for D/C and RES; payload.status is not available. 128-pixel-wide modules show RAM columns 2..129, hence the default column offset of 2 (use 0 for a true 132-column panel). |
| `ssd1305-device` | config | I²C bus and address for a SSD1305 |
| `ssd1305` | input | Draws on an SSD1305 monochrome OLED (132 x 64 RAM, typically a 1.3" 128 x 64 module) over I2C ( 0x3C / 0x3D ) or 4-wire SPI. On SPI the node needs the SPI bus / chip-select device and the GPIO lines for D/C and RES; payload.status is not available. 128-pixel-wide modules usually show RAM columns 4..131, hence the default column offset of 4 (use 0 for a true 132-column panel, 2 for some panels). |
| `ssd1306-device` | config | I²C bus and address for a SSD1306 |
| `ssd1306` | output | Draws on an SSD1306 monochrome OLED (128 x 64 RAM, typically a 0.96" 128 x 64 or 128 x 32 module) over I2C ( 0x3C / 0x3D ) or 4-wire SPI. On SPI the node needs the SPI bus / chip-select device and the GPIO lines for D/C and RES. The chip cannot be read, so there is no status output. External VCC keeps the internal charge pump off for boards that supply the OLED voltage themselves. |
| `ssd1331-device` | config | SPI bus and device index for a SSD1331 |
| `ssd1331` | input | Draws on an SSD1331 96 x 64 RGB OLED (e.g. Waveshare 0.95inch RGB OLED) over SPI. The chip has no BUSY line. Drawing messages change the framebuffer only; show sends it to the display. The hw* messages use the controller's own drawing engine on the display RAM. |
| `st7036-device` | config | SPI bus and device index for a ST7036 |
| `st7036` | output | Writes text on an ST7036 character LCD (EA DOGM081 / DOGM162 / DOGM163) in 4-wire serial mode over SPI. The serial mode is write-only; the chip has no BUSY line. Strapping: PSB = GND, E = 3V3, booster capacitors for 3.3 V. |
| `uc1611s-device` | config | I²C bus and address for an UC1611S |
| `uc1611s` | input | Draws on a UC1611s graphic LCD (EA DOGXL240-7, 240 x 128, black/white) over I2C or 4-wire SPI. On I2C the chip has two addresses, command ( 0x38 + 2n ) and data (one higher); the node opens both. On SPI the node needs the SPI bus / chip-select device and the GPIO lines for CD (D/C) and RST; the module must be strapped for 4-wire SPI (BM0 = D13 = VSS), and payload.status is not available. The module runs from 3.3 V only. |
| `uc8159-device` | config | SPI bus and device index for an UC8159 |
| `uc8159` | input | Draws on a UC8159 seven-colour e-paper panel (600 x 448, e.g. Waveshare 5.65inch e-Paper (F)) over SPI. Drawing messages change the framebuffer only; show sends it and refreshes the panel, which blocks the node for about 30 seconds. |
| `uc8276c-device` | config | SPI bus and device index for an UC8276C |
| `uc8276c` | input | Draws on a UC8276C black/white/red e-paper panel (400 x 300, e.g. Waveshare 4.2inch e-Paper (B)) over SPI. Drawing messages change the framebuffer only; show sends it and refreshes the panel, which blocks the node for about 17 seconds. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
