'use strict';

const { DHTxxTransport } = require('../../src/transport/dhtxx');
const { DHT11Full } = require('../../src/chips/humidity/dht11');

const DATA_PIN = parseInt(process.env.DATA_PIN || '4', 10);

const transport = new DHTxxTransport(new Gpio(DATA_PIN, 'out'));
const dht = new DHT11Full(transport);

setInterval(() => {
  let temp, hum;
  try {
    [temp, hum] = dht.readRetry(3);
  } catch (e) {
    console.warn('Read failed after retries');
    return;
  }

  let comfort;
  if (hum < 30) {
    comfort = 'dry';
  } else if (hum <= 60) {
    comfort = 'comfortable';
  } else {
    comfort = 'humid';
  }

  console.log(`Temperature: ${temp.toFixed(1)} C, Humidity: ${hum.toFixed(1)} %RH -- ${comfort}`);
}, 5000);
