'use strict';

// Demo: a two-channel bridge / thermocouple monitor. Reads CH1 (a thermistor
// divider) at 16 bit / x1, then 18 bit / x8 on CH2 (a bridge sensor), then runs
// continuous conversion on CH1 at 12 bit / 240 SPS and finishes with a general
// call reset.

const { I2CConnection } = require('periph/src/connection/i2c');
const { MCP3422Full } = require('periph/src/chips/adc_dac/mcp3422');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, MCP3422Full.I2C_ADDRESS);
    const adc = new MCP3422Full(connection);                                // Create MCP3422 Full driver, (connection)
    await adc.init();                                                       // Write the default configuration, () → void (one-shot, 16 bit, x1, RDY = 0)

    // --- CH1: thermistor divider at 16 bit / x1 ---
    for (let i = 0; i < 3; i++) {
        const raw = await adc.readRaw(0);                                   // One-shot code, (channel 0–1) → int
        console.log('CH1 raw', raw, '=', (await adc.readVoltage(0)).toFixed(5), 'V');// One-shot voltage, (channel 0–1) → float V
        await sleep(1000);
    }

    // --- CH2: bridge sensor at 18 bit / x8 ---
    await adc.setResolution(18);                                            // Select 18 bit / 3.75 SPS, (bits) → void
    await adc.setGain(8);                                                   // Select PGA x8, (gain) → void
    const [bridge] = await adc.readScan([1]);                               // Scan channels, (channels) → [float V]
    console.log('CH2', (bridge * 1e6).toFixed(1), 'uV, LSB', (adc.lsb() / adc.getGain() * 1e6).toFixed(3), 'uV');// LSB, () → float V

    // --- Continuous conversion on CH1 at 12 bit / 240 SPS ---
    await adc.setResolution(12);
    await adc.setGain(1);
    await adc.startContinuous(0);                                           // Start continuous conversion, (channel) → void
    const samples = [];
    const t0 = Date.now();
    for (let i = 0; i < 20; i++) {
        samples.push(await adc.readContinuous());                           // Next fresh voltage, () → float V
    }
    const elapsed = (Date.now() - t0) / 1000;
    await adc.stopContinuous();                                             // Back to one-shot / standby, () → void
    console.log('min', Math.min(...samples).toFixed(5), 'max', Math.max(...samples).toFixed(5),
        'mean', (samples.reduce((a, b) => a + b, 0) / samples.length).toFixed(5), 'V,', (samples.length / elapsed).toFixed(0), 'samples/s');

    await adc.generalCallReset();                                           // Reset to power-on defaults, () → void

    await connection.close();
})();
