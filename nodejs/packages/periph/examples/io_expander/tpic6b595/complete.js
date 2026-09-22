'use strict';

const opengpio = require('opengpio');
const { SiPoConnection } = require('../../../src/connection/sipo');
const { Tpic6b595Minimal, Tpic6b595Full } = require('../../../src/chips/io_expander/tpic6b595');

async function main() {
    const rck   = new opengpio.Output(17);                                                   // Open RCK GPIO, (line=17)
    const srclr = new opengpio.Output(16);                                                   // Open SRCLR GPIO, (line=16)
    const g     = new opengpio.Output(15);                                                   // Open G GPIO, (line=15)
    const connection = new SiPoConnection(rck, { srclr, g });                                // Create SiPo connection, (rck, srclr, g)
    const chip = new Tpic6b595Full(connection, 2);                                            // Create TPIC6B595 full driver, (connection, numDevices=2)
                                                                                              // two cascaded devices — 16 outputs total (DRAIN0..DRAIN15)

    // --- Pin-level control ---
    const p0 = chip.pin(0);                                                                  // Get pin proxy for DRAIN0 of device 0, (n=0) → Pin
    await p0.on();                                                                           // Set DRAIN0 ON, () → Promise<void>
                                                                                              // sets shadow[0] bit 0, reverses the cascade, shifts out and pulses RCK
    await p0.off();                                                                          // Set DRAIN0 OFF, () → Promise<void>
                                                                                              // clears shadow[0] bit 0, retransmits and latches
    await p0.toggle();                                                                       // Invert shadow bit, () → Promise<void>

    const state = await p0.read();                                                           // Read pin state, () → Promise<number>
                                                                                              // returns the shadow bit (no bus read — SiPo is write-only)
    await p0.write(1);                                                                       // Write pin high, (value=1) → Promise<void>
                                                                                              // equivalent to on(); updates shadow, retransmits, latches
    await p0.set(true);                                                                      // OutputPin set, (high=true) → Promise<void>
                                                                                              // same path as on()/write(1), but matches the OutputPin contract

    // --- Port-level bulk write ---
    await chip.writePort(0, 0xAA);                                                           // Write device 0 outputs, (port=0, mask=0xAA) → Promise<void>
                                                                                              // sets DRAIN{1,3,5,7} ON, DRAIN{0,2,4,6} OFF; preserves device 1
    await chip.writePort(1, 0x55);                                                           // Write device 1 outputs, (port=1, mask=0x55) → Promise<void>

    // --- Bulk fill / off ---
    await chip.fill(true);                                                                   // Set every output ON, (value=true) → Promise<void>
                                                                                              // fills every shadow byte with 0xFF and retransmits — fast "all on" path
    await chip.fill(false);                                                                  // Set every output OFF, (value=false) → Promise<void>
                                                                                              // fills every shadow byte with 0x00 and retransmits — fast "all off" path
    await chip.off();                                                                        // Turn every output off, () → Promise<void>
                                                                                              // shorthand for fill(false); the safe initial state

    // --- Multi-device bulk write ---
    await chip.writeAll([0x01, 0x80]);                                                        // Write all device bytes, (values=[0x01, 0x80]) → void
                                                                                              // updates both shadow bytes and performs one transmit + latch

    // --- Hardware features (Full only) ---
    chip.clear();                                                                            // Pulse SRCLR, () → void
                                                                                              // clears the shift register only; outputs unaffected until next RCK pulse
    chip.setOutputEnable(false);                                                             // Force every output off via G, (enabled=false) → void
                                                                                              // drives G HIGH, blanking outputs without disturbing the shadow register
    await new Promise(r => setTimeout(r, 100));
    chip.setOutputEnable(true);                                                              // Re-enable outputs, (enabled=true) → void
                                                                                              // drives G LOW; outputs resume from the previously-latched state
}

main().catch(err => { console.error(err); process.exit(1); });
