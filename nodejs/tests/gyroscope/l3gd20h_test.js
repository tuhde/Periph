const { I2CConnection } = require('../../packages/periph/src/connection/i2c_linux');
const { L3GD20HMinimal, L3GD20HFull } = require('../../packages/periph/src/chips/gyroscope/l3gd20h');

async function test() {
    console.log('=== L3GD20H Node.js Hardware Test ===');
    let passed = 0, failed = 0;

    function check(label, cond) {
        if (cond) { console.log('PASS', label); passed++; }
        else { console.log('FAIL', label); failed++; }
    }

    const conn = new I2CConnection(1, 0x6A);
    try {
        const gyro = new L3GD20HMinimal(conn);
        await new Promise(r => setTimeout(r, 300));
        check('Minimal init', true);

        const [x, y, z] = await gyro.gyro();
        check('gyro() returns array of 3 floats', Array.isArray(x) ? false : typeof x === 'number' && typeof y === 'number' && typeof z === 'number');
        console.log(`  Initial reading: x=${x.toFixed(3)} y=${y.toFixed(3)} z=${z.toFixed(3)} rad/s`);

        const gyroFull = new L3GD20HFull(conn);
        check('Full init', true);

        await gyroFull.configure(1, 0, 1);
        check('configure()', true);

        await gyroFull.enableHpFilter(true);
        check('enableHpFilter(true)', true);

        await gyroFull.configureFifo(1, 10);
        await gyroFull.enableFifo(true);
        check('configureFifo() + enableFifo()', true);

        const [x2, y2, z2] = await gyroFull.gyro();
        check('gyro() after config', typeof x2 === 'number' && typeof y2 === 'number' && typeof z2 === 'number');

        const [rx, ry, rz] = await gyroFull.gyroRaw();
        check('gyroRaw() returns array of 3 ints', Number.isInteger(rx) && Number.isInteger(ry) && Number.isInteger(rz));

        const temp = await gyroFull.temperature();
        check('temperature() returns int', Number.isInteger(temp));

        const drdy = await gyroFull.dataReady();
        check('dataReady() returns bool', typeof drdy === 'boolean');

        const level = await gyroFull.fifoLevel();
        check('fifoLevel() returns int >= 0', Number.isInteger(level) && level >= 0);

        const samples = await gyroFull.readFifo();
        check('readFifo() returns array', Array.isArray(samples));

        await gyroFull.setPowerMode(L3GD20HFull.POWER_SLEEP);
        check('setPowerMode(SLEEP)', true);

        await gyroFull.setPowerMode(L3GD20HFull.POWER_NORMAL);
        check('setPowerMode(NORMAL)', true);

    } catch (e) {
        console.log('FAIL: Hardware test error:', e.message);
        failed++;
    }

    console.log(`\n=== DONE: ${passed} passed, ${failed} failed ===`);
    process.exit(failed === 0 ? 0 : 1);
}

test();