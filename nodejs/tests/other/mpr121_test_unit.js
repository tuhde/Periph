// Unit test for the MPR121 driver — uses the I2CConnectionMock to exercise
// register-level logic without hardware.

const { MPR121Minimal, MPR121Full } = require('periph/src/chips/other/mpr121');
const { I2CConnectionMock }         = require('periph/src/connection/i2c_mock');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS ' + label); passed++; }
    else           { console.log('FAIL ' + label); failed++; }
}

function findWrite(writes, reg, value) {
    return writes.some(w => w.length === 2 && w[0] === reg && w[1] === value);
}

async function main() {
    let mock = new I2CConnectionMock();
    let mpr = new MPR121Minimal(mock);
    await new Promise(r => setTimeout(r, 10));
    checkTrue('Minimal init issues soft reset', findWrite(mock.writes, 0x80, 0x63));
    checkTrue('Minimal init writes ECR=0x8C',   findWrite(mock.writes, 0x5E, 0x8C));
    checkTrue('Minimal init writes ELE0_TTH=12', findWrite(mock.writes, 0x41, 12));
    checkTrue('Minimal init writes ELE0_RTH=6',  findWrite(mock.writes, 0x42, 6));

    mock = new I2CConnectionMock();
    mock.setRegister(0x00, [0x5A, 0x05]);
    mpr = new MPR121Minimal(mock);
    await new Promise(r => setTimeout(r, 10));
    const t = await mpr.touched();
    checkTrue('touched returns 0x55A', t === 0x5A);

    mock = new I2CConnectionMock();
    mock.setRegister(0x00, [0x28, 0x08]);
    mpr = new MPR121Minimal(mock);
    await new Promise(r => setTimeout(r, 10));
    checkTrue('is_touched(5) True',  (await mpr.isTouched(5))  === true);
    checkTrue('is_touched(11) True', (await mpr.isTouched(11)) === true);
    checkTrue('is_touched(0) False', (await mpr.isTouched(0))  === false);

    mock = new I2CConnectionMock();
    mock.setRegister(0x04, [0x80, 0x02]);
    mpr = new MPR121Full(mock);
    await new Promise(r => setTimeout(r, 10));
    checkTrue('filtered(0) = 0x280', (await mpr.filtered(0)) === 0x280);

    mock = new I2CConnectionMock();
    mock.setRegister(0x1E, [0x80]);
    mpr = new MPR121Full(mock);
    await new Promise(r => setTimeout(r, 10));
    checkTrue('baseline(0) = 0x200', (await mpr.baseline(0)) === 0x200);

    mock = new I2CConnectionMock();
    mpr = new MPR121Full(mock);
    await new Promise(r => setTimeout(r, 10));
    await mpr.setBaseline(0, 0x300);
    checkTrue('set_baseline(0, 0x300) writes 0xC0 to 0x1E',
              findWrite(mock.writes, 0x1E, 0xC0));

    mock = new I2CConnectionMock();
    mock.setRegister(0x01, [0x10]);
    mpr = new MPR121Full(mock);
    await new Promise(r => setTimeout(r, 10));
    checkTrue('proximity_touched True at 0x01=0x10', (await mpr.proximityTouched()) === true);
    mock.setRegister(0x01, [0x00]);
    checkTrue('proximity_touched False at 0x01=0x00', (await mpr.proximityTouched()) === false);

    mock = new I2CConnectionMock();
    mock.setRegister(0x01, [0x80]);
    mpr = new MPR121Full(mock);
    await new Promise(r => setTimeout(r, 10));
    await mpr.clearOvercurrent();
    checkTrue('clear_overcurrent writes 0x01 with bit 7 cleared',
              mock.writes.some(w => w.length === 2 && w[0] === 0x01 && (w[1] & 0x80) === 0));

    mock = new I2CConnectionMock();
    mpr = new MPR121Full(mock);
    await new Promise(r => setTimeout(r, 10));
    await mpr.configureSampling(10, 2, 1, 2, 5);
    checkTrue('configure_sampling writes CDC_CONFIG=0x4A',
              findWrite(mock.writes, 0x5C, 0x4A));
    checkTrue('configure_sampling writes CDT_CONFIG=0x4D',
              findWrite(mock.writes, 0x5D, 0x4D));

    mock = new I2CConnectionMock();
    mpr = new MPR121Full(mock);
    await new Promise(r => setTimeout(r, 10));
    await mpr.configureDebounce(3, 5);
    checkTrue('configure_debounce writes 0x5B=0x53', findWrite(mock.writes, 0x5B, 0x53));

    mock = new I2CConnectionMock();
    mock.setRegister(0x7C, [0x00]);
    mpr = new MPR121Full(mock);
    await new Promise(r => setTimeout(r, 10));
    await mpr.enableInterrupt(MPR121Full.SOURCE_OOR);
    checkTrue('enable_interrupt(SOURCE_OOR) writes 0x7C=0x04',
              findWrite(mock.writes, 0x7C, 0x04));
    mock.setRegister(0x7C, [0x04]);
    await mpr.disableInterrupt(MPR121Full.SOURCE_OOR);
    checkTrue('disable_interrupt(SOURCE_OOR) writes 0x7C=0x00',
              findWrite(mock.writes, 0x7C, 0x00));

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(err => { console.error(err); process.exit(1); });
