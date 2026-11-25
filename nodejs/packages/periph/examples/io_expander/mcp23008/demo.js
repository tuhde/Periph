'use strict';
/**
 * MCP23008 demo — LED scanner with interrupt-driven button input.
 *
 * Hardware:
 *   GP0–GP3: four LEDs (anode → GP via 220 Ω, cathode → GND; active-high)
 *   GP4–GP7: four push buttons (pin → GND when pressed; 100 kΩ pull-up enabled)
 *
 * Runs a scanner pattern on the LEDs. Button presses raise an interrupt-on-change;
 * the callback prints the INTF mask and INTCAP snapshot and holds the LED
 * matching the pressed button (GP4→GP0, …) for one second.
 */
const { I2CConnection } = require('periph/src/connection/i2c');
const { Mcp23008Full } = require('periph/src/chips/io_expander/mcp23008');

async function main() {
    // --- Initialise bus and driver ---
    const connection = new I2CConnection(1, 0x20);                 // Create I2C connection, (bus, addr=0x20)
    const chip       = new Mcp23008Full(connection);                // Create MCP23008 full driver, (connection, addr=0x20)
    await chip.ready;

    // --- Configure LEDs and buttons ---
    // GP0–GP3 are outputs; GP4–GP7 stay inputs with pull-ups, so a pressed button reads 0.
    for (let n = 0; n < 4; n++) await chip.pin(n, 'out').setDirection('out'); // Set LED direction, (direction) → Promise<void>
    await chip.configurePullup(0xF0);                               // Configure pull-ups, (mask) → Promise<void>

    // --- Arm interrupt-on-change on the buttons ---
    // Any button edge raises INT (or the 5 ms poll sees it); the callback reports INTF and
    // INTCAP, then holds the matching LED.
    let holdUntil = 0;
    let holdMask = 0;
    await chip.enablePinInterrupt(0xF0);                            // Arm GP4–GP7, (mask) → Promise<void>
    await chip.onInterrupt(async (status) => {                      // Subscribe to INT, (callback, options) → Promise<void>
        const captured = await chip.readCapture();                  // Read port snapshot, () → Promise<int>
        console.log('button INTF=0x' + status.toString(16) + ' INTCAP=0x' + captured.toString(16));
        holdMask = (status >> 4) & 0x0F;
        holdUntil = Date.now() + 1000;
    });

    // --- Scanner loop ---
    let step = 0;
    console.log('Running — press buttons GP4–GP7 to hold the matching LED');
    const timer = setInterval(async () => {
        const leds = Date.now() < holdUntil ? holdMask : (1 << (step % 4));
        await chip.writePort(0, leds);                              // Write all 8 pins, (port=0, mask) → Promise<void>
        const port = await chip.readPort();                         // Read all 8 pins, (port=0) → Promise<int bitmask>
        console.log('pos=' + (step % 4) + '  port=0x' + port.toString(16).padStart(2, '0'));
        step++;
        if (step >= 40) {
            clearInterval(timer);
            await chip.offInterrupt();                              // Unsubscribe, () → Promise<void>
            await chip.writePort(0, 0x00);                          // Clear all outputs, (port=0, mask=0x00) → Promise<void>
            await connection.close();
        }
    }, 100);
}

main();
