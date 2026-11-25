'use strict';

const spi = require('spi-device');
const { SPIConnection } = require('../../../src/connection/spi');
const { HY3131Full } = require('../../../src/chips/adc_dac/hy3131');

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);

(async () => {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: spi.MODE0, maxSpeedHz: 1_000_000 });   // Create SPI connection, (bus=0, device=0, mode=0, maxSpeedHz=1_000_000) → SPIConnection

    // --- Bench voltmeter with on-chip temperature and AC analysis ---
    // A 0–1.2 V source sits between PB2 and AGND. AD1 runs at ~40 Hz with the chopper on
    // for offset-free DC readings; AD2 + LPF + RMS + peak hold watch the same node for AC.
    const adc = await HY3131Full.create(connection);                                                       // Create and initialise HY3131 driver, (connection, vref=1.2 V) → HY3131Full
    await adc.configureAd1({ osr: 4, chop: 2 });                                                           // Configure AD1, ({osr=7, chop=0, inputGain=0, refGain=0, offset=0, buffers=0}) → None

    // --- One-point temperature calibration ---
    // Run the board at 25 °C (or replace with a stored constant) and remember the code.
    const tcode25 = await adc.readTemperatureCode();                                                       // Read temperature code, () → int

    // --- Enable AC measurement chain on the same input ---
    await adc.enableAd2({ osr: 5, inputGain: 1, source: 2 });                                              // Enable AD2, ({osr=5, inputGain=1, refGain=0, chop=false, clkDiv4=false, source=0, useAd3=false}) → None
    await adc.enableLpf(3);                                                                                // Enable low-pass filter, (bw=0) → None
    await adc.enableRms();                                                                                 // Enable RMS converter, () → None
    await adc.enablePeakHold(0);                                                                           // Enable peak hold, (source=0) → None

    for (;;) {
        const v = await adc.readVoltage();                                                                 // Read AD1 voltage, () → float V
        const t = await adc.readTemperature(tcode25);                                                      // Read die temperature, (tcode25) → float °C
        const rms = await adc.readRms();                                                                   // Read RMS, () → float LSB
        const pk = await adc.readPeak();                                                                   // Read peak hold, () → {min, max} 19-bit
        console.log(`V=${v.toFixed(4)} V  T=${t.toFixed(1)} C  rms=${rms.toFixed(0)}  p-p=${pk.max - pk.min}`);
        await new Promise((r) => setTimeout(r, 1000));
    }
})();
