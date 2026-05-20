'use strict';
const { I2CTransport } = require('periph/src/transport/i2c');
const { Pcf8574Minimal, Pcf8574Full } = require('periph/src/chips/io_expander/pcf8574');

const transport = new I2CTransport(1, 0x20);                   // Create I2C transport, (bus, addr=0x20)
const chip      = new Pcf8574Full(transport);                   // Create PCF8574 full driver, (transport, addr=0x20)
                                                               // initialises all pins as inputs; shadow = 0xFF

// --- Pin proxy — synchronous API ---
const p0 = chip.pin(0, 'out');                                  // Get full pin proxy, (n, direction='out') → FullPin
                                                               // drives P0 low initially; returns FullPin
console.log('direction:', p0.direction);                       // direction property, () → string 'in'|'out'
                                                               // reflects the direction set at pin() call time

p0.writeSync(1);                                               // Write pin synchronously, (value=0|1) → void
                                                               // shadow |= (1<<0); quasi-high (input mode)
p0.writeSync(0);                                               // Write pin synchronously, (value=0|1) → void
                                                               // shadow &= ~(1<<0); drives low

const v = p0.readSync();                                       // Read pin synchronously, () → int 0|1
                                                               // reads full port byte, extracts bit 0
console.log('P0 level:', v);

// --- Async API ---
p0.read((err, val) => {                                        // Read pin async, (callback(err,val)) → void
    if (!err) console.log('async read:', val);                 // wraps readSync; fires callback immediately
});

p0.write(1, (err) => {                                         // Write pin async, (value, callback(err)) → void
    if (!err) console.log('async write done');                 // wraps writeSync; fires callback immediately
});

// --- Direction change ---
p0.setDirection('in', (err) => {                               // Set direction, (direction, callback(err)) → void
    if (!err) console.log('now input');                        // updates shadow bit to 1 (input mode)
});

// --- Port-level bulk operations ---
const mask = chip.readPort();                                  // Read all 8 pins, (port=0) → int bitmask
                                                               // bit 0 = P0, bit 7 = P7
console.log('port:', mask.toString(16));
chip.writePort(0, 0b00001111);                                 // Write all 8 pins, (port, mask) → void
                                                               // P0–P3 low (outputs), P4–P7 high (inputs)

// --- Input pin ---
const p4 = chip.pin(4, 'in');                                  // Get full pin proxy, (n, direction='in') → FullPin
const state = p4.readSync();                                   // Read pin synchronously, () → int
                                                               // 0 if button pulls P4 low, 1 if floating
console.log('P4:', state);

// --- Interrupt-on-change ---
chip.configureInterrupt(null, (changed) => {                   // Attach interrupt, (intGpioPath, callback) → void
    console.log('changed:', changed.toString(2).padStart(8,'0')); // null = use 5 ms polling
});

const changed = chip.clearInterrupt();                         // Read and return changed bitmask, () → int
                                                               // compares current byte to previous; clears INT
console.log('changed on init:', changed);

// --- Per-pin watch ---
const p5 = chip.pin(5, 'in');                                  // Get full pin proxy, (n, direction='in') → FullPin
const watcher = (err, val) => console.log('P5 changed:', val);
p5.watch(watcher);                                             // Attach change handler, (handler(err,val)) → void
                                                               // fires whenever P5 changes; needs configureInterrupt
p5.unwatch(watcher);                                           // Remove change handler, (handler) → void
p5.unexport();                                                  // Release pin (no-op), () → void

process.exit(0);
