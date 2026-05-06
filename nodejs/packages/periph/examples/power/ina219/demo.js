'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { INA219Full } = require('../../../src/chips/power/ina219');

const transport = new I2CTransport(1, 0x40);
const ina = new INA219Full(transport);

ina.configure(INA219Full.BRNG_32V, INA219Full.PGA_8, INA219Full.ADC_12BIT, INA219Full.ADC_12BIT, INA219Full.MODE_SHUNT_BUS_CONT);

console.log('V_bus       V_shunt     I          P');

const vList = [];
const iList = [];
const pList = [];

let count = 0;
const interval = setInterval(() => {
    const v = ina.voltage();
    const vs = ina.shuntVoltage();
    const i = ina.current();
    const p = ina.power();

    vList.push(v);
    iList.push(i);
    pList.push(p);

    console.log(v.toFixed(3) + 'V   ' + vs.toFixed(5) + 'V   ' + i.toFixed(4) + 'A   ' + p.toFixed(4) + 'W');

    if (count === 3) {
        console.log('>>> Switch on your load now <<<');
    }

    count++;
    if (count >= 10) {
        clearInterval(interval);
        console.log('min: ' + Math.min(...vList).toFixed(3) + ' V  ' + Math.min(...iList).toFixed(4) + ' A  ' + Math.min(...pList).toFixed(4) + ' W');
        console.log('max: ' + Math.max(...vList).toFixed(3) + ' V  ' + Math.max(...iList).toFixed(4) + ' A  ' + Math.max(...pList).toFixed(4) + ' W');
        console.log('mean: ' + (vList.reduce((a, b) => a + b) / 10).toFixed(3) + ' V  ' + (iList.reduce((a, b) => a + b) / 10).toFixed(4) + ' A  ' + (pList.reduce((a, b) => a + b) / 10).toFixed(4) + ' W');
        transport.close();
    }
}, 1000);