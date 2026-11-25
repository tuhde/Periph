'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { AS7262Full } = require('../../../src/chips/color/as7262');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x49', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x49) → Connection
const sensor = new AS7262Full(connection);                                         // Create AS7262 driver, (connection) → void

async function main() {
    await sensor.init();                                                     // Reset and configure defaults, () → Promise<void>
    console.log(await sensor.read());                                        // Read calibrated channels, () → Promise<{violet, ..., red}>
                                                                             // one one-shot conversion
    console.log(await sensor.readRaw());                                     // Read raw counts, () → Promise<{violet, ..., red}>
    console.log(await sensor.temperature());                                 // Read device temperature, () → Promise<number> °C
    await sensor.measure();                                                  // Start a one-shot conversion, () → Promise<void>
                                                                             // does not wait
    while (!(await sensor.dataReady())) await sleep(50);                     // Check data ready, () → Promise<boolean>
    console.log(await sensor.readCalibrated());                              // Read last calibrated results, () → Promise<object>
    console.log(await sensor.readRawLast());                                 // Read last raw counts, () → Promise<object>
    console.log(await sensor.readChannel(5, true));                          // Read one channel, (index, calibrated=true) → Promise<number>
                                                                             // 0..5 = V, B, G, Y, O, R
    await sensor.setGain(16);                                                // Set gain, (gain) → Promise<void>
                                                                             // 0-3 index or 1, 3.7, 16, 64
    console.log(await sensor.getGain());                                     // Get gain, () → Promise<number>
    await sensor.setIntegrationTime(165.2);                                  // Set integration time, (ms) → Promise<void>
                                                                             // 2.8 - 714 ms
    console.log(await sensor.getIntegrationTime());                          // Get integration time, () → Promise<number> ms
    await sensor.setMode(3);                                                 // Set bank mode, (mode) → Promise<void>
                                                                             // 0, 1, 2 continuous; 3 one-shot
    console.log(await sensor.getMode());                                     // Get bank mode, () → Promise<number>
    await sensor.setContinuous(2);                                           // Continuous conversions, (mode=2) → Promise<void>
    await sensor.setContinuous(0);
    await sensor.setMode(3);
    await sensor.setInterrupt(false);                                        // Enable/disable INT pin, (enabled) → Promise<void>
    await sensor.setLed(true, 25);                                           // Driver LED, (on, currentMa=12.5) → Promise<void>
                                                                             // 12.5, 25, 50 or 100 mA
    await sensor.setLed(false);
    await sensor.setIndicatorLed(true, 4);                                   // Indicator LED, (on, currentMa=1) → Promise<void>
                                                                             // 1, 2, 4 or 8 mA
    await sensor.setIndicatorLed(false);
    console.log(await sensor.hardwareVersion());                             // Hardware version, () → Promise<[type, version]>
    console.log(await sensor.firmwareVersion());                             // Firmware version, () → Promise<[major, minor, sub]>
    console.log((await sensor.deviceType()).toString(16));                   // Device type, () → Promise<number> (0x40)
    console.log(await sensor.readVirtual(0x05));                             // Read a virtual register, (reg) → Promise<number>
    await sensor.writeVirtual(0x05, 0x3B);                                   // Write a virtual register, (reg, value) → Promise<void>
    await sensor.setContinuous(2);
    await sensor.onInterrupt(async () => console.log('data ready', await sensor.readCalibrated()));  // Subscribe to INT, (callback, intPin=null) → Promise<void>
    await sleep(3000);
    console.log(await sensor.pollInterrupt());                               // Poll DATA_RDY, () → Promise<number>
    await sensor.offInterrupt();                                             // Unsubscribe, () → Promise<void>
    await sensor.softReset();                                                // Soft reset, () → Promise<void>
                                                                             // restores defaults
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
