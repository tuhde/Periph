'use strict';

const { DHTxxTransport } = require('periph/src/transport/dhtxx');
const { DHT11Full }      = require('periph/src/chips/humidity/dht11');

const DATA_PIN = parseInt(process.env.DHT11_PIN || '4', 10);
const transport = new DHTxxTransport(DATA_PIN);
const dht = new DHT11Full(transport, 3);               // Create DHT11 driver, (transport, max_retries=3)

(async function() {
    const t = dht.readTemperature();                   // Read temperature, () → float °C
                                                       // returns a fresh conversion each call
    const h = dht.readHumidity();                      // Read humidity, () → float %RH
                                                       // returns a fresh conversion each call
    const r = dht.readRetry(5);                        // Read with retries, (max_retries=5) → {temperature, humidity}
                                                       // retries up to 5 times on checksum error
    const raw = dht.readRaw();                         // Read raw frame, () → Buffer
                                                       // returns the validated 5-byte frame
    console.log(`t=${t} h=${h} retry_t=${r.temperature} raw[0]=0x${raw[0].toString(16).padStart(2,'0').toUpperCase()}`);
    transport.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
