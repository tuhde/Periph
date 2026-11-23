# node-red-contrib-periph-adc-dac

Node-RED nodes for ADC and DAC chips: AD5243, AD5248, AD5251, AD5252, AD7705, AD7706, ADS1013, ADS1014, ADS1113, ADS1114, ADS1115, ADS7828, CAT5171, CS43L22, DS1881, DS3502, ES8311, HX710A, HX710B, HX711, HY3131, MCP3422, MCP4725, MCP4728, MCP4XXX, PCF8591 — part of the [Periph](https://github.com/tuhde/Periph) library.

**Supported chips (26):** AD5243, AD5248, AD5251, AD5252, AD7705, AD7706, ADS1013, ADS1014, ADS1113, ADS1114, ADS1115, ADS7828, CAT5171, CS43L22, DS1881, DS3502, ES8311, HX710A, HX710B, HX711, HY3131, MCP3422, MCP4725, MCP4728, MCP4XXX, PCF8591.

Each chip has a sensor/actuator node plus a shared `-device` config node that holds the bus settings. Importable example flows are in [`examples/`](https://github.com/tuhde/Periph/tree/main/nodejs/packages/node-red-contrib-periph-adc-dac/examples).

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-adc-dac`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-adc-dac
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `ad5243-device` | config | Bus configuration for an AD5243 |
| `ad5243` | input | Sets and reads the two 256-position wipers of an AD5243 dual I²C digital potentiometer. Wiper values are volatile: both channels power up at midscale (128). |
| `ad5248-device` | config | I²C bus and address for an AD5248 |
| `ad5248` | input | Sets or reads the two 256-position wipers of an AD5248 dual digital rheostat over I²C. The device is configured via an ad5248-device config node. |
| `ad5251-device` | config | I²C bus and address for an AD5251 |
| `ad5251` | input | Sets and reads the two 64-position wipers of an AD5251 dual I²C digital potentiometer. Both channels power up from their stored EEMEM value (midscale, 32, until a value is stored). |
| `ad5252-device` | config | I²C bus and address for an AD5252 |
| `ad5252` | input | Sets and reads the two 256-position wipers of an AD5252 dual I²C digital potentiometer. Both channels power up from their stored EEMEM value (midscale, 128, until a value is stored). |
| `ad7705-device` | config | Bus configuration for an AD7705 |
| `ad7705` | input | Reads voltage from a 2-channel, 16-bit sigma-delta AD7705 ADC over SPI. The driver uses the chip's two-phase register-access protocol and polls DRDY over SPI, so only SCLK/DIN/DOUT/CS are needed (no dedicated DRDY GPIO). |
| `ad7706-device` | config | Bus configuration for an AD7706 |
| `ad7706` | input | Reads voltage from a 3-channel, 16-bit sigma-delta AD7706 ADC over SPI. The driver uses the chip's two-phase register-access protocol and polls DRDY over SPI, so only SCLK/DIN/DOUT/CS are needed (no dedicated DRDY GPIO). All three channels share a single COMMON reference pin. |
| `ads1013-device` | config | I²C bus and address for an ADS1013 |
| `ads1013` | input | Reads the voltage AIN0 − AIN1 from an ADS1013 12-bit ADC over I²C using a single-shot conversion. |
| `ads1014-device` | config | I²C bus and address for an ADS1014 |
| `ads1014` | input | Reads the voltage AIN0 − AIN1 from an ADS1014 12-bit ADC over I²C using a single-shot conversion. |
| `ads1113-device` | config | I²C bus and address for an ADS1113 |
| `ads1113` | input | Reads the ADS1113 16-bit ΔΣ ADC (one differential input (AIN0 − AIN1), fixed ±2.048 V) over I²C. Default address is 0x48 ; 0x48 – 0x4B are valid depending on the ADDR strap. |
| `ads1114-device` | config | I²C bus and address for an ADS1114 |
| `ads1114` | input | Reads the ADS1114 16-bit ΔΣ ADC (one differential input (AIN0 − AIN1) with programmable gain) over I²C. Default address is 0x48 ; 0x48 – 0x4B are valid depending on the ADDR strap. |
| `ads1115-device` | config | I²C bus and address for an ADS1115 |
| `ads1115` | input | Reads the ADS1115 16-bit ΔΣ ADC (four single-ended or two differential inputs with programmable gain) over I²C. Default address is 0x48 ; 0x48 – 0x4B are valid depending on the ADDR strap. |
| `ads7828-device` | config | I²C bus and address for an ADS7828 |
| `ads7828` | input | Reads the 12-bit, 8-channel ADS7828 ADC over I²C as eight single-ended inputs or four differential pairs. Default address is 0x48 ; 0x48 – 0x4B are valid depending on the A1/A0 straps. With the internal 2.5 V reference the REFIN/REFOUT pin must be decoupled with 0.1 µF and nothing else may drive it. |
| `cat5171-device` | config | I²C bus and address for a CAT5171 |
| `cat5171` | input | Sets and reads the 256-position wiper of a CAT5171 I²C digital potentiometer. The wiper is volatile: it powers up at midscale (128). |
| `cs43l22-device` | config | I²C bus and address for a CS43L22 |
| `cs43l22` | input | Controls a CS43L22 stereo DAC with headphone and speaker amplifiers over its I²C control port. The audio stream (I²S: MCLK, SCLK, LRCK, SDIN) comes from the board and is not handled by this node. Send { start: true } only after MCLK is running. |
| `ds1881-device` | config | I²C bus and address for a DS1881 |
| `ds1881` | input | Controls the two audio log-taper channels of a DS1881 dual digital potentiometer. Attenuation is in dB (0 dB = wiper at H); position 63 (Option 1) or 33 (Option 2) is mute. By default the wipers are volatile and power up at mute. |
| `ds3502-device` | config | I²C bus and address for a DS3502 |
| `ds3502` | input | Sets and reads the 128-position wiper of a DS3502 high-voltage I²C digital potentiometer. Wiper writes go to SRAM only (MODE = 1) and never wear the EEPROM; the wiper powers up at the stored Initial Value Register position (factory default 64, midscale). |
| `es8311-device` | config | I²C bus and address for an ES8311 |
| `es8311` | input | Controls an ES8311 low-power mono audio codec over its I²C control port. The audio stream itself (I²S) is not handled by this node. |
| `hx710a-device` | config | Bus configuration for a HX710A |
| `hx710a` | input | Reads weight from an HX710A 24-bit ADC connected to a load cell via two GPIO pins. Also exposes the chip's on-chip temperature sensor as a raw ADC code. |
| `hx710b-device` | config | Bus configuration for a HX710B |
| `hx710b` | input | Reads weight from an HX710B 24-bit ADC connected to a load cell via two GPIO pins. Also exposes the chip's DVDD-AVDD supply-voltage-difference channel as a raw ADC code for battery-monitoring applications. |
| `hx711-device` | config | Bus configuration for a HX711 |
| `hx711` | input | Reads weight from an HX711 24-bit ADC connected to a load cell via two GPIO pins. |
| `hy3131-device` | config | Bus configuration for a HY3131 |
| `hy3131` | input | Reads the AD1 differential input (PB2 vs AGND) of an HY3131 DMM analog front end over SPI. Data-ready is polled via the INTF register, so only CK/DI/DO/CS are needed. |
| `mcp3422-device` | config | I²C bus and address for a MCP3422 |
| `mcp3422` | input | Reads the two differential inputs of a MCP3422 18-bit delta-sigma ADC (I²C) with a one-shot conversion per channel. Full scale is ±2.048 V / gain; the LSB is 2·2.048 V / 2 bits / gain. |
| `mcp4725-device` | config | I²C bus and address for a MCP4725 |
| `mcp4725` | output | Sets the output voltage of an MCP4725 12-bit DAC over I²C. |
| `mcp4728-device` | config | I²C bus and address for a MCP4728 |
| `mcp4728` | output | Sets the output voltage of one channel (A–D) of an MCP4728 quad-channel 12-bit DAC over I²C. |
| `mcp4xxx-device` | config | I²C bus and address for a MCP4XXX |
| `mcp4xxx` | input | Sets or reads the volatile wiper(s) of an MCP4XXX I²C digital potentiometer / rheostat (MCP4531 … MCP4662: 7 / 8-bit, one or two networks, volatile or non-volatile). The device is configured via an mcp4xxx-device config node. |
| `pcf8591-device` | config | I²C bus and address for a PCF8591 |
| `pcf8591` | input | Reads the four analog inputs of a PCF8591 8-bit ADC/DAC over I²C. Default address is 0x48 ; any address in the range 0x48 – 0x4F is valid depending on the A0/A1/A2 pin states. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
