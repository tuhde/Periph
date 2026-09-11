// Complete example for the APDS-9930 — exercises every Full-class method.

const { APDS9930Full } = require('periph/src/chips/light/apds-9930');
const { I2CConnection } = require('periph/src/connection/i2c_auto');

async function main() {
    const connection = new I2CConnection(0x39);                       // Create I2C connection, (addr=0x39, bus=undefined) → I2CConnection
    const apds = new APDS9930Full(connection);                        // Construct APDS-9930 Full, (connection) → APDS9930Full
                                                                     // exposes ALS and proximity configuration methods

    await new Promise(r => setTimeout(r, 110));

    await apds.configureAls(0xDB, 0, false);                         // Configure ALS, (atime=0xDB, again=0, agl=false) → Promise<void>
                                                                     // sets ALS integration time to 101 ms with 1x gain
    await apds.configureProximity(8, 0, 0, false, 0xFF);             // Configure proximity, (ppulse=8, pgain=0, pdrive=0, pdl=false, ptime=0xFF) → Promise<void>
                                                                     // 8 LED pulses at 100 mA, 1x gain, no reduced drive
    await apds.disableWait();                                         // Disable wait timer, () → Promise<void>
                                                                     // clears WEN in ENABLE
    await apds.setAlsThresholds(100, 60000, 1);                       // Set ALS thresholds, (low=100, high=60000, persistence=1) → Promise<void>
                                                                     // fires after 1 consecutive out-of-range Ch0 count
    await apds.setProximityThresholds(10, 200, 1);                   // Set proximity thresholds, (low=10, high=200, persistence=1) → Promise<void>
                                                                     // fires on a single proximity reading outside [10, 200]
    await apds.setProximityOffset(0);                                // Set proximity offset, (offset=0) → Promise<void>
                                                                     // clears any prior offset
    await apds.sleepAfterInterrupt(false);                           // Configure SAI, (enable=false) → Promise<void>
                                                                     // chip stays in normal operation after an interrupt

    for (let i = 0; i < 10; i++) {
        await new Promise(r => setTimeout(r, 110));
        const lx = await apds.lux();                                 // Read ambient illuminance, () → Promise<number> lx
                                                                     // combines Ch0 and Ch1 with IR-compensation coefficients
        const p  = await apds.proximity();                            // Read proximity count, () → Promise<number> count
                                                                     // 16-bit ADC value
        const c0 = await apds.ch0();                                  // Read Ch0 raw, () → Promise<number> count
                                                                     // 16-bit ADC value of visible + IR channel
        const c1 = await apds.ch1();                                  // Read Ch1 raw, () → Promise<number> count
                                                                     // 16-bit ADC value of IR-only channel
        const st = await apds.status();                               // Read STATUS decoded, () → Promise<object>
                                                                     // {avalid, pvalid, psat, aint, pint} booleans
        console.log(`lux=${lx.toFixed(1)}  prox=${p}  ch0=${c0}  ch1=${c1}  status=${JSON.stringify(st)}`);
    }
    await apds.clearInterrupt('both');                                // Clear interrupts, (channel='both') → Promise<void>
                                                                     // issues special-function command 0xE7 to clear both ALS and proximity INT flags
}

main().catch(err => { console.error(err); process.exit(1); });