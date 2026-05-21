'use strict';
/**
 * MCP23017 demo — Knight Rider scanner with button override.
 *
 * Hardware:
 *   GPA0–GPA6: seven LEDs (anode → VCC via 220Ω, cathode → pin; active-high)
 *   GPB0–GPB6: seven push buttons (pin → GND when pressed; internal pull-up = high when idle)
 *
 * Runs a Knight Rider scanning pattern on the PORTA LEDs. Pressing a button
 * overrides the scanner and lights the matching LED. The loop reads GPIOB
 * every 100 ms, builds the output mask from the button state (inverted, since
 * active-low buttons), ORs it with the scanner position unless a button is
 * pressed, then writes to OLATA.
 */
const { I2CTransport } = require('periph/src/transport/i2c');
const { Mcp23017Full } = require('periph/src/chips/io_expander/mcp23017');

// --- Initialise bus and driver ---
const transport = new I2CTransport(1, 0x20);                   // Create I2C transport, (bus, addr=0x20)
const chip      = new Mcp23017Full(transport);                   // Create MCP23017 full driver, (transport, addr=0x20)

// Enable pull-ups on PORTB inputs (GPB0–GPB6) so idle buttons read high.
chip.configurePullup(1, 0b01111111);                            // Configure pull-ups, (port=1, mask) → void

// --- Wire interrupt for responsive button detection ---
chip.configureInterrupt(1, null, (port, changed) => {          // Attach interrupt, (port, intGpioPath, callback) → void
    // handled in the polling loop below
});

let position = 0;
let direction = 1;

// --- Main loop: Knight Rider with button override ---
console.log('Running — press buttons GPB0–GPB6 to light corresponding LEDs');
setInterval(() => {
    const portb = chip.readPort(1);                             // Read all 8 pins, (port=1) → int bitmask
                                                                // GPB0–GPB6 buttons: pressed = 0 (active-low pull-down)

    const buttons = portb & 0x7F;   // mask GPA7 (output-only)
    const pressed = (~buttons) & 0x7F;   // invert: pressed button = bit 1

    const scanner = 1 << position;

    let output;
    if (pressed) {
        output = pressed | (1 << 7);   // keep GPA7 high (output-only)
    } else {
        output = scanner | (1 << 7);
    }

    chip.writePort(0, output);                                  // Write all 8 pins, (port=0, mask) → void

    const ledStr = [...Array(7)].map((_, i) => (output >> i) & 1 ? '*' : ' ').join('');
    console.log('PORTA=0x' + output.toString(16).padStart(2,'0') + '  [' + ledStr + ']  buttons=0x' + buttons.toString(16).padStart(2,'0'));

    position += direction;
    if (position === 6) direction = -1;
    if (position === 0) direction =  1;
}, 100);