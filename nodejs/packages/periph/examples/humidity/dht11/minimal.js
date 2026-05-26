'use strict';

const { DHTxxTransport } = require('../../src/transport/dhtxx');
const { DHT11Minimal } = require('../../src/chips/humidity/dht11');

const DATA_PIN = parseInt(process.env.DATA_PIN || '4', 10);

const transport = new DHTxxTransport(new Gpio(DATA_PIN, 'out'));
const dht = new DHT11Minimal(transport);

setInterval(() => {
  try {
    const [temp, hum] = dht.read();
    console.log(`Temperature: ${temp.toFixed(1)} C, Humidity: ${hum.toFixed(1)} %RH`);
  } catch (e) {
    console.error('Read error:', e.message);
  }
}, 2000);
