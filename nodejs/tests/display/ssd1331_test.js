'use strict';
// Hardware smoke test of the SSD1331 driver (Raspberry Pi: SPI0 + opengpio).
// The picture itself is verified by eye (see the demo example).
const { Default } = require('opengpio');
const { DisplayConnection } = require('../../packages/periph/src/connection/display');
const { GpioOutputPin } = require('../../packages/periph/src/connection/output_pin');
const { SSD1331Full, BLACK, WHITE, RED, GREEN, BLUE, YELLOW, MODE_INVERSE, MODE_NORMAL } = require('../../packages/periph/src/chips/display/ssd1331');

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
    const connection = new DisplayConnection({ busNumber: 0, deviceNumber: 0, dc, res });
    const oled = new SSD1331Full(connection);
    try {
        await oled.ready();
        check('init_valid', true);
    } catch (e) {
        check('init_valid', false);
        console.log(`===DONE: ${passed} passed, ${failed} failed===`);
        process.exit(1);
    }
    oled.clear(BLUE);
    check('clear_readback', oled.getPixel(0, 0) === BLUE && oled.getPixel(95, 63) === BLUE);
    oled.setPixel(5, 6, RED);
    check('pixel_readback', oled.getPixel(5, 6) === RED);
    oled.drawRect(10, 10, 20, 10, GREEN, true);
    check('draw_rect_fill', oled.getPixel(15, 15) === GREEN);
    oled.drawLine(0, 0, 95, 63, WHITE);
    check('draw_line_endpoints', oled.getPixel(0, 0) === WHITE && oled.getPixel(95, 63) === WHITE);
    try {
        await oled.show();
        await oled.showRegion(8, 8, 32, 16);
        await oled.hwClear(0, 0, 95, 63);
        await oled.hwRect(10, 10, 40, 30, YELLOW, RED, true);
        await oled.hwLine(0, 0, 95, 63, WHITE);
        await oled.hwCopy(10, 10, 30, 30, 50, 20);
        await oled.hwDim(0, 0, 47, 63);
        await oled.setContrast(0x80, 0x80, 0x80);
        await oled.setMasterCurrent(7);
        await oled.setDisplayMode(MODE_INVERSE);
        await oled.setDisplayMode(MODE_NORMAL);
        await oled.setFlip(false, false);
        await oled.scroll(1, 0, 64, 0, 0);
        await oled.scrollStop();
        await oled.displayOff();
        await oled.displayOn();
        check('commands_accepted', true);
    } catch (e) { check('commands_accepted', false); }
    connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed ? 1 : 0);
})();
