'use strict';

const { DHTxxTransport } = require('../../packages/periph/src/transport/dhtxx');
const { DHT11Full } = require('../../packages/periph/src/chips/humidity/dht11');

const DATA_PIN = parseInt(process.env.DATA_PIN || '4', 10);

let passed = 0;
let failed = 0;

function checkTrue(cond, label) {
  if (cond) {
    console.log(`PASS ${label}`);
    passed++;
  } else {
    console.log(`FAIL ${label}`);
    failed++;
  }
}

const transport = new DHTxxTransport(new Gpio(DATA_PIN, 'out'));
const dht = new DHT11Full(transport);

try {
  const raw = dht.readRaw();
  const sum = (raw[0] + raw[1] + raw[2] + raw[3]) & 0xFF;
  checkTrue(sum === raw[4], 'checksum');
} catch (e) {
  console.log(`FAIL checksum: ${e.message}`);
  failed++;
}

const [temp, hum] = dht.read();
checkTrue(temp > -40 && temp < 80, 'temperature_range');
checkTrue(hum >= 0 && hum <= 100, 'humidity_range');

transport.close();
console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
