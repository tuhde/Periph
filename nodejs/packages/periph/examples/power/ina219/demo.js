'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { INA219Full }   = require('../../../src/chips/power/ina219');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x40', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const ina = new INA219Full(transport);

// --- Configure for noise-sensitive power rail monitoring ---
// 128-sample averaging suppresses switching noise on a noisy 5 V rail;
// continuous mode avoids re-triggering overhead between measurements.
ina.configure(1, 3, 0x0F, 0x0F, 7);
                                                // Configure ADC, (brng 0–1, pga 0–3, badc 0x0F, sadc 0x0F, mode 0–7) → None

console.log('V          A          W');

setInterval(() => {
    while (!ina.conversionReady()) {}           // Check conversion done, () → bool
    const v = ina.voltage();                    // Read bus voltage, () → float V
    const i = ina.current();                    // Read load current, () → float A
    const p = ina.power();                      // Read power, () → float W
    console.log(`${v.toFixed(3)} V   ${i.toFixed(4)} A   ${p.toFixed(4)} W`);
}, 1000);