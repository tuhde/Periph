'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const { InputPin, EpollInputPin, PollingInputPin } = require('../../packages/periph/src/connection/input_pin');

let passed = 0;
let failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

let threw = false;
try { new InputPin().isHigh(); } catch (e) { threw = true; }
checkTrue('abstract isHigh throws', threw);

let level = 0;
const poll = new PollingInputPin(() => level, 1000);
checkTrue('polling low', poll.isHigh() === false);
level = 1;
checkTrue('polling high', poll.isHigh() === true);

const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'pin-')), 'value');
fs.writeFileSync(file, '0\n');
const ep = new EpollInputPin(file);
checkTrue('epoll low', ep.isHigh() === false);
fs.writeFileSync(file, '1\n');
checkTrue('epoll high', ep.isHigh() === true);

console.log(`${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
