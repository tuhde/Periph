// Demo for the MPR121 — 12-button musical keyboard with IRQ-driven detection.

const { MPR121Full }    = require('periph/src/chips/other/mpr121');
const { I2CConnection } = require('periph/src/connection/i2c_auto');

const NOTES = ['C4', 'C#4', 'D4', 'D#4', 'E4', 'F4', 'F#4', 'G4', 'G#4', 'A4', 'A#4', 'B4'];

async function main() {
    const connection = new I2CConnection(0x5A);                  // Create I2C connection, (addr=0x5A, bus=undefined) → I2CConnection
    const mpr = new MPR121Full(connection);                     // Construct MPR121 Full, (connection) → MPR121Full
                                                                // defaults, all 12 electrodes enabled

    let previous = 0;
    setInterval(async () => {
        const mask = await mpr.touched();                        // Read 12-bit touch bitmask, () → Promise<number> bitmask
        const newlyPressed = mask & ~previous;
        const newlyReleased = (~mask) & previous;
        for (let n = 0; n < 12; n++) {
            if (newlyPressed & (1 << n)) {
                console.log(`NOTE ON:  ${NOTES[n]}`);
            }
            if (newlyReleased & (1 << n)) {
                console.log(`NOTE OFF: ${NOTES[n]}`);
            }
        }
        previous = mask;
    }, 50);
}

main().catch(err => { console.error(err); process.exit(1); });
