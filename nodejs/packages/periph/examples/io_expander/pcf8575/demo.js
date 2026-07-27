'use strict';
const { Pcf8575Full } = require('../../src/chips/io_expander/pcf8575');

const connection = {
    async write(buf) { /* LED write */ },
    async read(len) { return Buffer.from([0xFF, 0xFF]); }
};

async function main() {
    const chip = new Pcf8575Full(connection, 0x20);             // Create PCF8575 full driver, (connection, addr=0x20)

    await chip.writePort(0, 0xFF);                               // Write Port 0, (port=0, mask=int) → Promise<void>
    await chip.writePort(1, 0xFF);                               // Write Port 1, (port=1, mask=int) → Promise<void>

    await chip.onInterrupt((changed) => {                        // Subscribe to INT line, (callback, options) → Promise<void>
        console.log('changed:', changed);
    });

    console.log('Running — buttons on P10–P17 mirror to LEDs on P00–P07');
    setInterval(async () => {
        const port0 = await chip.readPort(0);                    // Read Port 0, (port=0) → Promise<int bitmask>
        const port1 = await chip.readPort(1);                    // Read Port 1, (port=1) → Promise<int bitmask>

        const buttons = port1 & 0xFF;                           // P10–P17 (pressed = 0)
        const led_bits = (~buttons) & 0xFF;                     // invert: pressed → LED on (0)
        await chip.writePort(0, led_bits);                       // Write Port 0, (port=0, mask=int) → Promise<void>

        console.log(
            'P0=0x' + port0.toString(16).padStart(2, '0') +
            '  P1=0x' + port1.toString(16).padStart(2, '0') +
            '  buttons=' + Array.from({length: 8}, (_, i) => (buttons >> (7 - i)) & 1 ? '.' : 'X').join('') +
            '  LEDs=' + Array.from({length: 8}, (_, i) => (led_bits >> (7 - i)) & 1 ? ' ' : '*').join('')
        );
    }, 200);
}

main();
