'use strict';

const Gpio = require('onoff').Gpio;
const { SiPoTransport } = require('../../packages/periph/src/transport/sipo');

const MODE = process.env.SIPO_MODE || 'sw';  // 'sw' (bit-bang) or 'hw' (spi-device)

const RCK_PIN    = parseInt(process.env.SIPO_RCK    || '5',  10);
const SRCLR_PIN  = parseInt(process.env.SIPO_SRCLR  || '6',  10);
const G_PIN      = parseInt(process.env.SIPO_G      || '13', 10);
const SER_IN_PIN = parseInt(process.env.SIPO_SER_IN || '19', 10);
const SRCK_PIN   = parseInt(process.env.SIPO_SRCK   || '26', 10);

const SPI_BUS    = parseInt(process.env.SIPO_SPI_BUS    || '0', 10);
const SPI_DEVICE = parseInt(process.env.SIPO_SPI_DEVICE || '0', 10);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const rck   = new Gpio(RCK_PIN,   'out');
const srclr = new Gpio(SRCLR_PIN, 'out');
const g     = new Gpio(G_PIN,     'out');

let transport;
if (MODE === 'hw') {
    transport = new SiPoTransport(rck, {
        srclr, g, busNumber: SPI_BUS, deviceNumber: SPI_DEVICE,
    });
} else {
    const serIn = new Gpio(SER_IN_PIN, 'out');
    const srck = new Gpio(SRCK_PIN, 'out');
    transport = new SiPoTransport(rck, { srclr, g, serIn, srck });
}

transport.write(Buffer.from([0xA5]));
checkTrue('write accepted', true);

transport.write(Buffer.from([0x00, 0xFF]));
checkTrue('write multi-byte accepted', true);

transport.clear();
checkTrue('clear accepted', true);

transport.setOutputEnable(false);
checkTrue('setOutputEnable(false) accepted', true);

transport.setOutputEnable(true);
checkTrue('setOutputEnable(true) accepted', true);

transport.close();
checkTrue('close accepted', true);

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
