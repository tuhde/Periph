'use strict';

const { DHTxxTransport, TransportError } = require('../../src/transport/dhtxx');
const { DHT11Full } = require('../../src/chips/humidity/dht11');

const DATA_PIN = parseInt(process.env.DATA_PIN || '4', 10);

const transport = new DHTxxTransport(new Gpio(DATA_PIN, 'out'));
const dht = new DHT11Full(transport);

setInterval(() => {
  const temp = dht.readTemperature();
  const hum = dht.readHumidity();
  console.log(`Temperature: ${temp.toFixed(1)} C, Humidity: ${hum.toFixed(1)} %RH`);

  const raw = dht.readRaw();
  console.log('Raw: ', raw.toString('hex'));
}, 2000);
