'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { ADS7828Full } = require('periph/src/chips/adc_dac/ads7828');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const DIVIDER = 2.0;   // inputs sit behind 2:1 dividers (rails up to 5 V)

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, ADS7828Full.I2C_ADDRESS);    // Create I2C connection, (bus, address)
    const adc = new ADS7828Full(connection);                                // Create ADS7828 driver, (connection, vref=null → internal 2.5 V)
    await adc.init();                                                       // Enable internal reference, () → None

    // --- Eight-channel supply monitor ---
    // Scan CH0–CH7 once per second with the internal 2.5 V reference, print raw
    // codes and rail voltages (after the divider ratio) and keep a running
    // minimum/maximum per channel.
    const vmin = new Array(8).fill(99);
    const vmax = new Array(8).fill(0);
    for (let n = 0; n < 5; n++) {
        const raws = await adc.readScan([0, 1, 2, 3, 4, 5, 6, 7]);          // Scan channels, (channels int[]) → int[]
        const volts = raws.map((r) => r * adc.lsb() * DIVIDER);
        volts.forEach((v, ch) => { vmin[ch] = Math.min(vmin[ch], v); vmax[ch] = Math.max(vmax[ch], v); });
        console.log('scan', n, raws.map((r, i) => `${r}=${volts[i].toFixed(3)}V`).join(' '));
        await sleep(1000);
    }
    console.log('min', vmin.map((v) => v.toFixed(3)).join(' '));
    console.log('max', vmax.map((v) => v.toFixed(3)).join(' '));

    // --- Read a bridge sensor on CH0/CH1 ---
    // The signed differential read does two conversions (normal and inverted
    // polarity) so the sign of the bridge output is preserved.
    const mv = (await adc.readSignedDifferential(0)) * 1000;                // Read pair 0 signed, (pair 0–3) → float V
    console.log(`bridge ${mv.toFixed(2)} mV`);

    // --- Second scan against an external 5.0 V reference ---
    // Switching to a 5 V external reference gives full-scale range up to VDD.
    await adc.setReference(5.0);                                            // Use external reference, (vref null|float V) → None
    console.log('ext 5 V scan', await adc.readScan([0, 1, 2, 3, 4, 5, 6, 7]));   // Scan channels, (channels int[]) → int[]

    // --- Finish in full power-down ---
    await adc.powerDown();                                                  // Power down completely, () → None
    await connection.close();
})();
