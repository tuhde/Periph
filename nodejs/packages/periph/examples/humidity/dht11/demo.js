'use strict';

const { DHT11Pin } = require('../../../src/transport/dht11');
const { DHT11Full } = require('../../../src/chips/humidity/dht11');

const pin = new DHT11Pin(4);                            // Create DHT11 pin adapter on GPIO 4, (pin) → DHT11Pin
const dht = new DHT11Full(pin);                          // Create DHT11 driver, (pin) → DHT11Full

// --- Indoor comfort monitor ---
// Poll the sensor every 5 seconds and print a one-line status with a comfort
// assessment. readRetry() recovers from occasional checksum errors caused by
// timing jitter on Linux userspace bit-bang.
function poll() {
    try {
        const r = dht.readRetry(3);                      // Read with retry, (maxRetries=3) → {temperature_c, humidity_rh}
        const t = r.temperature_c;
        const h = r.humidity_rh;

        // --- Classify comfort zone ---
        let comfort;
        if      (h < 30.0) comfort = 'dry';
        else if (h <= 60.0) comfort = 'comfortable';
        else                comfort = 'humid';

        console.log(`T=${t.toFixed(1)} C  H=${h.toFixed(1)} %RH  (${comfort})`);
    } catch (e) {
        console.log('read failed after 3 attempts:', e.message);
    }
    setTimeout(poll, 5000);
}

poll();
