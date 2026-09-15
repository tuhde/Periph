// HIL test for the MPR121 capacitive touch sensor controller.

const { MPR121Full } = require('periph/src/chips/other/mpr121');
const { I2CConnection }  = require('periph/src/connection/i2c_auto');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS ' + label); passed++; }
    else           { console.log('FAIL ' + label); failed++; }
}

async function main() {
    const connection = new I2CConnection(0x5A);
    const mpr = new MPR121Full(connection);

    const t = await mpr.touched();
    checkTrue('touched in 0..4095', t <= 0xFFF);
    checkTrue('is_touched is callable', typeof (await mpr.isTouched(0)) === 'boolean');

    const f0 = await mpr.filtered(0);
    checkTrue('filtered(0) in 0..1023', f0 <= 1023);

    const b0 = await mpr.baseline(0);
    checkTrue('baseline(0) in 0..1023', b0 <= 1023);

    const oor = await mpr.oorStatus();
    checkTrue('oor_status in 0..8191', oor <= 0x1FFF);

    await mpr.stop();
    await mpr.configureThresholds(0, 15, 8);
    await mpr.configureAllThresholds(12, 6);
    await mpr.configureProximityThresholds(8, 4);
    await mpr.configureBaselineFilter(1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0);
    await mpr.configureSampling(16, 1, 0, 0, 4);
    await mpr.configureDebounce(1, 1);
    await mpr.configureAutoconfig(3300, 0, false, true, true);
    checkTrue('configuration methods accepted', true);

    await mpr.enableInterrupt(MPR121Full.SOURCE_OOR);
    await mpr.disableInterrupt(MPR121Full.SOURCE_OOR);
    await mpr.clearOvercurrent();
    checkTrue('interrupt API accepted', true);

    await mpr.reset();
    checkTrue('reset completed', true);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(err => { console.error(err); process.exit(1); });
