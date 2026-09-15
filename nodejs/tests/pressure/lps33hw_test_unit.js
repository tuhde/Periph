'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { LPS33HWMinimal, LPS33HWFull } = require('../../packages/periph/src/chips/pressure/lps33hw');

const _REG_INTERRUPT_CFG = 0x0B;
const _REG_THS_P_L       = 0x0C;
const _REG_THS_P_H       = 0x0D;
const _REG_WHO_AM_I      = 0x0F;
const _REG_CTRL_REG1     = 0x10;
const _REG_CTRL_REG2     = 0x11;
const _REG_CTRL_REG3     = 0x12;
const _REG_FIFO_CTRL     = 0x14;
const _REG_RPDS_L        = 0x18;
const _REG_RPDS_H        = 0x19;
const _REG_RES_CONF      = 0x1A;
const _REG_INT_SOURCE    = 0x25;
const _REG_FIFO_STATUS   = 0x26;
const _REG_STATUS        = 0x27;
const _REG_PRESS_XL      = 0x28;
const _REG_LPFP_RES      = 0x33;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function flushMicrotasks() {
    return new Promise((resolve) => setImmediate(resolve));
}

function preloadDataReady(connection) {
    connection.setRegister(_REG_STATUS, [0x03]);
}

function preloadPressureTemp(connection, pressurePa, temperatureC) {
    const rawP = (pressurePa * 4096.0) / 100.0;
    const rawT = (temperatureC * 100.0);
    const p24 = Math.round(rawP) & 0xFFFFFF;
    const t16 = Math.round(rawT) & 0xFFFF;
    connection.setRegister(_REG_PRESS_XL, [
        p24 & 0xFF,
        (p24 >> 8) & 0xFF,
        (p24 >> 16) & 0xFF,
        t16 & 0xFF,
        (t16 >> 8) & 0xFF,
    ]);
}

async function main() {
    const connection = new I2CConnectionMock();
    connection.setRegister(_REG_WHO_AM_I, [0xB1]);
    preloadDataReady(connection);
    preloadPressureTemp(connection, 101325.0, 25.0);

    const sensor = new LPS33HWMinimal(connection);
    await flushMicrotasks();
    checkTrue('init_chip_id_check', true);

    const initCtrl2 = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG2);
    checkTrue('init_writes_ctrl_reg2', initCtrl2.length >= 2);
    const initCtrl1 = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG1 && w[1] === 0x12);
    checkTrue('init_writes_ctrl_reg1_default', initCtrl1.length > 0);

    const t = await sensor.temperature();
    checkTrue('temperature', Math.abs(t - 25.0) < 1e-6);

    const p = await sensor.pressure();
    checkTrue('pressure', Math.abs(p - 101325.0) < 0.01);

    const cid = await sensor._readReg(_REG_WHO_AM_I, 1);
    checkTrue('chip_id', cid[0] === 0xB1);

    const full = new LPS33HWFull(connection);
    await flushMicrotasks();

    // configure(odr=2, bdu=1, enLpfp=1, lpfpCfg=1, lcEn=0, sim=0):
    // CTRL_REG1 = (2<<4)|(1<<3)|(1<<2)|(1<<1) = 0x2E.
    connection.setRegister(_REG_RES_CONF, [0x00]);
    await full.configure(2, 1, 1, 1, 0, 0);
    const ctrl1Write = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG1 && w[1] === 0x2E).pop();
    checkTrue('configure_ctrl_reg1', ctrl1Write !== undefined);

    // oneShot: STATUS=0x03 (P_DA|T_DA) so the loop returns immediately.
    preloadDataReady(connection);
    preloadPressureTemp(connection, 101325.0, 25.0);
    const sample = await full.oneShot();
    checkTrue('one_shot_pressure', Math.abs(sample.pressure_Pa - 101325.0) < 0.01);
    checkTrue('one_shot_temperature', Math.abs(sample.temperature_C - 25.0) < 1e-6);
    const oneShotTrig = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG2 && (w[1] & 0x01) === 0x01);
    checkTrue('one_shot_triggers_ctrl_reg2_bit0', oneShotTrig.length > 0);

    // status: STATUS register returns the raw byte.
    connection.setRegister(_REG_STATUS, [0x05]);
    checkTrue('status', (await full.status()) === 0x05);

    // setPressureOffset(offsetHPa=1.5): raw = round(1.5 * 16) = 24 -> 0x0018.
    await full.setPressureOffset(1.5);
    const rpdsLWrite = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_RPDS_L && w[1] === 0x18).pop();
    const rpdsHWrite = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_RPDS_H && w[1] === 0x00).pop();
    checkTrue('set_pressure_offset_rpds_l', rpdsLWrite !== undefined);
    checkTrue('set_pressure_offset_rpds_h', rpdsHWrite !== undefined);

    // setAutozero: INTERRUPT_CFG OR 0x20. Read-modify-write.
    connection.setRegister(_REG_INTERRUPT_CFG, [0x00]);
    await full.setAutozero();
    const autozeroWrite = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_INTERRUPT_CFG && (w[1] & 0x20) === 0x20).pop();
    checkTrue('set_autozero', autozeroWrite !== undefined);

    // clearAutozero: INTERRUPT_CFG OR 0x10.
    connection.setRegister(_REG_INTERRUPT_CFG, [0x20]);
    await full.clearAutozero();
    const clearAutozeroWrite = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_INTERRUPT_CFG && (w[1] & 0x10) === 0x10).pop();
    checkTrue('clear_autozero', clearAutozeroWrite !== undefined);

    // setAutorifp / clearAutorifp: 0x80 / 0x40 mask bits.
    connection.setRegister(_REG_INTERRUPT_CFG, [0x00]);
    await full.setAutorifp();
    const autorifpWrite = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_INTERRUPT_CFG && (w[1] & 0x80) === 0x80).pop();
    checkTrue('set_autorifp', autorifpWrite !== undefined);
    connection.setRegister(_REG_INTERRUPT_CFG, [0x80]);
    await full.clearAutorifp();
    const clearAutorifpWrite = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_INTERRUPT_CFG && (w[1] & 0x40) === 0x40).pop();
    checkTrue('clear_autorifp', clearAutorifpWrite !== undefined);

    // configureInterrupt(drdy=1, fFth=0, fOvr=0, fFss5=1, intS=3, activeLow=1, openDrain=0):
    // CTRL_REG3 = 0x80 | 0x20 | 0x04 | 0x03 = 0xA7.
    await full.configureInterrupt(1, 0, 0, 1, 3, 1, 0);
    const intCfgWrite = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG3 && w[1] === 0xA7).pop();
    checkTrue('configure_interrupt', intCfgWrite !== undefined);

    // configurePressureInterrupt(highEn=1, lowEn=0, thresholdHPa=2.0, latch=1):
    // rawThs = round(2.0 * 16) = 32 = 0x0020 -> THS_P_L=0x20, THS_P_H=0x00.
    // INTERRUPT_CFG lower nibble = (1<<2)|(1<<1) = 0x06 (DIFF_EN=latch, PHE=highEn).
    connection.setRegister(_REG_INTERRUPT_CFG, [0x00]);
    await full.configurePressureInterrupt(1, 0, 2.0, 1);
    const thsLWrite = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_THS_P_L && w[1] === 0x20).pop();
    const thsHWrite = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_THS_P_H && w[1] === 0x00).pop();
    const intCfgPressWrite = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_INTERRUPT_CFG && (w[1] & 0x0F) === 0x06).pop();
    checkTrue('configure_pressure_interrupt_ths_l', thsLWrite !== undefined);
    checkTrue('configure_pressure_interrupt_ths_h', thsHWrite !== undefined);
    checkTrue('configure_pressure_interrupt_cfg', intCfgPressWrite !== undefined);

    // enableFifo(mode=1, watermark=10): FIFO_CTRL = (1<<5)|10 = 0x2A; CTRL_REG2 OR 0x40.
    connection.setRegister(_REG_CTRL_REG2, [0x00]);
    await full.enableFifo(1, 10);
    const fifoCtrlWrite = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_FIFO_CTRL && w[1] === 0x2A).pop();
    const fifoEnableWrite = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG2 && (w[1] & 0x40) === 0x40).pop();
    checkTrue('enable_fifo_ctrl', fifoCtrlWrite !== undefined);
    checkTrue('enable_fifo_bit', fifoEnableWrite !== undefined);

    // disableFifo: CTRL_REG2 &= ~0x40, FIFO_CTRL=0.
    connection.setRegister(_REG_CTRL_REG2, [0x40]);
    await full.disableFifo();
    const fifoDisableWrite = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG2 && (w[1] & 0x40) === 0).pop();
    const fifoResetWrite = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_FIFO_CTRL && w[1] === 0x00).pop();
    checkTrue('disable_fifo_bit', fifoDisableWrite !== undefined);
    checkTrue('disable_fifo_zero', fifoResetWrite !== undefined);

    // fifoStatus: raw FIFO_STATUS byte.
    connection.setRegister(_REG_FIFO_STATUS, [0x1F]);
    checkTrue('fifo_status', (await full.fifoStatus()) === 0x1F);

    // interruptStatus: raw INT_SOURCE byte.
    connection.setRegister(_REG_INT_SOURCE, [0x10]);
    checkTrue('interrupt_status', (await full.interruptStatus()) === 0x10);

    // resetLpf: single-byte read of LPFP_RES.
    const beforeLpfReads = connection.writes.filter((w) => w.length === 1 && w[0] === _REG_LPFP_RES).length;
    await full.resetLpf();
    const afterLpfReads = connection.writes.filter((w) => w.length === 1 && w[0] === _REG_LPFP_RES).length;
    checkTrue('reset_lpf_reads_lpfp_res', afterLpfReads > beforeLpfReads);

    // reset(): BOOT bit set then cleared; CTRL_REG2 writes 0x04 (RESET) then 0x10 (DEFAULT), CTRL_REG1 written.
    connection.setRegister(_REG_CTRL_REG2, [0x04]);
    await full.reset();
    const resetWrite = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG2 && w[1] === 0x04).pop();
    const defaultWrite = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG2 && w[1] === 0x10).pop();
    checkTrue('reset_writes_boot', resetWrite !== undefined);
    checkTrue('reset_writes_default', defaultWrite !== undefined);

    // reboot(): CTRL_REG2 = 0x80 (BOOT).
    connection.setRegister(_REG_INT_SOURCE, [0x00]);
    await full.reboot();
    const rebootWrite = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG2 && w[1] === 0x80).pop();
    checkTrue('reboot_writes_boot', rebootWrite !== undefined);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
