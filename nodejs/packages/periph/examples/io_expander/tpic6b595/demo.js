// TPIC6B595 demo — "knight rider" chase pattern across two cascaded devices.
//
// Hardware:
//   Two cascaded TPIC6B595s driving 16 LEDs as an automotive-cluster-style
//   indicator bank (DRAIN0..DRAIN7 on each device). Each LED's anode goes to
//   the supply through a series resistor and its cathode to a DRAIN pin;
//   writing 1 turns the LED on (active-low via the DMOS sink).
//
// The demo walks a single lit LED back and forth across all 16 outputs and,
// every few sweeps, blanks every output for half a second via
// setOutputEnable(false) to demonstrate glitch-free global blanking. The
// shadow register is untouched across the blank, so the chase pattern resumes
// exactly where it left off.
'use strict';

const opengpio = require('opengpio');
const { SiPoConnection } = require('../../../src/connection/sipo');
const { Tpic6b595Full } = require('../../../src/chips/io_expander/tpic6b595');

const NUM_DEVICES = 2;
const NUM_OUTPUTS = NUM_DEVICES * 8;

async function main() {
    const rck   = new opengpio.Output(17);                                                   // Open RCK GPIO, (line=17)
    const srclr = new opengpio.Output(16);                                                   // Open SRCLR GPIO, (line=16)
    const g     = new opengpio.Output(15);                                                   // Open G GPIO, (line=15)
    const connection = new SiPoConnection(rck, { srclr, g });                                // Create SiPo connection, (rck, srclr, g)
    const chip = new Tpic6b595Full(connection, NUM_DEVICES);                                 // Create TPIC6B595 full driver, (connection, numDevices=2)
                                                                                              // two cascaded devices — 16 outputs total; outputs start OFF

    let position = 0;
    let direction = 1;
    let sweepCount = 0;
    const BLANK_EVERY = 3;
    const BLANK_MS = 500;

    while (true) {
        // --- Walk a single lit LED across all 16 outputs and back ---
        // Use writeAll() each step so both cascaded devices latch together —
        // there is no way to update just one downstream device without re-sending
        // the whole chain's data.
        const bytes = new Uint8Array(NUM_DEVICES);
        const port = Math.floor(position / 8);
        const bit  = position % 8;
        bytes[port] = 1 << bit;
        chip.writeAll(bytes);                                                                 // Write all device bytes, (values=[0x01, 0x80]) → void

        console.log(`position=${position}  device=${port}  bit=${bit}  bytes=[0x${bytes[0].toString(16).padStart(2, '0')}, 0x${bytes[1].toString(16).padStart(2, '0')}]`);

        // --- Periodically blank every output via G, then resume ---
        // setOutputEnable(false) drives G HIGH, forcing every DMOS off without
        // touching the shadow register — the LEDs simply resume exactly where they
        // left off when G is re-enabled.
        sweepCount++;
        if (sweepCount % BLANK_EVERY === 0) {
            chip.setOutputEnable(false);                                                     // Force every output off via G, (enabled=false) → void
                                                                                              // the chase pattern's shadow state is preserved
            console.log(`  blanked via G for ${BLANK_MS} ms`);
            await new Promise(r => setTimeout(r, BLANK_MS));
            chip.setOutputEnable(true);                                                      // Re-enable outputs, (enabled=true) → void
                                                                                              // LEDs resume from the previously-latched state
        }

        // Bounce the chase position at both ends of the strip
        position += direction;
        if (position >= NUM_OUTPUTS - 1 || position <= 0) {
            direction = -direction;
            await new Promise(r => setTimeout(r, 100));
        } else {
            await new Promise(r => setTimeout(r, 80));
        }
    }
}

main().catch(err => { console.error(err); process.exit(1); });
