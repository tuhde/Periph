'use strict';
const { I2CConnection } = require('periph/src/connection/i2c');
const { Mcp23017Minimal, Mcp23017Full } = require('periph/src/chips/io_expander/mcp23017');

async function main() {
    const connection = new I2CConnection(1, 0x20);                 // Create I2C connection, (bus, addr=0x20)
    const chip       = new Mcp23017Full(connection);                // Create MCP23017 full driver, (connection, addr=0x20)

    // --- Pin proxy — async API ---
    const p0 = chip.pin(0, 'out');                                  // Get full pin proxy, (n, direction='out') → FullPin
                                                                    // GPA0 as output (GPA7/GPB7 are output-only on hardware)
    console.log('direction:', p0.direction);                        // direction property, () → string 'in'|'out'

    await p0.write(1);                                              // Write pin, (value=0|1) → Promise<void>
    await p0.write(0);                                              // Write pin, (value=0|1) → Promise<void>

    const v = await p0.read();                                      // Read pin, () → Promise<int 0|1>
                                                                    // reads GPIOA, extracts bit 0
    console.log('GPA0 level:', v);

    // --- Direction change ---
    await p0.setDirection('in');                                    // Set direction, (direction) → Promise<void>
    console.log('now input');

    // --- Port-level bulk operations ---
    const porta = await chip.readPort(0);                           // Read all 8 pins, (port=0) → Promise<int bitmask>
                                                                    // PORTA = GPIOA register
    const portb = await chip.readPort(1);                           // Read all 8 pins, (port=1) → Promise<int bitmask>
                                                                    // PORTB = GPIOB register
    console.log('PORTA=0x' + porta.toString(16).padStart(2, '0') + '  PORTB=0x' + portb.toString(16).padStart(2, '0'));
    await chip.writePort(0, 0b00001111);                            // Write all 8 pins, (port, mask) → Promise<void>
                                                                    // GPA0–GPA3 as outputs low, GPA4–GPA6 inputs, GPA7 output low
    await chip.writePort(1, 0b11110000);                            // Write all 8 pins, (port, mask) → Promise<void>
                                                                    // GPB0–GPB3 outputs low, GPB4–GPB7 outputs high

    // --- Pull-up configuration ---
    await chip.configurePullup(0, 0b01111111);                      // Configure pull-ups, (port, mask) → Promise<void>
                                                                    // enable pull-ups on GPA0–GPA6 (inputs); GPA7 is output, pull-up has no effect

    // --- Polarity inversion ---
    await chip.configurePolarity(1, 0x00);                          // Configure polarity, (port, mask) → Promise<void>
                                                                    // normal polarity on PORTB

    // --- Interrupt-on-change (single port) ---
    await chip.onInterruptPort(0, (changed) => {                    // Subscribe to INT on one port, (port, callback, options) → Promise<void>
        console.log('PORTA changed: 0x' + changed.toString(16).padStart(2, '0'));
    });
                                                                    // no options.intPin = 5 ms polling fallback; INTA fires on any GPA change

    const changed = await chip.pollInterrupt(0);                    // Read and clear interrupt, (port) → Promise<int>
                                                                    // INTFA flags; also reads INTCAPA to clear
    console.log('changed on init:', changed);

    const captured = await chip.readCapture(0);                     // Read INTCAP, (port) → Promise<int>
                                                                    // captured PORTA state at moment of interrupt; also clears
    console.log('captured PORTA: 0x' + captured.toString(16).padStart(2, '0'));

    // --- Per-pin watch ---
    const p1 = chip.pin(1, 'in');                                   // Get full pin proxy, (n, direction='in') → FullPin
    const watcher = (pin) => console.log('GPA1 changed, pin:', pin);
    p1.watch(watcher, 'change');                                    // Subscribe to pin edges, (handler, trigger) → void
                                                                    // fires when GPA1 changes; needs onInterruptPort(0, ...) armed first
    p1.unwatch();                                                    // Unsubscribe pin handler, () → void
    p1.setActiveLow(false);                                         // Set active-low inversion, (invert) → void

    // --- Stop interrupt ---
    await chip.offInterruptPort(0);                                 // Unsubscribe PORTA, (port) → Promise<void>
                                                                    // clears GPINTENA; stops polling

    process.exit(0);
}

main();
