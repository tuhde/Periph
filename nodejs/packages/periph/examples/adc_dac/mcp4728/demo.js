'use strict';

const { I2CTransport } = require('periph/src/transport/i2c');
const { MCP4728Full } = require('periph/src/chips/adc_dac/mcp4728');

const VDD = 3.3;

const transport = new I2CTransport(1, 0x60);     // Create I2C transport, (bus=1, addr=0x60)
const dac = new MCP4728Full(transport);            // Create MCP4728 driver, (transport)

// --- Apply four-point calibration voltages to channels A–D ---
// A 4-channel DAC is the canonical way to bias a 4-point sensor bridge
// (load cell, RTD conditioning, strain gauge). Each channel gets a
// different fraction of full scale to demonstrate independent outputs.
// Voltages printed below assume a 3.3 V supply.
const calibration = [0.0, 1.0 / 3, 2.0 / 3, 1.0];
dac.set_all(calibration);                          // Update all four channels simultaneously, (fractions[4]) → None
for (let ch = 0; ch < 4; ch++) {
    const code = Math.round(calibration[ch] * 4095);
    console.log(`ch=${ch} fraction=${calibration[ch].toFixed(4)} code=${code} approx_v=${(code * VDD / 4096).toFixed(3)}V`);
}

// --- Synchronous staircase from 0 to full scale on all four channels ---
// Using set_all with the same fraction across channels keeps them in lock-step
// and demonstrates simultaneous V_OUT update via Fast Write. A 50 ms pause
// between steps lets the host controller observe each level on the scope.
async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }
(async () => {
    const STEP = 1.0 / 16;
    for (let n = 0; n <= 16; n++) {
        const f = n * STEP;
        dac.set_all([f, f, f, f]);                 // Update all four channels simultaneously, (fractions[4]) → None
        const code = Math.round(f * 4095);
        console.log(`step=${n.toString().padStart(2)} fraction=${f.toFixed(4)} code=${code} approx_v=${(code * VDD / 4096).toFixed(3)}V`);
        await sleep(50);
    }

    // --- Reset all channels to 0 V before exit ---
    dac.set_all([0.0, 0.0, 0.0, 0.0]);             // Update all four channels simultaneously, (fractions[4]) → None
    transport.close();
})();
