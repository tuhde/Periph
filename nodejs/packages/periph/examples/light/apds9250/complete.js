'use strict';

// Complete example: exercise every method in the public API.
// Reads the channels singly and as a block, changes mode, resolution, rate
// and gain, programs the threshold and variation interrupts and handles one.

const { I2CConnection } = require('periph/src/connection/i2c');
const { APDS9250Full } = require('periph/src/chips/light/apds9250');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, APDS9250Full.I2C_ADDRESS);
    const sensor = new APDS9250Full(connection);                            // Create APDS-9250 Full driver, (connection)
    await sensor.init();                                                    // Check PART_ID, start colour mode, () → void

    console.log(await sensor.partId());                                     // Read PART_ID, () → {part, revision}
    // {part: 0xB, revision: 0x5} for the APDS-9250
    console.log(await sensor.read());                                       // Read all channels, () → {red, green, blue, ir}
    console.log(await sensor.readRed());                                    // Read the red channel, () → int count
    console.log(await sensor.readGreen());                                  // Read the green / ALS channel, () → int count
    console.log(await sensor.readBlue());                                   // Read the blue channel, () → int count
    console.log(await sensor.readIr());                                     // Read the IR channel, () → int count
    console.log(await sensor.dataReady());                                  // Query LS_DATA, () → bool
    // clears the status flags on the chip
    console.log(await sensor.readChannels());                               // Block-read without waiting, () → {red, green, blue, ir}

    await sensor.setMode(false);                                            // Select ALS/IR mode, (colour bool) → void
    // false: only green and IR are active
    await sensor.setMode(true);                                             // Back to colour mode, (colour bool) → void
    await sensor.disable();                                                 // Standby, () → void
    await sensor.enable();                                                  // Activate the sensor, () → void

    await sensor.setResolution(16);                                         // Set the ADC resolution, (bits) → void
    // 13, 16, 17, 18, 19 or 20; restarts the measurement
    await sensor.setMeasurementRate(200);                                   // Set the measurement rate, (milliseconds) → void
    // 25, 50, 100, 200, 500, 1000 or 2000
    await sensor.setGain(6);                                                // Set the gain, (gain) → void
    // 1, 3, 6, 9 or 18; restarts the measurement
    console.log(await sensor.getResolution());                              // Read the resolution, () → int bits
    console.log(await sensor.getMeasurementRate());                         // Read the rate, () → int ms
    console.log(await sensor.getGain());                                    // Read the gain, () → int
    console.log(await sensor.integrationTime());                            // Integration time, () → float s
    console.log(await sensor.normalized(await sensor.readGreen()));         // Counts per gain-second, (counts) → float

    await sensor.setThresholds(1000, 100000);                               // Set the threshold window, (low, high) → void
    // 20-bit counts
    console.log(await sensor.getThresholds());                              // Read the window, () → {low, high}
    await sensor.setInterruptChannel('green');                              // Choose the monitored channel, (channel) → void
    // 'ir', 'green', 'red' or 'blue'
    await sensor.setPersistence(2);                                         // Set the persistence, (count) → void
    // 1-16 consecutive events
    await sensor.setVariance(64);                                           // Set the variation threshold, (counts) → void
    // 8 ... 1024, power of two

    await sensor.enableInterrupt(APDS9250Full.SOURCE_VARIATION);            // Enable the interrupt, (source) → void
    // SOURCE_THRESHOLD or SOURCE_VARIATION
    await sensor.onInterrupt((status) => console.log('interrupt', status.toString(16)));   // Subscribe, (callback(status), {intPin}) → void
    // falling edge on INT, or a 50 ms poll without a pin
    await sleep(10000);
    console.log((await sensor.pollInterrupt()).toString(16));               // Read and clear MAIN_STATUS, () → int
    // 0x20 power-on, 0x10 interrupt, 0x08 data
    await sensor.offInterrupt();                                            // Unsubscribe, () → void
    await sensor.disableInterrupt();                                        // Disable the interrupt, () → void
    console.log((await sensor.status()).toString(16));                      // Raw MAIN_STATUS, () → int
    await sensor.softReset();                                               // Software reset, () → void
    // all registers to defaults, sensor in standby

    await connection.close();
})();
