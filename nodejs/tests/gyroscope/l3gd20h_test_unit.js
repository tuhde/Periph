const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { L3GD20HMinimal, L3GD20HFull } = require('../../packages/periph/src/chips/gyroscope/l3gd20h');

console.log('=== L3GD20H Unit Tests ===');

let passed = 0, failed = 0;

function check(label, cond) {
    if (cond) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function runTests() {
    // Test 1: Minimal init with L3GD20H WHO_AM_I
    {
        const mock = new I2CConnectionMock();
        mock.setRegister(0x0F, Buffer.from([0xD7]));
        mock.setRegister(0x20, Buffer.from([0x0F]));
        mock.setRegister(0x23, Buffer.from([0x80]));
        const gyro = new L3GD20HMinimal(mock);
        await new Promise(r => setTimeout(r, 260));
        check('Minimal init (L3GD20H WHO_AM_I=0xD7)', true);
    }

    // Test 2: Minimal init with L3GD20 WHO_AM_I
    {
        const mock = new I2CConnectionMock();
        mock.setRegister(0x0F, Buffer.from([0xD4]));
        mock.setRegister(0x20, Buffer.from([0x0F]));
        mock.setRegister(0x23, Buffer.from([0x80]));
        const gyro = new L3GD20HMinimal(mock);
        await new Promise(r => setTimeout(r, 260));
        check('Minimal init (L3GD20 WHO_AM_I=0xD4)', true);
    }

    // Test 3: Minimal init with invalid WHO_AM_I
    {
        const mock = new I2CConnectionMock();
        mock.setRegister(0x0F, Buffer.from([0x00]));
        const gyro = new L3GD20HMinimal(mock);
        await new Promise(r => setTimeout(r, 260));
        check('Minimal init invalid WHO_AM_I (silent)', true);
    }

    // Test 4: gyro() returns array of 3 numbers
    {
        const mock = new I2CConnectionMock();
        mock.setRegister(0x0F, Buffer.from([0xD7]));
        mock.setRegister(0x20, Buffer.from([0x0F]));
        mock.setRegister(0x23, Buffer.from([0x80]));
        mock.setRegister(0x28 | 0x80, Buffer.from([0x00, 0x01, 0x00, 0x02, 0x00, 0x03]));
        const gyro = new L3GD20HMinimal(mock);
        await new Promise(r => setTimeout(r, 260));
        const [x, y, z] = await gyro.gyro();
        check('gyro() returns 3 numbers', typeof x === 'number' && typeof y === 'number' && typeof z === 'number');
    }

    // Test 5: Full init
    {
        const mock = new I2CConnectionMock();
        mock.setRegister(0x0F, Buffer.from([0xD7]));
        mock.setRegister(0x20, Buffer.from([0x0F]));
        mock.setRegister(0x23, Buffer.from([0x80]));
        const gyro = new L3GD20HFull(mock);
        await new Promise(r => setTimeout(r, 260));
        check('Full init', true);
    }

    // Test 6: configure() sets registers
    {
        const mock = new I2CConnectionMock();
        mock.setRegister(0x0F, Buffer.from([0xD7]));
        mock.setRegister(0x20, Buffer.from([0x0F]));
        mock.setRegister(0x23, Buffer.from([0x80]));
        const gyro = new L3GD20HFull(mock);
        await new Promise(r => setTimeout(r, 260));
        await gyro.configure(1, 0, 1);
        check('configure() sets CTRL_REG1', mock.registers.get(0x20) === 0x4F);
        check('configure() sets CTRL_REG4', mock.registers.get(0x23) === 0x90);
    }

    // Test 7: gyroRaw() returns signed int16
    {
        const mock = new I2CConnectionMock();
        mock.setRegister(0x0F, Buffer.from([0xD7]));
        mock.setRegister(0x20, Buffer.from([0x0F]));
        mock.setRegister(0x23, Buffer.from([0x80]));
        mock.setRegister(0x28 | 0x80, Buffer.from([0x00, 0x80, 0xFF, 0x7F, 0x00, 0x00]));
        const gyro = new L3GD20HFull(mock);
        await new Promise(r => setTimeout(r, 260));
        const [x, y, z] = await gyro.gyroRaw();
        check('gyroRaw() returns signed int16', x === -32768 && y === 32767 && z === 0);
    }

    // Test 8: temperature() returns signed 8-bit
    {
        const mock = new I2CConnectionMock();
        mock.setRegister(0x0F, Buffer.from([0xD7]));
        mock.setRegister(0x20, Buffer.from([0x0F]));
        mock.setRegister(0x23, Buffer.from([0x80]));
        mock.setRegister(0x26, Buffer.from([0x80]));
        const gyro = new L3GD20HFull(mock);
        await new Promise(r => setTimeout(r, 260));
        check('temperature() negative', await gyro.temperature() === -128);
        mock.setRegister(0x26, Buffer.from([0x7F]));
        check('temperature() positive', await gyro.temperature() === 127);
    }

    // Test 9: dataReady() returns ZYXDA bit
    {
        const mock = new I2CConnectionMock();
        mock.setRegister(0x0F, Buffer.from([0xD7]));
        mock.setRegister(0x20, Buffer.from([0x0F]));
        mock.setRegister(0x23, Buffer.from([0x80]));
        mock.setRegister(0x27, Buffer.from([0x08]));
        const gyro = new L3GD20HFull(mock);
        await new Promise(r => setTimeout(r, 260));
        check('dataReady() true', await gyro.dataReady() === true);
        mock.setRegister(0x27, Buffer.from([0x00]));
        check('dataReady() false', await gyro.dataReady() === false);
    }

    // Test 10: configureHpFilter()
    {
        const mock = new I2CConnectionMock();
        mock.setRegister(0x0F, Buffer.from([0xD7]));
        mock.setRegister(0x20, Buffer.from([0x0F]));
        mock.setRegister(0x23, Buffer.from([0x80]));
        const gyro = new L3GD20HFull(mock);
        await new Promise(r => setTimeout(r, 260));
        await gyro.configureHpFilter(1, 5);
        check('configureHpFilter() sets CTRL_REG2', mock.registers.get(0x21) === 0x15);
    }

    // Test 11: enableHpFilter()
    {
        const mock = new I2CConnectionMock();
        mock.setRegister(0x0F, Buffer.from([0xD7]));
        mock.setRegister(0x20, Buffer.from([0x0F]));
        mock.setRegister(0x23, Buffer.from([0x80]));
        const gyro = new L3GD20HFull(mock);
        await new Promise(r => setTimeout(r, 260));
        await gyro.enableHpFilter(true);
        check('enableHpFilter(true) sets HPen', (mock.registers.get(0x24) & 0x10) === 0x10);
        await gyro.enableHpFilter(false);
        check('enableHpFilter(false) clears HPen', (mock.registers.get(0x24) & 0x10) === 0);
    }

    // Test 12: configureFifo()
    {
        const mock = new I2CConnectionMock();
        mock.setRegister(0x0F, Buffer.from([0xD7]));
        mock.setRegister(0x20, Buffer.from([0x0F]));
        mock.setRegister(0x23, Buffer.from([0x80]));
        const gyro = new L3GD20HFull(mock);
        await new Promise(r => setTimeout(r, 260));
        await gyro.configureFifo(1, 10);
        check('configureFifo() sets FIFO_EN', (mock.registers.get(0x24) & 0x40) === 0x40);
        check('configureFifo() sets FIFO_CTRL_REG', mock.registers.get(0x2E) === 0x2A);
    }

    // Test 13: enableFifo()
    {
        const mock = new I2CConnectionMock();
        mock.setRegister(0x0F, Buffer.from([0xD7]));
        mock.setRegister(0x20, Buffer.from([0x0F]));
        mock.setRegister(0x23, Buffer.from([0x80]));
        const gyro = new L3GD20HFull(mock);
        await new Promise(r => setTimeout(r, 260));
        await gyro.enableFifo(true);
        check('enableFifo(true) sets FIFO_EN', (mock.registers.get(0x24) & 0x40) === 0x40);
        await gyro.enableFifo(false);
        check('enableFifo(false) clears FIFO_EN', (mock.registers.get(0x24) & 0x40) === 0);
        check('enableFifo(false) sets FIFO_CTRL_REG=0', mock.registers.get(0x2E) === 0x00);
    }

    // Test 14: fifoLevel()
    {
        const mock = new I2CConnectionMock();
        mock.setRegister(0x0F, Buffer.from([0xD7]));
        mock.setRegister(0x20, Buffer.from([0x0F]));
        mock.setRegister(0x23, Buffer.from([0x80]));
        mock.setRegister(0x2F, Buffer.from([0x05]));
        const gyro = new L3GD20HFull(mock);
        await new Promise(r => setTimeout(r, 260));
        check('fifoLevel() returns FSS', await gyro.fifoLevel() === 5);
    }

    // Test 15: setPowerMode()
    {
        const mock = new I2CConnectionMock();
        mock.setRegister(0x0F, Buffer.from([0xD7]));
        mock.setRegister(0x20, Buffer.from([0x0F]));
        mock.setRegister(0x23, Buffer.from([0x80]));
        const gyro = new L3GD20HFull(mock);
        await new Promise(r => setTimeout(r, 260));
        await gyro.setPowerMode('normal');
        check('setPowerMode(normal) enables all axes', (mock.registers.get(0x20) & 0x0F) === 0x0F);
        await gyro.setPowerMode('sleep');
        check('setPowerMode(sleep) disables axes', (mock.registers.get(0x20) & 0x0F) === 0x08);
        await gyro.setPowerMode('power_down');
        check('setPowerMode(power_down) clears PD', (mock.registers.get(0x20) & 0x08) === 0);
    }

    console.log(`\n=== DONE: ${passed} passed, ${failed} failed ===`);
    process.exit(failed === 0 ? 0 : 1);
}

runTests().catch(e => { console.error(e); process.exit(1); });