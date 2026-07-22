'use strict';

const { DHTxxTransport } = require('periph/src/transport/dhtxx');
const { DHT11Minimal }   = require('periph/src/chips/humidity/dht11');

const DATA_PIN = parseInt(process.env.DHT11_PIN || '4', 10);
const transport = new DHTxxTransport(DATA_PIN);
const dht = new DHT11Minimal(transport);               // Create DHT11 driver, (transport)

(async function() {
    for (let i = 0; i < 5; i++) {
        const r = dht.read();                          // Read temperature & humidity, () → {temperature: float °C, humidity: float %RH}
        console.log(`${r.temperature} C, ${r.humidity} %RH`);
        await new Promise(r => setTimeout(r, 2000));
    }
    transport.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
