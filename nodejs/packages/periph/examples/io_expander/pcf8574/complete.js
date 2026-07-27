'use strict';
const { I2CConnection } = require('periph/src/connection/i2c');
const { Pcf8574Minimal, Pcf8574Full } = require('periph/src/chips/io_expander/pcf8574');

async function main() {
    const connection = new I2CConnection(1, 0x20);                 // Create I2C connection, (bus, addr=0x20)
    const chip       = new Pcf8574Full(connection);                 // Create PCF8574 full driver, (connection, addr=0x20)
                                                                   // initialises all pins as inputs; shadow = 0xFF

    // --- Pin proxy — async API ---
    const p0 = chip.pin(0, 'out');                                  // Get full pin proxy, (n, direction='out') → FullPin
                                                                   // drives P0 low initially; returns FullPin
    console.log('direction:', p0.direction);                       // direction property, () → string 'in'|'out'
                                                                   // reflects the direction set at pin() call time

    await p0.write(1);                                              // Write pin, (value=0|1) → Promise<void>
                                                                   // shadow |= (1<<0); quasi-high (input mode)
    await p0.write(0);                                              // Write pin, (value=0|1) → Promise<void>
                                                                   // shadow &= ~(1<<0); drives low

    const v = await p0.read();                                      // Read pin, () → Promise<int 0|1>
                                                                   // reads full port byte, extracts bit 0
    console.log('P0 level:', v);

    // --- Direction change ---
    await p0.setDirection('in');                                    // Set direction, (direction) → Promise<void>
                                                                   // updates shadow bit to 1 (input mode)
    console.log('now input');

    // --- Port-level bulk operations ---
    const mask = await chip.readPort();                             // Read all 8 pins, (port=0) → Promise<int bitmask>
                                                                   // bit 0 = P0, bit 7 = P7
    console.log('port:', mask.toString(16));
    await chip.writePort(0, 0b00001111);                            // Write all 8 pins, (port, mask) → Promise<void>
                                                                   // P0–P3 low (outputs), P4–P7 high (inputs)

    // --- Input pin ---
    const p4 = chip.pin(4, 'in');                                  // Get full pin proxy, (n, direction='in') → FullPin
    const state = await p4.read();                                  // Read pin, () → Promise<int>
                                                                   // 0 if button pulls P4 low, 1 if floating
    console.log('P4:', state);

    // --- Interrupt-on-change ---
    await chip.onInterrupt((changed) => {                           // Subscribe to INT line, (callback, options) → Promise<void>
        console.log('changed:', changed.toString(2).padStart(8, '0')); // no options.intPin = 5 ms polling fallback
    });

    const changed = await chip.pollInterrupt();                     // Read and return changed bitmask, () → Promise<int>
                                                                   // compares current byte to previous; clears INT
    console.log('changed on init:', changed);

    // --- Per-pin watch ---
    const p5 = chip.pin(5, 'in');                                  // Get full pin proxy, (n, direction='in') → FullPin
    const watcher = (pin) => console.log('P5 changed, pin:', pin);
    p5.watch(watcher, 'change');                                    // Subscribe to pin edges, (handler, trigger) → void
                                                                   // fires whenever P5 changes; needs onInterrupt armed
    p5.unwatch();                                                    // Unsubscribe pin handler, () → void
    p5.unexport();                                                   // Release pin (no-op), () → void

    process.exit(0);
}

main();
