// Minimal example for the MPR121 capacitive touch controller.

const { MPR121Minimal } = require('periph/src/chips/other/mpr121');
const { I2CConnection }  = require('periph/src/connection/i2c_auto');

async function main() {
    const connection = new I2CConnection(0x5A);                  // Create I2C connection, (addr=0x5A, bus=undefined) → I2CConnection
    const mpr = new MPR121Minimal(connection);                  // Construct MPR121 Minimal, (connection) → MPR121Minimal
                                                                // resets, applies default thresholds (T=12, R=6), enters Run Mode on all 12 electrodes
    setInterval(async () => {
        const t = await mpr.touched();                          // Read 12-bit touch bitmask, () → Promise<number> bitmask
                                                                // bit n=1 means ELEn is currently touched
        console.log(`touched=0x${t.toString(16).padStart(3, '0')}`);
    }, 1000);
}

main().catch(err => { console.error(err); process.exit(1); });
