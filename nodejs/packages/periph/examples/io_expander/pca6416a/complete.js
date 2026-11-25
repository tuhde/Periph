'use strict';
const { I2CConnection } = require('periph/src/connection/i2c');
const { Pca6416aFull } = require('periph/src/chips/io_expander/pca6416a');

async function main() {
    const connection = new I2CConnection(1, 0x20);                 // Create I2C connection, (bus, addr=0x20)
    const chip       = new Pca6416aFull(connection);                // Create PCA6416A full driver, (connection, addr=0x20)
                                                                    // writes the reset values; all 16 pins start as inputs
    await chip.ready;

    // --- Pin proxy — async API ---
    const p0 = chip.pin(0, 'out');                                  // Get full pin proxy, (n, direction='in') → FullPin
                                                                    // P0_0 as output; every pin is bidirectional
    console.log('direction:', p0.direction);                        // direction property, () → string 'in'|'out'
    await p0.write(0);                                              // Write pin, (value=0|1) → Promise<void>
                                                                    // clears the OUTPUT bit via the shadow
    const v = await p0.read();                                      // Read pin, () → Promise<int 0|1>
                                                                    // reads INPUT (actual pin level)
    console.log('P0 level:', v);
    await p0.setDirection('in');                                    // Set direction, (direction) → Promise<void>
                                                                    // writes CONFIG; OUTPUT is latched first for outputs

    // --- Port-level operations ---
    await chip.writePort(0, 0b00001111);                            // Write port 0, (port, mask) → Promise<void>
                                                                    // single-port OUTPUT write
    await chip.writeAll(0xA50F);                                    // Write all 16 pins, (value) → Promise<void>
                                                                    // one two-byte write at OUTPUT0 (pair toggling)
    const port = await chip.readPort(1);                            // Read port 1, (port) → Promise<int bitmask>
                                                                    // INPUT1 register
    console.log('PORT1=0x' + port.toString(16).padStart(2, '0'));
    const all = await chip.readAll();                               // Read all 16 pins, () → Promise<int>
                                                                    // one two-byte read at INPUT0
    console.log('ALL=0x' + all.toString(16).padStart(4, '0'));

    // --- Configuration ---
    await chip.configurePolarity(0x0000);                           // Configure input polarity, (mask) → Promise<void>
                                                                    // 1 bits invert the INPUT read
    await chip.setDirection(0xFF00);                                // Set whole-device direction, (mask) → Promise<void>
                                                                    // port 0 outputs, port 1 inputs; OUTPUT is written first
    const dir = chip.getDirection();                                // Read direction shadow, () → int
                                                                    // 1 = input, 0 = output
    console.log('CONFIG=0x' + dir.toString(16).padStart(4, '0'));
    const config = await chip.readRegister(0x07);                   // Read raw register, (reg) → Promise<int>
                                                                    // escape hatch for debugging
    console.log('CONFIG1=0x' + config.toString(16).padStart(2, '0'));
    await chip.writeRegister(0x05, 0x10);                           // Write raw register, (reg, value) → Promise<void>
                                                                    // POLARITY1: invert P1_4

    // --- Interrupts ---
    await chip.onInterrupt((changed) => {                           // Subscribe to INT, (callback, options) → Promise<void>
        console.log('interrupt, changed=0x' + changed.toString(16));// INT line if wired, else 5 ms polling
    });
    const p12 = chip.pin(12, 'in');
    await p12.watch((pin) => console.log('P1_4 changed'), 'change');// Watch a pin, (handler, trigger='change') → Promise<void>
                                                                    // edge filtered in software; all pins share INT
    const changed = await chip.pollInterrupt();                     // Read INPUT0/1, get changed mask, () → Promise<int>
                                                                    // XOR with the last INPUT values
    console.log('changed=0x' + changed.toString(16));
    await p12.unwatch();                                            // Stop watching, () → Promise<void>
                                                                    // drops the handler
    await chip.offInterrupt();                                      // Unsubscribe, () → Promise<void>
                                                                    // stops delivery
    await chip.reset();                                             // Restore reset values, () → Promise<void>
                                                                    // OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF (both ports)
    await connection.close();
}

main();
