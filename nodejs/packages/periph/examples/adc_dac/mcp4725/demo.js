'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { MCP4725Full } = require('../../../src/chips/adc_dac/mcp4725');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x60', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const dac = new MCP4725Full(transport);

const STEP = 1.0 / 20.0;
const DELAY_MS = 100;

function delay(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

async function run_triangle_wave() {
    console.log('MCP4725 demo: triangle wave');
    // Up sweep: 0 to full scale in 21 steps
    for (let n = 0; n <= 20; n++) {
        const fraction = n * STEP;
        dac.set_voltage(fraction);             // Set output as fraction of V_DD, (fraction=0.0–1.0) → None
        const code = Math.round(fraction * 4095);
        const approx_v = code * 3.3 / 4096;
        console.log(`n=${String(n).padStart(2)} fraction=${fraction.toFixed(2)} code=${String(code).padStart(4)} approx_v=${approx_v.toFixed(3)}V`);
        await delay(DELAY_MS);
    }
    // Down sweep: full scale back to 0 in 20 steps
    for (let n = 20; n >= 0; n--) {
        const fraction = n * STEP;
        dac.set_voltage(fraction);             // Set output as fraction of V_DD, (fraction=0.0–1.0) → None
        const code = Math.round(fraction * 4095);
        const approx_v = code * 3.3 / 4096;
        console.log(`n=${String(n).padStart(2)} fraction=${fraction.toFixed(2)} code=${String(code).padStart(4)} approx_v=${approx_v.toFixed(3)}V`);
        await delay(DELAY_MS);
    }
    console.log('Demo complete');
    process.exit(0);
}

run_triangle_wave();