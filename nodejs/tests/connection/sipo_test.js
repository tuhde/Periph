'use strict';

const { Default } = require('opengpio');
const { SiPoConnection } = require('../../packages/periph/src/connection/sipo');

const MODE = process.env.SIPO_MODE || 'sw';  // 'sw' (bit-bang) or 'hw' (spi-device)

const GPIO_CHIP  = parseInt(process.env.GPIO_CHIP    || '0',  10);
const RCK_LINE   = parseInt(process.env.SIPO_RCK     || '5',  10);
const SRCLR_LINE = parseInt(process.env.SIPO_SRCLR   || '6',  10);
const G_LINE     = parseInt(process.env.SIPO_G       || '13', 10);
const SER_IN_LINE = parseInt(process.env.SIPO_SER_IN || '19', 10);
const SRCK_LINE  = parseInt(process.env.SIPO_SRCK    || '26', 10);

const SPI_BUS    = parseInt(process.env.SIPO_SPI_BUS    || '0', 10);
const SPI_DEVICE = parseInt(process.env.SIPO_SPI_DEVICE || '0', 10);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const rck   = Default.output({ chip: GPIO_CHIP, line: RCK_LINE });
const srclr = Default.output({ chip: GPIO_CHIP, line: SRCLR_LINE });
const g     = Default.output({ chip: GPIO_CHIP, line: G_LINE });

let connection;
if (MODE === 'hw') {
    connection = new SiPoConnection(rck, {
        srclr, g, busNumber: SPI_BUS, deviceNumber: SPI_DEVICE,
    });
} else {
    const serIn = Default.output({ chip: GPIO_CHIP, line: SER_IN_LINE });
    const srck  = Default.output({ chip: GPIO_CHIP, line: SRCK_LINE });
    connection = new SiPoConnection(rck, { srclr, g, serIn, srck });
}

connection.write(Buffer.from([0xA5]));
checkTrue('write accepted', true);

connection.write(Buffer.from([0x00, 0xFF]));
checkTrue('write multi-byte accepted', true);

connection.clear();
checkTrue('clear accepted', true);

connection.setOutputEnable(false);
checkTrue('setOutputEnable(false) accepted', true);

connection.setOutputEnable(true);
checkTrue('setOutputEnable(true) accepted', true);

connection.close();
checkTrue('close accepted', true);

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
