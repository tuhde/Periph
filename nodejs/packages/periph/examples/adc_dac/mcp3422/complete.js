'use strict';

// Complete example: exercise every method in the public API.

const { I2CConnection } = require('periph/src/connection/i2c');
const { MCP3422Full } = require('periph/src/chips/adc_dac/mcp3422');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, MCP3422Full.I2C_ADDRESS);
    const adc = new MCP3422Full(connection);                                // Create MCP3422 Full driver, (connection)
    await adc.init();                                                       // Write the default configuration, () → void (one-shot, 16 bit, x1, RDY = 0)

    console.log(await adc.readRaw(0));                                      // One-shot code, (channel 0–1) → int
    // writes RDY = 1, O/C = 0, waits, polls RDY
    console.log(await adc.readVoltage(1));                                  // One-shot voltage, (channel 0–1) → float V
    console.log(await adc.readAll());                                       // Both channels, () → [float V, float V]
    console.log(await adc.readScan([0, 1, 0]));                             // Scan a list of channels, (channels) → [float V, ...]

    await adc.setResolution(18);                                            // Select 18 bit / 3.75 SPS, (bits 12|14|16|18) → void
    // throws RangeError for other values
    await adc.setGain(8);                                                   // Select PGA x8, (gain 1|2|4|8) → void
    console.log(adc.getResolution(), adc.getGain());                        // Read back the selection, () → int, int
    console.log(adc.lsb(), adc.fullScale());                                // LSB and full scale, () → float V, float V
    // 2·2.048/2^bits and 2.048/gain

    await adc.setResolution(12);                                            // Back to 12 bit / 240 SPS, (bits) → void
    await adc.setGain(1);                                                   // Back to x1, (gain) → void
    await adc.setChannel(0);                                                // Channel for continuous conversion, (channel 0–1) → void
    await adc.startContinuous();                                            // Start continuous conversion, (channel) → void
    // O/C = 1; the first sample after a change is discarded
    console.log(adc.isContinuous());                                        // Report the mode, () → bool
    console.log(await adc.readContinuousRaw());                             // Next fresh code, () → int
    console.log(await adc.readContinuous());                                // Next fresh voltage, () → float V
    console.log(await adc.readConfig(), await adc.isReady());               // Config byte and RDY, () → int, bool
    await adc.stopContinuous();                                             // Back to one-shot / standby, () → void

    await adc.generalCallConvert();                                         // Start a conversion on every device, () → void
    // sends 0x00 0x08
    await sleep(100);
    console.log(await adc.readPendingRaw());                                // Result of that conversion, () → int
    await adc.generalCallReset();                                           // Reset to power-on defaults, () → void
    // sends 0x00 0x06; the driver state follows

    await connection.close();
})();
