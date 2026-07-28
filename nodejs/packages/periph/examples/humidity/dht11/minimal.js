'use strict';

const { DHTxxConnection } = require('periph/src/connection/dhtxx');
const { DHT11Minimal }   = require('periph/src/chips/humidity/dht11');

const DATA_PIN = parseInt(process.env.DHT11_PIN || '4', 10);
const connection = new DHTxxConnection(DATA_PIN);
const dht = new DHT11Minimal(connection);               // Create DHT11 driver, (connection)

(async function() {
    for (let i = 0; i < 5; i++) {
        const r = dht.read();                          // Read temperature & humidity, () → {temperature: float °C, humidity: float %RH}
        console.log(`${r.temperature} C, ${r.humidity} %RH`);
        await new Promise(r => setTimeout(r, 2000));
    }
    connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
