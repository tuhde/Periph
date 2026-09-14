// Complete example for the MPR121 — exercises every Full-class method.

const { MPR121Full }    = require('periph/src/chips/other/mpr121');
const { I2CConnection } = require('periph/src/connection/i2c_auto');

async function main() {
    const connection = new I2CConnection(0x5A);                  // Create I2C connection, (addr=0x5A, bus=undefined) → I2CConnection
    const mpr = new MPR121Full(connection);                     // Construct MPR121 Full, (connection) → MPR121Full
                                                                // runs Minimal init then exposes Full configuration methods

    await mpr.stop();                                            // Enter Stop Mode, () → Promise<void>
                                                                // required before writing most config registers
    await mpr.configureThresholds(0, 15, 8);                     // Set thresholds, (electrode=0, touch=15, release=8) → Promise<void>
                                                                // ELE0: touch at 15 LSBs below baseline, release at 8 LSBs
    await mpr.configureAllThresholds(12, 6);                     // Apply thresholds to all, (touch=12, release=6) → Promise<void>
                                                                // ELE1..ELE11: same touch/release values
    await mpr.configureProximityThresholds(8, 4);                // Set ELEPROX thresholds, (touch=8, release=4) → Promise<void>
                                                                // ELEPROX touch at 8 LSBs, release at 4
    await mpr.configureBaselineFilter(1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0); // Set baseline filter, (mhdr, nhdr, nclr, fdlr, mhdf, nhdf, nclf, fdlf, nhdt, nclt, fdlt) → Promise<void>
                                                                // baseline filter defaults; tracks capacitance drift only
    await mpr.configureSampling(16, 1, 0, 0, 4);                 // Set AFE config, (cdc=16, cdt=1, ffi=0, sfi=0, esi=4) → Promise<void>
                                                                // 16 µA global CDC, 0.5 µs charge time, 16 ms sample interval
    await mpr.configureDebounce(1, 1);                           // Set debounce, (touch=1, release=1) → Promise<void>
                                                                // one consecutive measurement to detect touch/release
    await mpr.configureAutoconfig(3300, 0, false, true, true);  // Configure autoconfig, (vdd_mv=3300, retry=0, scts=false, are=true, ace=true) → Promise<void>
                                                                // recompute USL/TL/LSL for 3.3 V supply
    await mpr.start(12, 2, 0);                                   // Enter Run Mode, (n_electrodes=12, cl=2, eleprox_en=0) → Promise<void>
                                                                // all 12 electrodes, baseline init from first measurement, proximity off

    const f0 = await mpr.filtered(0);                            // Read ELE0 filtered, (electrode=0) → Promise<number> 0..1023
                                                                // raw 10-bit value, inversely proportional to capacitance
    const b0 = await mpr.baseline(0);                            // Read ELE0 baseline, (electrode=0) → Promise<number> 0..1023
                                                                // 8 MSBs from baseline register, shifted left 2
    const oor = await mpr.oorStatus();                           // Read OOR bitmask, () → Promise<number> bitmask
                                                                // bits 0..11 = ELE0..11 out-of-range
    const pt = await mpr.proximityTouched();                     // Read proximity touched, () → Promise<boolean>
                                                                // False: ELEPROX not enabled in this example
    console.log(`ELE0 filtered=${f0} baseline=${b0} OOR=0x${oor.toString(16).padStart(4, '0')} prox_touched=${pt}`);

    await mpr.enableInterrupt(MPR121Full.SOURCE_OOR);            // Enable interrupt source, (source=SOURCE_OOR) → Promise<void>
                                                                // OOR will now assert INT as well as touch/release
    await mpr.disableInterrupt(MPR121Full.SOURCE_OOR);           // Disable interrupt source, (source=SOURCE_OOR) → Promise<void>
                                                                // OOR no longer asserts INT
    await mpr.clearOvercurrent();                                // Clear OVCF, () → Promise<void>
                                                                // safe no-op when overcurrent has not occurred
    await mpr.reset();                                           // Soft reset, () → Promise<void>
                                                                // reapplies Minimal defaults; device ready for fresh use
}

main().catch(err => { console.error(err); process.exit(1); });
