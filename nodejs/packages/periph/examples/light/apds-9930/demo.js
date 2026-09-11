// Demo for the APDS-9930 — adaptive backlight + screen-lock scenario.

const { APDS9930Full } = require('periph/src/chips/light/apds-9930');
const { I2CConnection } = require('periph/src/connection/i2c_auto');

const DIM_LUX_THRESHOLD = 10.0;
const PROX_SCREEN_OFF = 400;

async function main() {
    // --- Configure for adaptive backlight + screen-lock monitoring ---
    // Defaults (ATIME=0xDB, 1x AGAIN, 8-pulse proximity, 100 mA drive)
    // already give stable readings; we just print lux and proximity.
    const connection = new I2CConnection(0x39);                       // Create I2C connection, (addr=0x39, bus=undefined) → I2CConnection
    const apds = new APDS9930Full(connection);                        // Construct APDS-9930 Full, (connection) → APDS9930Full
                                                                     // default 101 ms ALS integration, 8-pulse proximity, 100 mA drive

    await new Promise(r => setTimeout(r, 110));
    // --- Sample lux and proximity once per second for 30 cycles ---
    for (let i = 0; i < 30; i++) {
        await new Promise(r => setTimeout(r, 1000));
        const lx = await apds.lux();                                 // Read ambient illuminance, () → Promise<number> lx
                                                                     // IR-compensated lux via Ch0/Ch1 difference
        const p  = await apds.proximity();                            // Read proximity count, () → Promise<number> count
                                                                     // 16-bit ADC value; higher = closer
        console.log(`lux=${lx.toFixed(1)} lx  proximity=${p}`);
        if (lx < DIM_LUX_THRESHOLD) console.log('  -> dim backlight');
        if (p > PROX_SCREEN_OFF)     console.log('  -> disable screen');
    }
}

main().catch(err => { console.error(err); process.exit(1); });