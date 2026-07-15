'use strict';

const { DHT11Pin } = require('../../../src/transport/dht11');
const { DHT11Minimal } = require('../../../src/chips/humidity/dht11');

const pin = new DHT11Pin(4);                            // Create DHT11 pin adapter on GPIO 4, (pin) → DHT11Pin
const dht = new DHT11Minimal(pin);                       // Create DHT11 driver, (pin) → DHT11Minimal

const r = dht.read();                                    // Read temperature and humidity, () → {temperature_c, humidity_rh}
console.log(`T: ${r.temperature_c.toFixed(1)} C  H: ${r.humidity_rh.toFixed(1)} %RH`);

pin.close();
