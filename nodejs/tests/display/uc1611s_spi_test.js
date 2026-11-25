'use strict';
// Hardware smoke test of the UC1611s driver (Raspberry Pi: SPI0 + opengpio, 4-wire SPI).
// The picture itself is verified by eye (see the demo example).
const { Default } = require('opengpio');
const { DisplayConnection } = require('../../packages/periph/src/connection/display');
const { GpioOutputPin } = require('../../packages/periph/src/connection/output_pin');
const { UC1611sSpiFull } = require('../../packages/periph/src/chips/display/uc1611s');

const CHIP = parseInt(process.env.GPIO_CHIP_NUM || '0', 10);
const DC = parseInt(process.env.DISPLAY_DC || '25', 10);
const RES = parseInt(process.env.DISPLAY_RES || '17', 10);

let passed = 0;
let failed = 0;
function check(label, ok) {
    if (ok) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

(async () => {
    const dc = new GpioOutputPin(Default.output({ chip: CHIP, line: DC }));
    const res = new GpioOutputPin(Default.output({ chip: CHIP, line: RES }));
    const connection = new DisplayConnection({ busNumber: 0, deviceNumber: 0, dc, res, maxSpeedHz: 4000000 });
    const lcd = new UC1611sSpiFull(connection);
    try {
        await lcd.ready();
        check('init_valid', true);
    } catch (e) {
        check('init_valid', false);
        console.log(`===DONE: ${passed} passed, ${failed} failed===`);
        process.exit(1);
    }
    check('geometry_240x128', lcd.width === 240 && lcd.height === 128);
    try {
        lcd.clear();
        lcd.drawRect(0, 0, 240, 128);
        lcd.drawLine(0, 0, 239, 127);
        lcd.drawRect(20, 20, 40, 30, true, true);
        await lcd.show();
        check('show_accepted', lcd.ok());
        await lcd.showPage(3);
        await lcd.setContrast(0x70);
        await lcd.setContrast(0x8F);
        await lcd.setInvert(true);
        await lcd.setInvert(false);
        await lcd.setAllOn(true);
        await lcd.setAllOn(false);
        await lcd.setFlip(false, true);
        await lcd.setFlip(true, false);
        await lcd.setFixedLines(2);
        await lcd.setScrollLine(8);
        await lcd.setScrollLine(0);
        await lcd.setFixedLines(0);
        await lcd.setBiasRatio(2);
        await lcd.setTemperatureCompensation(1);
        await lcd.setLineRate(3);
        await lcd.setPump(true);
        await lcd.setPanelLoading(true);
        await lcd.setNLineInversion(0);
        await lcd.setIsolationClock(1, 0);
        await lcd.setPartialDisplay(true, 32, 95);
        await lcd.setPartialDisplay(false);
        lcd.setWritePattern(1);
        await lcd.show();
        await lcd.setDisplayPattern(1);
        await lcd.setDisplayPattern(0);
        lcd.setWritePattern(0);
        check('commands_accepted', lcd.ok());
        await lcd.sleep();
        await lcd.wake();
        await lcd.reset();
        await lcd.show();
        check('sleep_wake_reset_accepted', lcd.ok());
    } catch (e) {
        console.log('command failed:', e.message);
        check('commands_accepted', false);
    }
    let threw = false;
    try { await lcd.readStatus(); } catch (e) { threw = true; }
    check('read_status_unsupported', threw);
    threw = false;
    try { await lcd.readRam(0, 0, 4); } catch (e) { threw = true; }
    check('read_ram_unsupported', threw);
    check('no_retries_or_errors', lcd.retryCount() === 0 && lcd.errorCount() === 0);
    connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(1); });
