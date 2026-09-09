'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { APDS9960Full } = require('../../packages/periph/src/chips/light/apds9960');

const _REG_ENABLE     = 0x80;
const _REG_ATIME      = 0x81;
const _REG_WTIME      = 0x83;
const _REG_AILTL      = 0x84;
const _REG_AILTH      = 0x85;
const _REG_AIHTL      = 0x86;
const _REG_AIHTH      = 0x87;
const _REG_PILT       = 0x89;
const _REG_PIHT       = 0x8B;
const _REG_PERS       = 0x8C;
const _REG_CONFIG1    = 0x8D;
const _REG_PPULSE     = 0x8E;
const _REG_CONTROL    = 0x8F;
const _REG_CONFIG2    = 0x90;
const _REG_ID         = 0x92;
const _REG_STATUS     = 0x93;
const _REG_CDATAL     = 0x94;
const _REG_PDATA      = 0x9C;
const _REG_POFFSET_UR = 0x9D;
const _REG_POFFSET_DL = 0x9E;
const _REG_CONFIG3    = 0x9F;
const _REG_GPENTH     = 0xA0;
const _REG_GEXTH      = 0xA1;
const _REG_GCONF2     = 0xA3;
const _REG_GPULSE     = 0xA6;
const _REG_GCONF4     = 0xAB;
const _REG_GFLVL      = 0xAE;
const _REG_GSTATUS    = 0xAF;
const _REG_PICLEAR    = 0xE5;
const _REG_CICLEAR    = 0xE6;
const _REG_AICLEAR    = 0xE7;
const _REG_GFIFO_U    = 0xFC;

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
    connection.setRegister(_REG_ID, [0xAB]);

    const sensor = new APDS9960Full(connection);
    await flushMicrotasks(); // let the fire-and-forget _init() finish before asserting on it
    checkTrue('init', true);

    const enableWrites = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_ENABLE);
    checkTrue('init_writes_enable_off_then_on',
        enableWrites[0][1] === 0x00 && enableWrites[enableWrites.length - 1][1] === 0x03);
    checkTrue('init_writes_atime_default', connection.registers.get(_REG_ATIME) === 0xB6);
    checkTrue('init_writes_control_default', connection.registers.get(_REG_CONTROL) === 0x01);
    checkTrue('init_writes_config2_default', connection.registers.get(_REG_CONFIG2) === 0x01);

    // color(): CDATAL burst of 8 bytes, LE 16-bit words: clear=0x1234, red=0x0102,
    // green=0x0304, blue=0x0506.
    connection.setRegister(_REG_CDATAL, [0x34, 0x12, 0x02, 0x01, 0x04, 0x03, 0x06, 0x05]);
    const rgbc = await sensor.color();
    checkTrue('color_clear', rgbc.clear === 0x1234);
    checkTrue('color_red', rgbc.red === 0x0102);
    checkTrue('color_green', rgbc.green === 0x0304);
    checkTrue('color_blue', rgbc.blue === 0x0506);

    checkTrue('color_clear_method', (await sensor.colorClear()) === 0x1234);
    checkTrue('color_red_method', (await sensor.colorRed()) === 0x0102);
    checkTrue('color_green_method', (await sensor.colorGreen()) === 0x0304);
    checkTrue('color_blue_method', (await sensor.colorBlue()) === 0x0506);

    await sensor.enableProximity(true);
    checkTrue('enable_proximity_sets_pen', connection.registers.get(_REG_ENABLE) === 0x07);
    await sensor.enableProximity(false);
    checkTrue('enable_proximity_clears_pen', connection.registers.get(_REG_ENABLE) === 0x03);

    connection.setRegister(_REG_PDATA, [200]);
    checkTrue('proximity', (await sensor.proximity()) === 200);

    await sensor.enableWait(true);
    checkTrue('enable_wait_sets_wen', connection.registers.get(_REG_ENABLE) === 0x0B);
    await sensor.enableWait(false);
    checkTrue('enable_wait_clears_wen', connection.registers.get(_REG_ENABLE) === 0x03);

    await sensor.configureWait(100, true);
    checkTrue('configure_wait_wtime', connection.registers.get(_REG_WTIME) === 100);
    checkTrue('configure_wait_config1_wlong', connection.registers.get(_REG_CONFIG1) === 0x62);
    await sensor.configureWait(50, false);
    checkTrue('configure_wait_config1_no_wlong', connection.registers.get(_REG_CONFIG1) === 0x60);

    await sensor.configureAls(0xDB, 2);
    checkTrue('configure_als_atime', connection.registers.get(_REG_ATIME) === 0xDB);
    checkTrue('configure_als_again', (connection.registers.get(_REG_CONTROL) & 0x03) === 2);

    await sensor.configureProximityLed(1, 2, 10, 3);
    const ctrl = connection.registers.get(_REG_CONTROL);
    checkTrue('configure_proximity_led_ldrive', ((ctrl >> 6) & 0x03) === 1);
    checkTrue('configure_proximity_led_pgain', ((ctrl >> 2) & 0x03) === 2);
    checkTrue('configure_proximity_led_ppulse', connection.registers.get(_REG_PPULSE) === ((3 << 6) | 10));

    await sensor.setLedBoost(2);
    checkTrue('set_led_boost', connection.registers.get(_REG_CONFIG2) === ((2 << 4) | 0x01));

    await sensor.alsThreshold(0x1234, 0x5678);
    checkTrue('als_threshold_low',
        connection.registers.get(_REG_AILTL) === 0x34 && connection.registers.get(_REG_AILTH) === 0x12);
    checkTrue('als_threshold_high',
        connection.registers.get(_REG_AIHTL) === 0x78 && connection.registers.get(_REG_AIHTH) === 0x56);

    await sensor.proximityThreshold(10, 200);
    checkTrue('proximity_threshold',
        connection.registers.get(_REG_PILT) === 10 && connection.registers.get(_REG_PIHT) === 200);

    await sensor.setPersistence(5, 3);
    checkTrue('set_persistence', connection.registers.get(_REG_PERS) === ((5 << 4) | 3));

    await sensor.enableAlsInterrupt(true);
    checkTrue('enable_als_interrupt', (connection.registers.get(_REG_ENABLE) & 0x10) !== 0);
    await sensor.enableProximityInterrupt(true);
    checkTrue('enable_proximity_interrupt', (connection.registers.get(_REG_ENABLE) & 0x20) !== 0);

    await sensor.clearProximityInterrupt();
    checkTrue('clear_proximity_interrupt',
        Buffer.compare(connection.writes[connection.writes.length - 1], Buffer.from([_REG_PICLEAR])) === 0);
    await sensor.clearAlsInterrupt();
    checkTrue('clear_als_interrupt',
        Buffer.compare(connection.writes[connection.writes.length - 1], Buffer.from([_REG_CICLEAR])) === 0);
    await sensor.clearAllInterrupts();
    checkTrue('clear_all_interrupts',
        Buffer.compare(connection.writes[connection.writes.length - 1], Buffer.from([_REG_AICLEAR])) === 0);

    // Sign-magnitude proximity offset encoding: -50 -> 0x80|50=0xB2, 100 -> 0x64.
    await sensor.setProximityOffset(-50, 100);
    checkTrue('set_proximity_offset_negative', connection.registers.get(_REG_POFFSET_UR) === 0xB2);
    checkTrue('set_proximity_offset_positive', connection.registers.get(_REG_POFFSET_DL) === 0x64);

    await sensor.setProximityMask(true, false, true, false);
    checkTrue('set_proximity_mask', connection.registers.get(_REG_CONFIG3) === (0x08 | 0x02));

    await sensor.enableGesture(true);
    checkTrue('enable_gesture_sets_gen', (connection.registers.get(_REG_ENABLE) & 0x40) !== 0);
    checkTrue('enable_gesture_sets_gmode', (connection.registers.get(_REG_GCONF4) & 0x01) !== 0);
    await sensor.enableGesture(false);
    checkTrue('enable_gesture_clears_gen', (connection.registers.get(_REG_ENABLE) & 0x40) === 0);
    checkTrue('enable_gesture_clears_gmode', (connection.registers.get(_REG_GCONF4) & 0x01) === 0);

    await sensor.configureGesture(1, 2, 20, 3, 5, 30, 10);
    checkTrue('configure_gesture_gpenth', connection.registers.get(_REG_GPENTH) === 30);
    checkTrue('configure_gesture_gexth', connection.registers.get(_REG_GEXTH) === 10);
    checkTrue('configure_gesture_gconf2', connection.registers.get(_REG_GCONF2) === ((1 << 5) | (2 << 3) | 5));
    checkTrue('configure_gesture_gpulse', connection.registers.get(_REG_GPULSE) === ((3 << 6) | 20));

    connection.setRegister(_REG_GSTATUS, [0x01]);
    checkTrue('gesture_available', (await sensor.gestureAvailable()) === true);

    connection.setRegister(_REG_GFLVL, [2]);
    connection.setRegister(_REG_GFIFO_U, [10, 20, 30, 40]);
    const fifo = await sensor.readGestureFifo();
    checkTrue('read_gesture_fifo_level', fifo.length === 2);
    checkTrue('read_gesture_fifo_first_dataset',
        fifo[0].u === 10 && fifo[0].d === 20 && fifo[0].l === 30 && fifo[0].r === 40);

    connection.setRegister(_REG_GFLVL, [0]);
    checkTrue('read_gesture_fifo_empty', (await sensor.readGestureFifo()).length === 0);
    checkTrue('gesture_fifo_level', (await sensor.gestureFifoLevel()) === 0);

    await sensor.clearGestureFifo();
    checkTrue('clear_gesture_fifo', (connection.registers.get(_REG_GCONF4) & 0x04) !== 0);

    await sensor.enableGestureInterrupt(true);
    checkTrue('enable_gesture_interrupt', (connection.registers.get(_REG_GCONF4) & 0x02) !== 0);

    connection.setRegister(_REG_STATUS, [0x93]); // CPSAT|PVALID|AVALID
    checkTrue('status', (await sensor.status()) === 0x93);
    checkTrue('is_als_valid', (await sensor.isAlsValid()) === true);
    checkTrue('is_proximity_valid', (await sensor.isProximityValid()) === true);
    checkTrue('is_als_saturated', (await sensor.isAlsSaturated()) === true);
    checkTrue('is_proximity_saturated', (await sensor.isProximitySaturated()) === false);

    checkTrue('chip_id', (await sensor.chipId()) === 0xAB);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
