'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { BMP581Full } = require('../../packages/periph/src/chips/pressure/bmp581');

const _REG_CHIP_ID     = 0x01;
const _REG_REV_ID      = 0x02;
const _REG_STATUS      = 0x28;
const _REG_INT_STATUS  = 0x27;
const _REG_OSR_CONFIG  = 0x36;
const _REG_ODR_CONFIG  = 0x37;
const _REG_DSP_CONFIG  = 0x30;
const _REG_DSP_IIR     = 0x31;
const _REG_FIFO_SEL    = 0x18;
const _REG_FIFO_CONFIG = 0x16;
const _REG_FIFO_COUNT  = 0x17;
const _REG_FIFO_DATA   = 0x29;
const _REG_INT_SOURCE  = 0x15;
const _REG_INT_CONFIG  = 0x14;
const _REG_OOR_THR_P_LSB = 0x32;
const _REG_OOR_THR_P_MSB = 0x33;
const _REG_OOR_RANGE   = 0x34;
const _REG_OOR_CONFIG  = 0x35;
const _REG_OSR_EFF     = 0x38;
const _REG_NVM_ADDR    = 0x2B;
const _REG_NVM_DATA_LSB = 0x2C;
const _REG_NVM_DATA_MSB = 0x2D;
const _REG_TEMP_XLSB   = 0x1D;
const _REG_PRESS_XLSB  = 0x20;
const _REG_CMD         = 0x7E;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function flushMicrotasks() {
    return new Promise((resolve) => setImmediate(resolve));
}

async function main() {
    const connection = new I2CConnectionMock();
    connection.setRegister(_REG_CHIP_ID, [0x50]);
    connection.setRegister(_REG_STATUS, [0x02]);

    const sensor = new BMP581Full(connection);
    await flushMicrotasks();
    checkTrue('init', true);

    const odrDefault = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_ODR_CONFIG && w[1] === 0x71);
    checkTrue('init_writes_odr_default', odrDefault.length > 0);
    const osrDefault = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_OSR_CONFIG && w[1] === 0x40);
    checkTrue('init_writes_osr_default', osrDefault.length > 0);

    connection.setRegister(_REG_TEMP_XLSB, [0x00, 0x10, 0x00]);
    connection.setRegister(_REG_PRESS_XLSB, [0x04, 0x00, 0x00]);
    checkTrue('temperature_decode', Math.abs((await sensor.temperature()) - 0.0625) < 1e-6);
    checkTrue('pressure_decode', Math.abs((await sensor.pressure()) - 0.0625) < 1e-6);

    const both = await sensor.both();
    checkTrue('both_pressure', Math.abs(both.pressure - 0.0625) < 1e-6);
    checkTrue('both_temperature', Math.abs(both.temperature - 0.0625) < 1e-6);

    connection.setRegister(_REG_CHIP_ID, [0x50]);
    checkTrue('chip_id', (await sensor.chipId()) === 0x50);

    connection.setRegister(_REG_REV_ID, [0x32]);
    checkTrue('rev_id', (await sensor.revId()) === 0x32);

    connection.setRegister(_REG_STATUS, [0x09]);
    checkTrue('status', (await sensor.status()) === 0x09);

    connection.setRegister(_REG_INT_STATUS, [0x11]);
    checkTrue('interrupt_status', (await sensor.interruptStatus()) === 0x11);

    connection.setRegister(_REG_INT_STATUS, [0x01]);
    checkTrue('data_ready', (await sensor.dataReady()) === true);

    await sensor.configure(0x17, 4, 2, true);
    const configureOdr = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_ODR_CONFIG).pop();
    const configureOsr = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_OSR_CONFIG).pop();
    checkTrue('configure_odr_10Hz', configureOdr[1] === ((0x17 << 2) | 0x01));
    checkTrue('configure_osr_x16_x4', configureOsr[1] === (0x40 | (4 << 3) | 2));

    await sensor.setMode(BMP581Full.MODE_STANDBY);
    const standby = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_ODR_CONFIG).pop();
    checkTrue('set_mode_standby', standby[1] === ((0x17 << 2) | 0x00));

    await sensor.setMode(BMP581Full.MODE_CONTINUOUS);
    const continuous = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_ODR_CONFIG).pop();
    checkTrue('set_mode_continuous', continuous[1] === ((0x17 << 2) | 0x03));

    await sensor.setIirFilter(BMP581Full.IIR_COEFF_3, BMP581Full.IIR_BYPASS);
    const iirDsp = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_DSP_CONFIG).pop();
    const iirIir = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_DSP_IIR).pop();
    checkTrue('set_iir_filter_dsp', (iirDsp[1] & 0x28) === 0x28);
    checkTrue('set_iir_filter_iir', iirIir[1] === ((BMP581Full.IIR_COEFF_3 << 3) | BMP581Full.IIR_BYPASS));

    await sensor.enableDrdyInterrupt(true);
    const drdy = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_INT_SOURCE).pop();
    checkTrue('enable_drdy_interrupt', (drdy[1] & 0x01) !== 0);

    await sensor.enableFifoInterrupt(true, false);
    const fifoThs = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_INT_SOURCE).pop();
    checkTrue('enable_fifo_threshold', (fifoThs[1] & 0x04) !== 0);

    await sensor.enableOorInterrupt(true);
    const oor = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_INT_SOURCE).pop();
    checkTrue('enable_oor_interrupt', (oor[1] & 0x08) !== 0);

    await sensor.configureInterrupt(1, 1, true, true);
    const intCfg = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_INT_CONFIG).pop();
    checkTrue('configure_interrupt', intCfg[1] === 0x0F);

    await sensor.setOorThreshold(110000, 200, 2);
    const thr17 = Math.floor(110000 * 64.0) >> 7;
    const thrLsb = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_OOR_THR_P_LSB).pop();
    const thrMsb = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_OOR_THR_P_MSB).pop();
    const range = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_OOR_RANGE).pop();
    const oorCfg = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_OOR_CONFIG).pop();
    checkTrue('oor_threshold_lsb', thrLsb[1] === (thr17 & 0xFF));
    checkTrue('oor_threshold_msb', thrMsb[1] === ((thr17 >> 8) & 0xFF));
    checkTrue('oor_range', range[1] === ((Math.floor(200 * 64.0) >> 7) & 0xFF));
    checkTrue('oor_config_count_limit', (oorCfg[1] & 0xC0) === (2 << 6));

    await sensor.configureFifo(BMP581Full.FIFO_BOTH, BMP581Full.FIFO_STREAM, 8);
    const fifoSel = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_FIFO_SEL).pop();
    const fifoCfg = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_FIFO_CONFIG).pop();
    checkTrue('configure_fifo_sel', fifoSel[1] === 0x03);
    checkTrue('configure_fifo_config', fifoCfg[1] === 8);

    connection.setRegister(_REG_FIFO_COUNT, [4]);
    checkTrue('fifo_count', (await sensor.fifoCount()) === 4);

    connection.setRegister(_REG_FIFO_COUNT, [2]);
    connection.setRegister(_REG_FIFO_DATA, [
        0x00, 0x10, 0x00, 0x04, 0x00, 0x00,
        0x00, 0x10, 0x00, 0x04, 0x00, 0x00,
    ]);

    connection.setRegister(_REG_OSR_EFF, [0xA0]);
    const eff = await sensor.effectiveOsr();
    checkTrue('effective_osr', eff.osrP === 4 && eff.osrT === 0);
    checkTrue('odr_is_valid', (await sensor.odrIsValid()) === true);

    connection.setRegister(_REG_STATUS, [0x00]);
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();