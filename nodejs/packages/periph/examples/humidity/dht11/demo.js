'use strict';

const { DHTxxTransport } = require('periph/src/transport/dhtxx');
const { DHT11Full }      = require('periph/src/chips/humidity/dht11');

const DATA_PIN = parseInt(process.env.DHT11_PIN || '4', 10);
const transport = new DHTxxTransport(DATA_PIN);
const dht = new DHT11Full(transport, 3);               // Create DHT11 driver, (transport, max_retries=3)

function comfort(h) {
    if (h < 30.0) return 'dry';
    if (h > 60.0) return 'humid';
    return 'comfortable';
}

// --- Indoor comfort monitor ---
// Reads temperature and humidity every 5 seconds and prints a one-line
// status with a comfort assessment. Demonstrates reliable real-world polling
// with retry-based error recovery.
(async function() {
    for (let n = 0; n < 60; n++) {
        let r;
        try {
            r = dht.readRetry(3);                      // Read with retries, (max_retries=3) → {temperature, humidity}
        } catch (e) {
            // --- Handle read failure ---
            // After all retries are exhausted, log a warning and continue.
            // The next loop iteration will try again with a fresh sample.
            console.log('WARN: DHT11 read failed after retries');
            await new Promise(r => setTimeout(r, 5000));
            continue;
        }
        console.log(`${r.temperature} C, ${r.humidity} %RH, ${comfort(r.humidity)}`);
        await new Promise(r => setTimeout(r, 5000));
    }
    transport.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
