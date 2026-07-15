'use strict';

const { DHT11Pin } = require('../../../src/transport/dht11');
const { DHT11Full } = require('../../../src/chips/humidity/dht11');

const pin = new DHT11Pin(4);                            // Create DHT11 pin adapter on GPIO 4, (pin) → DHT11Pin
const dht = new DHT11Full(pin);                          // Create DHT11 driver, (pin) → DHT11Full

const r = dht.readRetry(3);                              // Read with retry, (maxRetries=3) → {temperature_c, humidity_rh}
                                                         // throws after 3 failed attempts
let raw;
try { raw = dht.readRaw(); }                             // Read raw 5-byte frame, () → number[5]
catch (_) { raw = null; }                                // [hum_int, hum_dec, temp_int, temp_dec, checksum]
console.log(`t=${r.temperature_c.toFixed(1)} h=${r.humidity_rh.toFixed(1)}` + (raw ? ` raw=[${raw.map(b => b.toString(16).padStart(2, '0')).join(' ')}]` : ''));

pin.close();
