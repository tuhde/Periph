'use strict';
const { I2CTransport } = require('periph/src/transport/i2c');
const { Mcp23017Minimal, Mcp23017Full } = require('periph/src/chips/io_expander/mcp23017');

const transport = new I2CTransport(1, 0x20);                   // Create I2C transport, (bus, addr=0x20)
const chip      = new Mcp23017Full(transport);                   // Create MCP23017 full driver, (transport, addr=0x20)

// --- Pin proxy — synchronous API ---
const p0 = chip.pin(0, 'out');                                  // Get full pin proxy, (n, direction='out') → FullPin
                                                                // GPA0 as output (GPA7/GPB7 are output-only on hardware)
console.log('direction:', p0.direction);                        // direction property, () → string 'in'|'out'

p0.writeSync(1);                                               // Write pin synchronously, (value=0|1) → void
p0.writeSync(0);                                               // Write pin synchronously, (value=0|1) → void

const v = p0.readSync();                                       // Read pin synchronously, () → int 0|1
                                                                // reads GPIOA, extracts bit 0
console.log('GPA0 level:', v);

// --- Async API ---
p0.read((err, val) => {                                        // Read pin async, (callback(err,val)) → void
    if (!err) console.log('async read:', val);
});

p0.write(1, (err) => {                                         // Write pin async, (value, callback(err)) → void
    if (!err) console.log('async write done');
});

// --- Direction change ---
p0.setDirection('in', (err) => {                               // Set direction, (direction, callback(err)) → void
    if (!err) console.log('now input');
});

// --- Port-level bulk operations ---
const porta = chip.readPort(0);                                 // Read all 8 pins, (port=0) → int bitmask
                                                                // PORTA = GPIOA register
const portb = chip.readPort(1);                                 // Read all 8 pins, (port=1) → int bitmask
                                                                // PORTB = GPIOB register
console.log('PORTA=0x' + porta.toString(16).padStart(2,'0') + '  PORTB=0x' + portb.toString(16).padStart(2,'0'));
chip.writePort(0, 0b00001111);                                  // Write all 8 pins, (port, mask) → void
                                                                // GPA0–GPA3 as outputs low, GPA4–GPA6 inputs, GPA7 output low
chip.writePort(1, 0b11110000);                                  // Write all 8 pins, (port, mask) → void
                                                                // GPB0–GPB3 outputs low, GPB4–GPB7 outputs high

// --- Pull-up configuration ---
chip.configurePullup(0, 0b01111111);                           // Configure pull-ups, (port, mask) → void
                                                                // enable pull-ups on GPA0–GPA6 (inputs); GPA7 is output, pull-up has no effect

// --- Polarity inversion ---
chip.configurePolarity(1, 0x00);                                // Configure polarity, (port, mask) → void
                                                                // normal polarity on PORTB

// --- Interrupt-on-change ---
chip.configureInterrupt(0, null, (port, changed) => {           // Attach interrupt, (port, intGpioPath, callback) → void
    console.log('PORTA changed: 0x' + changed.toString(16).padStart(2,'0'));
});
                                                                // null = use 5 ms polling; INTA fires on any GPA change

const flags = chip.readInterruptFlags(0);                      // Read interrupt flags, (port) → int
                                                                // INTFA register; 1 = corresponding pin caused interrupt
console.log('INT flags PORTA: 0x' + flags.toString(16).padStart(2,'0'));

const changed = chip.clearInterrupt(0);                         // Read and clear interrupt, (port) → int
                                                                // INTCAPA captured state; also returns changed bitmask
console.log('changed on init:', changed);

// --- Per-pin watch ---
const p1 = chip.pin(1, 'in');                                   // Get full pin proxy, (n, direction='in') → FullPin
const watcher = (err, val) => console.log('GPA1 changed:', val);
p1.watch(watcher);                                              // Attach change handler, (handler(err,val)) → void
                                                                // fires when GPA1 changes; needs configureInterrupt first
p1.unwatch(watcher);                                           // Remove change handler, (handler) → void
p1.unwatchAll();                                                // Remove all handlers for pin, () → void
p1.setActiveLow(false);                                        // Set active-low inversion, (invert) → void

// --- Stop interrupt ---
chip.stopInterrupt(0);                                         // Disable interrupt, (port) → void
                                                                // clears GPINTENA; stops polling

process.exit(0);