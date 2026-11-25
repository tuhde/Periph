'use strict';

// Complete example: every API method of the Full driver.

const { I2CConnection } = require('periph/src/connection/i2c');
const { ISL29125Full } = require('periph/src/chips/color/isl29125');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, ISL29125Full.I2C_ADDRESS);
    const sensor = new ISL29125Full(connection);                            // Create ISL29125 Full driver, (connection)
    await sensor.init();                                                    // Check DEVICE_ID, start RGB conversion, () → void

    console.log('device id', (await sensor.deviceId()).toString(16));       // Read DEVICE_ID, () → number
                                                                            // 0x7D for the ISL29125
    console.log(await sensor.read());                                       // Read the three channels, () → {red, green, blue}
    console.log(await sensor.readRed());                                    // Read the red channel, () → number
    console.log(await sensor.readGreen());                                  // Read the green channel, () → number
    console.log(await sensor.readBlue());                                   // Read the blue channel, () → number
    console.log(await sensor.readRgb());                                    // Block-read without waiting, () → {red, green, blue}
    console.log(await sensor.dataReady());                                  // Query CONVENF, () → boolean
                                                                            // clears the status flags on the chip
    await sensor.setMode('green');                                          // Select the channels, (mode) → void
                                                                            // power_down, standby, green, red, blue, rgb, green_red, green_blue
    await sensor.setMode('rgb');
    await sensor.setRange(375);                                             // Set the full-scale range, (lux) → void
                                                                            // 375 or 10000
    await sensor.setRange(10000);
    await sensor.setResolution(12);                                         // Set the ADC resolution, (bits) → void
                                                                            // 12 or 16
    await sensor.setResolution(16);
    await sensor.setSync(false);                                            // Select the conversion start, (external) → void
                                                                            // true: rising edge of INT starts the conversion
    await sensor.setIrCompensation(true, 0x3F);                             // Set the IR compensation, (offset, adjust) → void
                                                                            // adjust 0-63; offset selects codes 106-169
    await sensor.setThresholds(1000, 50000);                                // Set the threshold window, (low, high) → void
    console.log(await sensor.getThresholds());                              // Read the window, () → {low, high}
    await sensor.setInterruptChannel('green');                              // Choose the monitored channel, (channel) → void
                                                                            // none, green, red or blue
    await sensor.setPersistence(2);                                         // Set the persistence, (count) → void
                                                                            // 1, 2, 4 or 8 integration cycles
    await sensor.enableInterrupt(ISL29125Full.SOURCE_THRESHOLD);            // Enable the interrupt, (source) → void
                                                                            // SOURCE_THRESHOLD or SOURCE_CONVERSION
    await sensor.onInterrupt((s) => console.log('interrupt', s.toString(16)));   // Subscribe, (callback, options) → void
    await sleep(2000);
    await sensor.offInterrupt();                                            // Unsubscribe, () → void
    console.log((await sensor.pollInterrupt()).toString(16));               // Read and clear STATUS, () → number
    await sensor.disableInterrupt(ISL29125Full.SOURCE_CONVERSION);          // Disable the interrupt, (source) → void
    console.log((await sensor.status()).toString(16));                      // Raw STATUS, () → number
    await sensor.setMode('power_down');
    await sensor.softReset();                                               // Software reset, () → void
                                                                            // all registers to defaults

    await connection.close();
})();
