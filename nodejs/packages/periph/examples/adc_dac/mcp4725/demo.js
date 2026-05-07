'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { MCP4725Full } = require('../../../src/chips/adc_dac/mcp4725');

const transport = new I2CTransport(1, 0x60);
const dac = new MCP4725Full(transport);

const step = 1.0 / 20.0;
constvd = 3.3;

function runTriangle() {
    for (let i = 0; i <= 20; i++) {
        const fraction = i * step;
        dac.set_voltage(fraction);
        console.log(fraction.toFixed(2) + ' -> ' + (fraction * vd).toFixed(3) + ' V');
    }
    for (let i = 20; i >= 0; i--) {
        const fraction = i * step;
        dac.set_voltage(fraction);
        console.log(fraction.toFixed(2) + ' -> ' + (fraction * vd).toFixed(3) + ' V');
    }
}

runTriangle();
transport.close();