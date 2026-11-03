'use strict';
// Hardware smoke test of the ST7036 driver (Raspberry Pi: SPI0 + opengpio).
// The picture itself is verified by eye (see the demo example).
const { Default } = require('opengpio');
const { DisplayConnection } = require('../../packages/periph/src/connection/display');
const { GpioOutputPin } = require('../../packages/periph/src/connection/output_pin');
const { ST7036Full } = require('../../packages/periph/src/chips/display/st7036');

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
    const connection = new DisplayConnection({ busNumber: 0, deviceNumber: 0, dc, res, maxSpeedHz: 1000000 });
    const lcd = new ST7036Full(connection);
    try {
        await lcd.ready();
        check('init_valid', true);
    } catch (e) {
        check('init_valid', false);
        console.log(`===DONE: ${passed} passed, ${failed} failed===`);
        process.exit(1);
    }
    check('geometry_16x2', lcd.columns === 16 && lcd.rows === 2);
    try {
        await lcd.clear();
        await lcd.printAt(0, 0, 'Periph ST7036');
        await lcd.printAt(0, 1, 'smoke test');
        check('text_accepted', true);
        await lcd.createChar(0, [0x1F, 0x11, 0x15, 0x11, 0x1F, 0x00, 0x1F, 0x00]);
        await lcd.setCursor(15, 1);
        await lcd.writeChar(0);
        await lcd.cursor(true, true);
        await lcd.cursor(false, false);
        await lcd.setEntryMode(true, false);
        await lcd.shiftDisplay(true);
        await lcd.shiftDisplay(false);
        await lcd.shiftCursor(true);
        await lcd.returnHome();
        await lcd.setDdramAddress(0x40);
        await lcd.setContrast(40);
        await lcd.setBooster(true);
        await lcd.setFollower(true, 5);
        await lcd.setBias(false);
        await lcd.setContrast(24);
        await lcd.display(false);
        await lcd.display(true);
        await lcd.home();
        check('commands_accepted', true);
    } catch (e) { check('commands_accepted', false); }
    connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed ? 1 : 0);
})();
