'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { AS7262Full } = require('../../../src/chips/color/as7262');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x49', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x49) → Connection
const sensor = new AS7262Full(connection);                                         // Create AS7262 driver, (connection) → void

async function main() {
    // --- Spectrometer ---
    // Versions, gain sweep, integration time comparison, LED reflectance difference, continuous mode.
    await sensor.init();                                                     // Reset and configure defaults, () → Promise<void>
    console.log((await sensor.deviceType()).toString(16), await sensor.hardwareVersion(), await sensor.firmwareVersion());  // Identity
    console.log('chip ' + (await sensor.temperature()) + ' C');              // Read device temperature, () → Promise<number> °C

    for (const gain of [1, 3.7, 16, 64]) {
        await sensor.setGain(gain);                                          // Set gain, (gain) → Promise<void>
        console.log('gain', await sensor.getGain());                         // Get gain, () → Promise<number>
        console.log(' cal', await sensor.read());                            // Read calibrated, () → Promise<object>
        console.log(' raw', await sensor.readRaw());                         // Read raw, () → Promise<object>
    }
    await sensor.setGain(16);

    for (const ms of [50, 500]) {
        await sensor.setIntegrationTime(ms);                                 // Set integration time, (ms) → Promise<void>
        const t0 = Date.now();
        await sensor.read();
        console.log('integration', await sensor.getIntegrationTime(), 'ms ->', (Date.now() - t0) / 1000, 's');
    }
    await sensor.setIntegrationTime(165.2);

    await sensor.setMode(3);                                                 // Set bank mode, (mode) → Promise<void>
    const base = await sensor.read();
    await sensor.setIndicatorLed(true, 4);                                   // Indicator LED, (on, currentMa) → Promise<void>
    await sensor.setLed(true, 25);                                           // Driver LED, (on, currentMa) → Promise<void>
    const lit = await sensor.read();
    await sensor.setLed(false);
    await sensor.setIndicatorLed(false);
    const diff = {};
    for (const k of Object.keys(lit)) diff[k] = lit[k] - base[k];
    const dominant = Object.keys(diff).reduce((a, b) => (diff[a] >= diff[b] ? a : b));
    console.log('reflectance', diff, 'dominant', dominant);

    await sensor.measure();                                                  // Start one-shot, () → Promise<void>
    while (!(await sensor.dataReady())) await sleep(50);                     // Data ready, () → Promise<boolean>
    console.log(await sensor.readCalibrated(), await sensor.readRawLast(), await sensor.readChannel(0));  // Last results / one channel

    await sensor.setContinuous(2);                                           // Continuous mode, (mode=2) → Promise<void>
    await sensor.onInterrupt(async () => console.log(await sensor.readCalibrated()));  // Subscribe to INT, (callback, intPin=null) → Promise<void>
    await sleep(5000);
    console.log(await sensor.pollInterrupt());                               // Poll DATA_RDY, () → Promise<number>
    await sensor.offInterrupt();                                             // Unsubscribe, () → Promise<void>
    await sensor.setMode(3);
    await sensor.setInterrupt(false);                                        // Disable INT, (enabled) → Promise<void>
    await sensor.writeVirtual(0x05, 0x3B);                                   // Write virtual register, (reg, value) → Promise<void>
    console.log(await sensor.readVirtual(0x05));                             // Read virtual register, (reg) → Promise<number>
    await sensor.softReset();                                                // Soft reset, () → Promise<void>
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
