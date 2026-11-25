'use strict';
const { I2CConnection } = require('periph/src/connection/i2c');
const { Mcp23008Full } = require('periph/src/chips/io_expander/mcp23008');

async function main() {
    const connection = new I2CConnection(1, 0x20);                 // Create I2C connection, (bus, addr=0x20)
    const chip       = new Mcp23008Full(connection);                // Create MCP23008 full driver, (connection, addr=0x20)
                                                                    // clears OLAT; all pins start as inputs
    await chip.ready;

    // --- Pin proxy — async API ---
    const p0 = chip.pin(0, 'out');                                  // Get full pin proxy, (n, direction='in') → FullPin
                                                                    // GP0 as output; GP7 is a normal bidirectional pin
    console.log('direction:', p0.direction);                        // direction property, () → string 'in'|'out'
    await p0.write(1);                                              // Write pin, (value=0|1) → Promise<void>
                                                                    // sets the OLAT bit via the shadow
    const v = await p0.read();                                      // Read pin, () → Promise<int 0|1>
                                                                    // reads GPIO (actual pin level)
    console.log('GP0 level:', v);
    await p0.setDirection('in');                                    // Set direction, (direction) → Promise<void>
                                                                    // writes IODIR; OLAT is latched first for outputs

    // --- Port-level operations ---
    await chip.writePort(0, 0b00001111);                            // Write all 8 pins, (port=0, mask) → Promise<void>
                                                                    // whole-port OLAT write
    const port = await chip.readPort();                             // Read all 8 pins, (port=0) → Promise<int bitmask>
                                                                    // GPIO register
    console.log('GPIO=0x' + port.toString(16).padStart(2, '0'));

    // --- Configuration ---
    await chip.configurePullup(0xF0);                               // Configure pull-ups, (mask) → Promise<void>
                                                                    // 100 kΩ pull-ups on GP4–GP7 (inputs only)
    await chip.configurePolarity(0x00);                             // Configure input polarity, (mask) → Promise<void>
                                                                    // 1 bits invert the GPIO read
    await chip.setDefaultValue(0x00);                               // Set DEFVAL, (mask) → Promise<void>
                                                                    // reference for default-compare interrupts
    await chip.configureIntPin({ openDrain: false, activeHigh: false }); // Configure INT output, (options) → Promise<void>
                                                                    // push-pull, active-low (IOCON ODR/INTPOL)
    await chip.setSequential(true);                                 // Sequential access, (enabled) → Promise<void>
                                                                    // IOCON SEQOP = 0 (driver default)
    const iodir = await chip.readRegister(0x00);                    // Read raw register, (reg) → Promise<int>
                                                                    // escape hatch for debugging
    console.log('IODIR=0x' + iodir.toString(16).padStart(2, '0'));
    await chip.writeRegister(0x02, 0x10);                           // Write raw register, (reg, value) → Promise<void>
                                                                    // GPINTEN: arm GP4

    // --- Interrupts ---
    await chip.enablePinInterrupt(0xF0);                            // Arm interrupt-on-change, (mask) → Promise<void>
                                                                    // GP4–GP7
    await chip.onInterrupt((status) => {                            // Subscribe to INT, (callback, options) → Promise<void>
        console.log('interrupt, INTF=0x' + status.toString(16));    // INT line if wired, else 5 ms polling
    }, { mode: 'default' });
    const p4 = chip.pin(4, 'in');
    await p4.watch((pin) => console.log('GP4 changed'), 'change');  // Watch a pin, (handler, trigger='change') → Promise<void>
                                                                    // arms GP4 in GPINTEN, edge filtered in software
    const flags = await chip.pollInterrupt();                       // Read INTF and clear, () → Promise<int>
                                                                    // INTCAP is read and discarded
    const cap = await chip.readCapture();                           // Read captured port, () → Promise<int>
                                                                    // port snapshot at the interrupt
    console.log('INTF=0x' + flags.toString(16) + ' INTCAP=0x' + cap.toString(16));
    await p4.unwatch();                                             // Stop watching, () → Promise<void>
                                                                    // clears its GPINTEN bit
    await chip.offInterrupt();                                      // Unsubscribe, () → Promise<void>
                                                                    // stops delivery
    await chip.reset();                                             // Restore reset values, () → Promise<void>
                                                                    // rewrites all registers, clears shadows
    await connection.close();
}

main();
