'use strict';
// Hardware smoke test of the IL0373 driver (Raspberry Pi: SPI0 + opengpio).
// Needs a panel: show() waits for the real refresh .
const { Default } = require('opengpio');
const { DisplayConnection } = require('../../packages/periph/src/connection/display');
const { GpioOutputPin } = require('../../packages/periph/src/connection/output_pin');
const { PollingInputPin } = require('../../packages/periph/src/connection/input_pin');
const { IL0373Full, BLACK, WHITE } = require('../../packages/periph/src/chips/display/il0373');

const CHIP = parseInt(process.env.GPIO_CHIP_NUM || '0', 10);
const DC = parseInt(process.env.DISPLAY_DC || '25', 10);
const RES = parseInt(process.env.DISPLAY_RES || '17', 10);
const BUSY = parseInt(process.env.DISPLAY_BUSY || '24', 10);

let passed = 0;
let failed = 0;
function check(label, ok) {
    if (ok) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

(async () => {
    const dc = new GpioOutputPin(Default.output({ chip: CHIP, line: DC }));
    const res = new GpioOutputPin(Default.output({ chip: CHIP, line: RES }));
    const busyIn = Default.input({ chip: CHIP, line: BUSY });
    const busy = new PollingInputPin(() => (busyIn.value ? 1 : 0), 1000);
    const connection = new DisplayConnection({ busNumber: 0, deviceNumber: 0, dc, res, busy });
    const epd = new IL0373Full(connection);
    try {
        await epd.ready();
        check('init_valid', true);
    } catch (e) {
        check('init_valid', false);
        console.log(`===DONE: ${passed} passed, ${failed} failed===`);
        process.exit(1);
    }
    epd.clear(WHITE);
    epd.setPixel(1, 1, BLACK);
    check('pixel_black_readback', epd.getPixel(1, 1) === BLACK);
    epd.setPixel(3, 1, WHITE);
    check('pixel_white_readback', epd.getPixel(3, 1) === WHITE);
    epd.drawRect(0, 0, 104, 212, BLACK);
    epd.drawLine(0, 0, 103, 211, BLACK);
    check('drawing_accepted', epd.getPixel(0, 0) === BLACK);
    try { await epd.show(); check('show_completes', true); } catch (e) { check('show_completes', false); }
    try { await epd.sleep(); check('sleep_accepted', true); } catch (e) { check('sleep_accepted', false); }
    connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed ? 1 : 0);
})();
