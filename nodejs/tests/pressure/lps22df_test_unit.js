'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { LPS22DFFull } = require('../../packages/periph/src/chips/pressure/lps22df');

const _REG_WHO_AM_I      = 0x0F;
const _REG_CTRL_REG1     = 0x10;
const _REG_CTRL_REG2     = 0x11;
const _REG_CTRL_REG3     = 0x12;
const _REG_CTRL_REG4     = 0x13;
const _REG_INTERRUPT_CFG = 0x0B;
const _REG_THS_P_L       = 0x0C;
const _REG_THS_P_H       = 0x0D;
const _REG_RPDS_L        = 0x1A;
const _REG_RPDS_H        = 0x1B;
const _REG_FIFO_CTRL     = 0x14;
const _REG_FIFO_WTM      = 0x15;
const _REG_REF_P_L       = 0x16;
const _REG_PRESS_OUT_XL  = 0x28;
const _REG_TEMP_OUT_L    = 0x2B;
const _REG_STATUS        = 0x27;
const _REG_FIFO_STATUS1  = 0x25;
const _REG_INT_SOURCE    = 0x24;
const _REG_FIFO_PRESS_XL = 0x78;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function flushMicrotasks() {
    return new Promise((resolve) => setImmediate(resolve));
}

function packPress(hPa) {
    let raw = Math.round(hPa * 4096);
    if (raw < 0) raw += 0x1000000;
    return [raw & 0xFF, (raw >> 8) & 0xFF, (raw >> 16) & 0xFF];
}

function packTemp(celsius) {
    const raw = (Math.round(celsius * 100)) & 0xFFFF;
    return [raw & 0xFF, (raw >> 8) & 0xFF];
}

async function main() {
    const connection = new I2CConnectionMock();
    connection.setRegister(_REG_WHO_AM_I, [0xB4]);

    const sensor = new LPS22DFFull(connection);
    await flushMicrotasks();
    checkTrue('init', true);

    const swReset = connection.writes.some((w) => w.length === 2 && w[0] === _REG_CTRL_REG2 && w[1] === 0x04);
    checkTrue('init_writes_swreset', swReset);

    const ctrlReg1Default = connection.writes.some((w) => w.length === 2 && w[0] === _REG_CTRL_REG1 && w[1] === 0x18);
    checkTrue('init_writes_ctrl_reg1_default', ctrlReg1Default);

    // pressure(): known hPa -> known Pa
    connection.setRegister(_REG_STATUS, [0x01]);
    connection.setRegister(_REG_PRESS_OUT_XL, packPress(1013.25));
    const p = await sensor.pressure();
    checkTrue('pressure_known_value', Math.abs(p - 101325.0) < 0.01);

    // temperature()
    connection.setRegister(_REG_TEMP_OUT_L, packTemp(23.5));
    const t = await sensor.temperature();
    checkTrue('temperature_known_value', Math.abs(t - 23.5) < 0.01);

    // configure(odr=4, avg=2, enLpfp=true, lfpfCfg=1, bdu=true)
    // CTRL_REG1 = (4<<3)|2 = 0x22; CTRL_REG2 = 0x10|0x20|0x08 = 0x38
    await sensor.configure(4, 2, true, 1, true);
    const cfgCtrl1 = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG1).pop();
    const cfgCtrl2 = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG2).pop();
    checkTrue('configure_ctrl_reg1', cfgCtrl1[1] === 0x22);
    checkTrue('configure_ctrl_reg2', cfgCtrl2[1] === 0x38);

    // altitude
    connection.setRegister(_REG_STATUS, [0x01]);
    connection.setRegister(_REG_PRESS_OUT_XL, packPress(900.0));
    const alt = await sensor.altitude(101325.0);
    checkTrue('altitude_known_value', Math.abs(alt - 989.0) < 5.0);

    // setPressureOffset(-50 Pa) -> -0.5 hPa * 4096 = -2048 = 0xF800
    await sensor.setPressureOffset(-50.0);
    const rpdsL = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_RPDS_L).pop();
    const rpdsH = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_RPDS_H).pop();
    checkTrue('pressure_offset_l', rpdsL[1] === 0x00);
    checkTrue('pressure_offset_h', rpdsH[1] === 0xF8);

    // setPressureThreshold(102000 Pa) -> 1020 hPa * 16 = 16320 = 0x3FC0
    await sensor.setPressureThreshold(102000.0);
    const thsL = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_THS_P_L).pop();
    const thsH = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_THS_P_H).pop();
    checkTrue('threshold_l', thsL[1] === 0xC0);
    checkTrue('threshold_h', thsH[1] === 0x3F);

    // configureInterrupt
    await sensor.configureInterrupt(true, true, true, true, true, true, true, true);
    const cfgCtrl3 = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG3).pop();
    const cfgCtrl4 = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG4).pop();
    checkTrue('configure_interrupt_ctrl_reg3', cfgCtrl3[1] === 0x0B);
    checkTrue('configure_interrupt_ctrl_reg4', cfgCtrl4[1] === 0x77);

    // configurePressureEvent
    await sensor.configurePressureEvent(true, true, true);
    const icfg = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_INTERRUPT_CFG).pop();
    checkTrue('configure_pressure_event', icfg[1] === 0x07);

    // autozero
    await sensor.autozero();
    const az = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_INTERRUPT_CFG).pop();
    checkTrue('autozero', az[1] === 0x20);

    // setFifoMode(1) -> FIFO = 0x01
    await sensor.setFifoMode(LPS22DFFull.FIFO_FIFO);
    const fifo = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_FIFO_CTRL).pop();
    checkTrue('set_fifo_mode_fifo', fifo[1] === 0x01);

    // setFifoWatermark(100)
    await sensor.setFifoWatermark(100);
    const wtm = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_FIFO_WTM).pop();
    checkTrue('set_fifo_watermark', wtm[1] === 100);

    // referencePressure
    connection.setRegister(_REG_REF_P_L, [0x00, 0x10]);
    const ref = await sensor.referencePressure();
    checkTrue('reference_pressure', Math.abs(ref - 100.0) < 0.01);

    // interruptSource
    connection.setRegister(_REG_INT_SOURCE, [0x87]);
    const src = await sensor.interruptSource();
    checkTrue('interrupt_source_all', src.boot_on && src.ia && src.ph && src.pl);

    // readFifo: N=3 samples
    connection.setRegister(_REG_FIFO_STATUS1, [3]);
    const fifoBytes = [];
    for (const hpa of [1000.0, 1010.0, 1020.0]) {
        fifoBytes.push(...packPress(hpa));
    }
    connection.setRegister(_REG_FIFO_PRESS_XL, fifoBytes);
    const samples = await sensor.readFifo();
    checkTrue('read_fifo_length', samples.length === 3);
    checkTrue('read_fifo_values',
        Math.abs(samples[0] - 100000.0) < 0.01 &&
        Math.abs(samples[1] - 101000.0) < 0.01 &&
        Math.abs(samples[2] - 102000.0) < 0.01);

    // SPI transport
    const spiConnection = new I2CConnectionMock();
    spiConnection.setRegister(_REG_WHO_AM_I, [0xB4]);
    const spiSensor = new LPS22DFFull(spiConnection, 'spi');
    await flushMicrotasks();
    const spiMaskedCtrl1 = spiConnection.writes.some(
        (w) => w.length === 2 && w[0] === (_REG_CTRL_REG1 & 0x7F) && w[1] === 0x18);
    const spiUnmaskedCtrl1 = spiConnection.writes.some(
        (w) => w.length === 2 && w[0] === _REG_CTRL_REG1);
    checkTrue('spi_init_writes_ctrl_reg1_masked', spiMaskedCtrl1);
    checkTrue('spi_init_no_unmasked_ctrl_reg1_write', !spiUnmaskedCtrl1);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();