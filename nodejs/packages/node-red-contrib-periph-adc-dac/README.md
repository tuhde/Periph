# node-red-contrib-periph-adc-dac

Node-RED nodes for ADC and DAC chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-adc-dac`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-adc-dac
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `periph-ad5243` | input | Sets and reads the two 256-position wipers of an AD5243 dual I²C digital potentiometer. Wiper values are volatile: both channels power up at midscale (128). |
| `ad5248-device` | config | I²C bus and address for an AD5248 |
| `periph-ad5248` | input | Sets or reads the two 256-position wipers of an AD5248 dual digital rheostat over I²C. The device is configured via an ad5248-device config node. |
| `ad7705-device` | config | Bus configuration for an AD7705 |
| `periph-ad7705` | input | Reads voltage from a 2-channel, 16-bit sigma-delta AD7705 ADC over SPI. The driver uses the chip's two-phase register-access protocol and polls DRDY over SPI, so only SCLK/DIN/DOUT/CS are needed (no dedicated DRDY GPIO). |
| `ad7706-device` | config | Bus configuration for an AD7706 |
| `periph-ad7706` | input | Reads voltage from a 3-channel, 16-bit sigma-delta AD7706 ADC over SPI. The driver uses the chip's two-phase register-access protocol and polls DRDY over SPI, so only SCLK/DIN/DOUT/CS are needed (no dedicated DRDY GPIO). All three channels share a single COMMON reference pin. |
| `periph-ads1013` | input | Reads the voltage AIN0 − AIN1 from an ADS1013 12-bit ADC over I²C using a single-shot conversion. |
| `periph-ads1014` | input | Reads the voltage AIN0 − AIN1 from an ADS1014 12-bit ADC over I²C using a single-shot conversion. |
| `periph-ads1113` | input | Reads the ADS1113 16-bit ΔΣ ADC (one differential input (AIN0 − AIN1), fixed ±2.048 V) over I²C. Default address is 0x48 ; 0x48 – 0x4B are valid depending on the ADDR strap. |
| `periph-ads1114` | input | Reads the ADS1114 16-bit ΔΣ ADC (one differential input (AIN0 − AIN1) with programmable gain) over I²C. Default address is 0x48 ; 0x48 – 0x4B are valid depending on the ADDR strap. |
| `periph-ads1115` | input | Reads the ADS1115 16-bit ΔΣ ADC (four single-ended or two differential inputs with programmable gain) over I²C. Default address is 0x48 ; 0x48 – 0x4B are valid depending on the ADDR strap. |
| `periph-ads7828` | input | Reads the 12-bit, 8-channel ADS7828 ADC over I²C as eight single-ended inputs or four differential pairs. Default address is 0x48 ; 0x48 – 0x4B are valid depending on the A1/A0 straps. With the internal 2.5 V reference the REFIN/REFOUT pin must be decoupled with 0.1 µF and nothing else may drive it. |
| `periph-cat5171` | input | Sets and reads the 256-position wiper of a CAT5171 I²C digital potentiometer. The wiper is volatile: it powers up at midscale (128). |
| `es8311-device` | config | I²C bus and address for an ES8311 |
| `periph-es8311` | input | Controls an ES8311 low-power mono audio codec over its I²C control port. The audio stream itself (I²S) is not handled by this node. |
| `periph-hx710a` | input | Reads weight from an HX710A 24-bit ADC connected to a load cell via two GPIO pins. Also exposes the chip's on-chip temperature sensor as a raw ADC code. |
| `periph-hx710b` | input | Reads weight from an HX710B 24-bit ADC connected to a load cell via two GPIO pins. Also exposes the chip's DVDD-AVDD supply-voltage-difference channel as a raw ADC code for battery-monitoring applications. |
| `periph-hx711` | input | Reads weight from an HX711 24-bit ADC connected to a load cell via two GPIO pins. |
| `hy3131-device` | config | Bus configuration for a HY3131 |
| `periph-hy3131` | input | Reads the AD1 differential input (PB2 vs AGND) of an HY3131 DMM analog front end over SPI. Data-ready is polled via the INTF register, so only CK/DI/DO/CS are needed. |
| `mcp4725-device` | config | I²C bus and address for a MCP4725 |
| `periph-mcp4725` | output | Sets the output voltage of an MCP4725 12-bit DAC over I²C. |
| `mcp4728-device` | config | I²C bus and address for a MCP4728 |
| `periph-mcp4728` | output | Sets the output voltage of one channel (A–D) of an MCP4728 quad-channel 12-bit DAC over I²C. |
| `mcp4xxx-device` | config | I²C bus and address for a MCP4XXX |
| `periph-mcp4xxx` | input | Sets or reads the volatile wiper(s) of an MCP4XXX I²C digital potentiometer / rheostat (MCP4531 … MCP4662: 7 / 8-bit, one or two networks, volatile or non-volatile). The device is configured via an mcp4xxx-device config node. |
| `periph-pcf8591` | input | Reads the four analog inputs of a PCF8591 8-bit ADC/DAC over I²C. Default address is 0x48 ; any address in the range 0x48 – 0x4F is valid depending on the A0/A1/A2 pin states. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
