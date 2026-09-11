'use strict';

const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { APDS9930Minimal, APDS9930Full } = require('../../packages/periph/src/chips/light/apds-9930');

let passed = 0;
let failed = 0;

function checkTrue(label, cond) {
    if (cond) { console.log('PASS', label); passed++; }
    else      { console.log('FAIL', label); failed++; }
}

// APDS-9930 uses a command-register protocol: every bus transaction
// starts with a command byte whose high bits are the type (0x80=write,
// 0xA0=auto-increment read, 0xE0=special) and whose low 5 bits are the
// register address. The mock is addressed by raw byte index, so this
// test preloads registers at the command-byte addresses the driver uses.
function CW(reg) { return 0x80 | (reg & 0x1F); }
function CR(reg) { return 0xA0 | (reg & 0x1F); }

async function testMinimalConstruction() {
    const mock = new I2CConnectionMock();
    mock.setRegister(CR(0x12), [0x39]);  // ID
    mock.setRegister(CR(0x0F), [0x00]);  // CONTROL
    mock.setRegister(CR(0x01), [0xDB]);  // ATIME
    mock.setRegister(CR(0x02), [0xFF]);  // PTIME
    mock.setRegister(CR(0x0E), [0x08]);  // PPULSE
    mock.setRegister(CR(0x0D), [0x00]);  // CONFIG
    mock.setRegister(CR(0x14), [0x00, 0x00]);
    mock.setRegister(CR(0x16), [0x00, 0x00]);
    mock.setRegister(CR(0x18), [0x00, 0x00]);
    new APDS9930Minimal(mock);
    await new Promise(r => setTimeout(r, 30));
    const wroteEnable = mock.writes.some(w => w.length === 2 && w[0] === CW(0x00) && w[1] === 0x07);
    const wrotePpulse = mock.writes.some(w => w.length === 2 && w[0] === CW(0x0E) && w[1] === 0x08);
    checkTrue('Minimal init enables PON|AEN|PEN', wroteEnable);
    checkTrue('Minimal init writes PPULSE=0x08', wrotePpulse);
}

async function testProximityReturns16Bit() {
    const mock = new I2CConnectionMock();
    mock.setRegister(CR(0x12), [0x39]);
    mock.setRegister(CR(0x0F), [0x00]);
    mock.setRegister(CR(0x01), [0xDB]);
    mock.setRegister(CR(0x02), [0xFF]);
    mock.setRegister(CR(0x0E), [0x08]);
    mock.setRegister(CR(0x0D), [0x00]);
    mock.setRegister(CR(0x14), [0x00, 0x00]);
    mock.setRegister(CR(0x16), [0x00, 0x00]);
    mock.setRegister(CR(0x18), [0x34, 0x12]);
    const apds = new APDS9930Minimal(mock);
    await new Promise(r => setTimeout(r, 30));
    const p = await apds.proximity();
    checkTrue('proximity is 0x1234', p === 0x1234);
}

async function testLuxZeroWhenDark() {
    const mock = new I2CConnectionMock();
    mock.setRegister(CR(0x12), [0x39]);
    mock.setRegister(CR(0x0F), [0x00]);
    mock.setRegister(CR(0x01), [0xDB]);
    mock.setRegister(CR(0x02), [0xFF]);
    mock.setRegister(CR(0x0E), [0x08]);
    mock.setRegister(CR(0x0D), [0x00]);
    mock.setRegister(CR(0x14), [0x00, 0x00]);
    mock.setRegister(CR(0x16), [0x00, 0x00]);
    mock.setRegister(CR(0x18), [0x00, 0x00]);
    const apds = new APDS9930Minimal(mock);
    await new Promise(r => setTimeout(r, 30));
    checkTrue('lux=0 when dark', (await apds.lux()) === 0);
}

async function testLuxPositiveWhenVisibleOnly() {
    const mock = new I2CConnectionMock();
    mock.setRegister(CR(0x12), [0x39]);
    mock.setRegister(CR(0x0F), [0x00]);
    mock.setRegister(CR(0x01), [0xDB]);
    mock.setRegister(CR(0x02), [0xFF]);
    mock.setRegister(CR(0x0E), [0x08]);
    mock.setRegister(CR(0x0D), [0x00]);
    mock.setRegister(CR(0x14), [0x00, 0x10]);
    mock.setRegister(CR(0x16), [0x00, 0x00]);
    mock.setRegister(CR(0x18), [0x00, 0x00]);
    const apds = new APDS9930Minimal(mock);
    await new Promise(r => setTimeout(r, 30));
    checkTrue('lux>0 with visible-only light', (await apds.lux()) > 0);
}

async function testFullConfigureAls() {
    const mock = new I2CConnectionMock();
    mock.setRegister(CR(0x12), [0x39]);
    mock.setRegister(CR(0x0F), [0x00]);
    mock.setRegister(CR(0x01), [0xDB]);
    mock.setRegister(CR(0x02), [0xFF]);
    mock.setRegister(CR(0x0E), [0x08]);
    mock.setRegister(CR(0x0D), [0x00]);
    mock.setRegister(CR(0x14), [0x00, 0x00]);
    mock.setRegister(CR(0x16), [0x00, 0x00]);
    mock.setRegister(CR(0x18), [0x00, 0x00]);
    const apds = new APDS9930Full(mock);
    await new Promise(r => setTimeout(r, 30));
    await apds.configureAls(0xF6, 2, false);
    const wroteAtime = mock.writes.some(w => w.length === 2 && w[0] === CW(0x01) && w[1] === 0xF6);
    const wroteAgain = mock.writes.some(w => w.length === 2 && w[0] === CW(0x0F) && (w[1] & 0x03) === 0x02);
    checkTrue('configureAls writes ATIME', wroteAtime);
    checkTrue('configureAls writes AGAIN', wroteAgain);
}

async function testFullStatusDecoded() {
    const mock = new I2CConnectionMock();
    mock.setRegister(CR(0x12), [0x39]);
    mock.setRegister(CR(0x0F), [0x00]);
    mock.setRegister(CR(0x01), [0xDB]);
    mock.setRegister(CR(0x02), [0xFF]);
    mock.setRegister(CR(0x0E), [0x08]);
    mock.setRegister(CR(0x0D), [0x00]);
    mock.setRegister(CR(0x13), [0x01]);  // STATUS: AVALID
    mock.setRegister(CR(0x14), [0x00, 0x00]);
    mock.setRegister(CR(0x16), [0x00, 0x00]);
    mock.setRegister(CR(0x18), [0x00, 0x00]);
    const apds = new APDS9930Full(mock);
    await new Promise(r => setTimeout(r, 30));
    const st = await apds.status();
    checkTrue('status has avalid bool', typeof st.avalid === 'boolean');
    checkTrue('status.avalid=true with STATUS=0x01', st.avalid === true);
    checkTrue('status.pvalid=false with STATUS=0x01', st.pvalid === false);
}

async function testClearInterruptWritesCommand() {
    const mock = new I2CConnectionMock();
    mock.setRegister(CR(0x12), [0x39]);
    mock.setRegister(CR(0x0F), [0x00]);
    mock.setRegister(CR(0x01), [0xDB]);
    mock.setRegister(CR(0x02), [0xFF]);
    mock.setRegister(CR(0x0E), [0x08]);
    mock.setRegister(CR(0x0D), [0x00]);
    mock.setRegister(CR(0x14), [0x00, 0x00]);
    mock.setRegister(CR(0x16), [0x00, 0x00]);
    mock.setRegister(CR(0x18), [0x00, 0x00]);
    const apds = new APDS9930Full(mock);
    await new Promise(r => setTimeout(r, 30));
    await apds.clearInterrupt('proximity');
    checkTrue('clearInterrupt(proximity) writes 0xE5',
        mock.writes.some(w => w.length === 1 && w[0] === 0xE5));
    await apds.clearInterrupt('als');
    checkTrue('clearInterrupt(als) writes 0xE6',
        mock.writes.some(w => w.length === 1 && w[0] === 0xE6));
    await apds.clearInterrupt('both');
    checkTrue('clearInterrupt(both) writes 0xE7',
        mock.writes.some(w => w.length === 1 && w[0] === 0xE7));
}

(async () => {
    await testMinimalConstruction();
    await testProximityReturns16Bit();
    await testLuxZeroWhenDark();
    await testLuxPositiveWhenVisibleOnly();
    await testFullConfigureAls();
    await testFullStatusDecoded();
    await testClearInterruptWritesCommand();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
})();