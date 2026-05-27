'use strict';
const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
const { ENS160Minimal, ENS160Full } = require('../../packages/periph/src/chips/gas/ens160');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x52', 16);

let passed = 0, failed = 0;

function check(condition, name) {
    if (condition) { console.log('PASS ' + name); passed++; }
    else { console.log('FAIL ' + name); failed++; }
}

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);

const sensor = new ENS160Minimal(transport);
check(true, 'init');

const status = sensor.status();
check(status >= 0 && status <= 3, 'status_valid_range');

console.log('Waiting for warm-up (may take up to 3 minutes)...');
let timeout = 180;
while (sensor.status() !== 0 && timeout > 0) {
    _delay(1000);
    timeout--;
}
if (sensor.status() === 0) {
    check(true, 'warmup_complete');
} else {
    console.log('FAIL warmup_timeout');
    failed++;
}

const data = sensor.readAirQuality();
check('aqi' in data && 'tvocPpb' in data && 'eco2Ppm' in data, 'read_air_quality_keys');
check(data.aqi >= 1 && data.aqi <= 5, 'aqi_range');
check(data.tvocPpb >= 0, 'tvoc_non_negative');
check(data.eco2Ppm >= 400, 'eco2_minimum');

const sensorFull = new ENS160Full(transport);
check(true, 'full_init');

sensorFull.setCompensation(25.0, 50.0);
check(true, 'set_compensation');

const tvoc = sensorFull.readTvoc();
check(tvoc >= 0, 'read_tvoc');

const eco2 = sensorFull.readEco2();
check(eco2 >= 400, 'read_eco2');

const aqi = sensorFull.readAqi();
check(aqi >= 1 && aqi <= 5, 'read_aqi');

const actuals = sensorFull.readCompensationActuals();
check('tempCelsius' in actuals && 'rhPercent' in actuals, 'read_compensation_actuals');

const fw = sensorFull.getFirmwareVersion();
check('major' in fw && 'minor' in fw && 'release' in fw, 'get_firmware_version');

sensorFull.sleep();
check(true, 'sleep');
_delay(100);
sensorFull.wake();
check(true, 'wake');

transport.close();
console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
process.exit(failed === 0 ? 0 : 1);

function _delay(ms) {
    const start = Date.now();
    while (Date.now() - start < ms) { /* spin */ }
}
