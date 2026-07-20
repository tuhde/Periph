'use strict';
const { I2CTransport } = require('../../../packages/periph/src/transport/i2c');
const { ENS160Full } = require('../../../packages/periph/src/chips/gas/ens160');

const AQI_LABELS = {1: 'Excellent', 2: 'Good', 3: 'Moderate', 4: 'Poor', 5: 'Unhealthy'};

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x52', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const sensor = new ENS160Full(transport);                // Create ENS160 driver, (transport)

// --- Wait for sensor warm-up ---
// The ENS160 requires ~3 minutes after power-on or idle before VALIDITY_FLAG
// reaches 0. During warm-up, readings are unreliable. The driver surfaces the
// status so the application can display progress to the user.
console.log('Waiting for sensor warm-up...');
while (true) {                                           // Wait for valid data, () → blocks until warm
    try { sensor.readAirQuality(); break; } catch (e) {
        const s = sensor.status();
        if (s === 1) console.log('Warm-up in progress...');
        else if (s === 2) console.log('Initial start-up (first power-on, up to 1 hour)...');
        else console.log('No valid output');
        _delay(1000);
    }
}
console.log('Sensor ready!');

// --- Set compensation from external sensor ---
// If an external temperature/humidity sensor is available, feeding its readings
// to the ENS160 improves accuracy outside the 20-80%RH range. Here we use a
// fixed 22C/45%RH as an example.
sensor.setCompensation(22.0, 45.0);                      // Set compensation, (tempCelsius=22.0, rhPercent=45.0) → undefined

// --- Indoor air quality monitoring loop ---
// Reads AQI, TVOC, and eCO2 every second and prints a human-readable label.
// AQI 1-2 is acceptable for occupied spaces; AQI 3+ suggests ventilation.
for (let n = 0; n < 60; n++) {
    const data = sensor.readAirQuality();                // Read air quality, () → object {aqi, tvocPpb, eco2Ppm}
    const aqi = data.aqi;
    const label = AQI_LABELS[aqi] || 'Unknown';
    console.log(`${n}s: AQI=${aqi} (${label}) TVOC=${data.tvocPpb} ppb eCO2=${data.eco2Ppm} ppm`);
    _delay(1000);
}

transport.close();
console.log('===DONE: 0 passed, 0 failed===');

function _delay(ms) {
    const start = Date.now();
    while (Date.now() - start < ms) { /* spin */ }
}
