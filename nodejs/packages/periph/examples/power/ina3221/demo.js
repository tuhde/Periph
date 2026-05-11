'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { INA3221Full } = require('../../../src/chips/power/ina3221');

const transport = new I2CTransport(1, 0x40);
const ina = new INA3221Full(transport);                // Create INA3221 driver, (transport, r_shunt=0.1 Ω)

// --- Monitor three rails simultaneously ---
// User wires CH1 to 5V rail, CH2 to 3.3V rail, CH3 to 12V rail.
// The demo prints a one-line tabular update each second for 30 seconds.
console.log('V1       I1       P1       V2       I2       P2       V3       I3       P3');
let t = 0;
const interval = setInterval(() => {
    const row = [];
    for (const ch of [1, 2, 3]) {
        const v = ina.voltage(ch);                      // Read bus voltage, (channel) → float V
        const i = ina.current(ch);                      // Read load current, (channel) → float A
        const p = ina.power(ch);                        // Read power, (channel) → float W
        row.push(v.toFixed(3), i.toFixed(4), p.toFixed(4));
    }
    console.log(row.join(' '));

    if (t === 9) {
        // --- Arm critical-alert limits at 1.5x current draw ---
        for (const ch of [1, 2, 3]) {
            const i = ina.current(ch);
            ina.setCriticalAlert(ch, i * 1.5);
        }
        console.log('alerts armed');
    }

    if (t === 19) {
        // --- Arm shunt-voltage summation across all three channels ---
        ina.setSummationChannels([1, 2, 3], 0.3);     // Set summation channels, (channels, limit_v) → None
                                                        // configures SCC bits and sum limit register
        console.log('summation armed');
    }

    t++;
    if (t >= 30) {
        clearInterval(interval);

        // --- Dump alert flags and decode any that fired ---
        const flags = ina.alertFlags();                // Read alert flags, () → int
                                                        // reads Mask/Enable register, clears latched flags
        console.log('Mask/Enable: 0x' + flags.toString(16).toUpperCase());
        const alertNames = ['CF1', 'CF2', 'CF3', 'SF', 'WF1', 'WF2', 'WF3', 'PVF', 'TCF', 'CVRF'];
        const alertBits  = [0x0200, 0x0100, 0x0080, 0x0040, 0x0020, 0x0010, 0x0008, 0x0004, 0x0002, 0x0001];
        const fired = alertNames.filter((_, k) => flags & alertBits[k]);
        if (fired.length > 0) console.log('Flags fired: ' + fired.join(', '));
        else console.log('No alert flags fired');

        transport.close();
    }
}, 1000);