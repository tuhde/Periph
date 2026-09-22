'use strict';

const opengpio = require('opengpio');
const { SiPoConnection } = require('../../../src/connection/sipo');
const { Tpic6b595Minimal } = require('../../../src/chips/io_expander/tpic6b595');

async function main() {
    const rck = new opengpio.Output(17);                                                    // Open RCK GPIO, (line=17)
    const connection = new SiPoConnection(rck);                                            // Create SiPo connection, (rck, srclr=null, g=null)
    const chip = new Tpic6b595Minimal(connection, 1);                                       // Create TPIC6B595 driver, (connection, numDevices=1)
                                                                                            // initialises every output to OFF (shadow zeroed, latched once)

    const p0 = chip.pin(0);                                                                 // Get pin proxy, (n=0) → Pin
    const p7 = chip.pin(7);                                                                 // Get pin proxy, (n=7) → Pin

    while (true) {
        await p0.on();                                                                       // Set DMOS output ON, () → Promise<void>
        await p7.off();                                                                      // Set DMOS output OFF, () → Promise<void>
        await new Promise(r => setTimeout(r, 500));
        await p0.off();                                                                      // Set DMOS output OFF, () → Promise<void>
        await p7.on();                                                                       // Set DMOS output ON, () → Promise<void>
        await new Promise(r => setTimeout(r, 500));
    }
}

main().catch(err => { console.error(err); process.exit(1); });
