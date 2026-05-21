'use strict';

const { UARTTransport } = require('../../packages/periph/src/transport/uart');

const UART_PORT = process.env.UART_PORT || '/dev/ttyS0';
const UART_BAUD = parseInt(process.env.UART_BAUD || '9600', 10);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

// Assumes a loopback jumper bridging TXD and RXD on the UART port under test.
async function main() {
    const transport = new UARTTransport(UART_PORT, { baudRate: UART_BAUD });
    await transport.open();

    await transport.write(Buffer.from([0x42]));
    checkTrue('write accepted', true);

    const rxByte = await transport.read(1);
    checkTrue('read returns 1 byte', rxByte.length === 1);
    checkTrue('loopback byte matches', rxByte[0] === 0x42);

    const resp = await transport.writeRead(Buffer.from([0xA5, 0x5A]), 2);
    checkTrue('writeRead returns 2 bytes', resp.length === 2);
    checkTrue('writeRead loopback matches', resp[0] === 0xA5 && resp[1] === 0x5A);

    await transport.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(err => { console.error(err); process.exit(1); });
