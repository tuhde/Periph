'use strict';

const { UARTTransport } = require('../../packages/periph/src/transport/uart');
const { NEO6Minimal } = require('../../packages/periph/src/chips/gnss/neo6');

const UART_PORT = process.env.UART_PORT || '/dev/ttyS0';
const UART_BAUD = parseInt(process.env.UART_BAUD || '9600', 10);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

// Requires a NEO-6 module wired to UART with a clear sky view. Achieving an
// actual fix needs an outdoor antenna and can take up to ~26 s (cold start);
// this test only requires that well-typed values come back, not a fix.
async function main() {
    const transport = new UARTTransport(UART_PORT, { baudRate: UART_BAUD });
    await transport.open();
    const gps = new NEO6Minimal(transport);

    checkTrue('fix() starts at 0', gps.fix() === 0);
    checkTrue('latitude() starts at null', gps.latitude() === null);

    for (let i = 0; i < 3000; i++) {
        await gps.update();
    }

    checkTrue('fix() is a valid quality code', [0, 1, 2].includes(gps.fix()));
    checkTrue('satellites() is a non-negative int', gps.satellites() >= 0);
    if (gps.fix() > 0) {
        checkTrue('latitude() in range once fixed', gps.latitude() >= -90 && gps.latitude() <= 90);
        checkTrue('longitude() in range once fixed', gps.longitude() >= -180 && gps.longitude() <= 180);
        checkTrue('altitude() is populated once fixed', gps.altitude() !== null);
    } else {
        console.log('note: no fix acquired during the test window (needs sky view)');
    }

    await transport.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(err => { console.error(err); process.exit(1); });
