'use strict';
const { I2CConnection } = require('periph/src/connection/i2c');
const { Pca9536Full } = require('periph/src/chips/io_expander/pca9536');

async function main() {
    const connection = new I2CConnection(1, 0x41);                 // Create I2C connection, (bus, addr=0x41)
    const chip       = new Pca9536Full(connection);                // Create PCA9536 full driver, (connection, addr=0x41)
                                                                    // writes the reset values; all pins start as inputs
    await chip.ready;

    // --- Pin proxy — async API ---
    const p0 = chip.pin(0, 'out');                                  // Get full pin proxy, (n, direction='in') → FullPin
                                                                    // P0 as output; all four pins are bidirectional
    console.log('direction:', p0.direction);                        // direction property, () → string 'in'|'out'
    await p0.write(0);                                              // Write pin, (value=0|1) → Promise<void>
                                                                    // clears the OUTPUT bit via the shadow
    const v = await p0.read();                                      // Read pin, () → Promise<int 0|1>
                                                                    // reads INPUT (actual pin level)
    console.log('P0 level:', v);
    await p0.setDirection('in');                                    // Set direction, (direction) → Promise<void>
                                                                    // writes CONFIG; OUTPUT is latched first for outputs

    // --- Port-level operations ---
    await chip.writePort(0, 0b00000101);                            // Write all 4 pins, (port=0, mask) → Promise<void>
                                                                    // whole-port OUTPUT write
    const port = await chip.readPort();                             // Read all 4 pins, (port=0) → Promise<int bitmask>
                                                                    // INPUT register
    console.log('PORT=0x' + port.toString(16).padStart(1, '0'));

    // --- Configuration ---
    await chip.configurePolarity(0x00);                             // Configure input polarity, (mask) → Promise<void>
                                                                    // 1 bits invert the INPUT read
    await chip.setDirection(0x0C);                                  // Set whole-port direction, (mask) → Promise<void>
                                                                    // P0–P1 outputs, P2–P3 inputs; OUTPUT is written first
    const dir = chip.getDirection();                                // Read direction shadow, () → int
                                                                    // 1 = input, 0 = output
    console.log('CONFIG=0x' + dir.toString(16).padStart(1, '0'));
    const config = await chip.readRegister(0x03);                   // Read raw register, (reg) → Promise<int>
                                                                    // escape hatch for debugging
    console.log('CONFIG=0x' + config.toString(16).padStart(1, '0'));
    await chip.writeRegister(0x02, 0x04);                           // Write raw register, (reg, value) → Promise<void>
                                                                    // POLARITY: invert P2

    // --- Change detection (polling) ---
    chip.watchChanges((changed) => {                                // Start polling for changes, (callback, intervalS=0.005) → void
        console.log('changed=0x' + changed.toString(16));           // no INT pin exists; a timer polls INPUT
    });
    const p2 = chip.pin(2, 'in');
    await p2.watch((pin) => console.log('P2 changed'), 'change');   // Watch a pin, (handler, trigger='change') → Promise<void>
                                                                    // edge filtered in software; all pins share one poll
    const changed = await chip.pollChanges();                       // Read INPUT, get changed mask, () → Promise<int>
                                                                    // XOR with the last INPUT value
    console.log('changed=0x' + changed.toString(16));
    await p2.unwatch();                                             // Stop watching, () → Promise<void>
                                                                    // drops the handler
    chip.unwatchChanges();                                          // Stop polling, () → void
                                                                    // clears the timer
    await chip.reset();                                             // Restore reset values, () → Promise<void>
                                                                    // OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF
    await connection.close();
}

main();
