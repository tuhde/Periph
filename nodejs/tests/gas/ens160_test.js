'use strict';
const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
const { ENS160Full } = require('../../packages/periph/src/chips/gas/ens160');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x52', 16);

let passed = 0, failed = 0;

function check(condition, name) {
    if (condition) { console.log('PASS ' + name); passed++; }
    else { console.log('FAIL ' + name); failed++; }
}

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);

const sensor = new ENS160Full(transport);
check(true, 'init');

const status = sensor.status();
check(status >= 0 && status <= 3, 'status_valid_range');

console.log('Waiting for warm-up (may take up to 3 minutes)...');
let warmup_ok = false;
for (let i = 0; i < 240; i++) {
    try {
        sensor.readAirQuality();
        warmup_ok = true;
        break;
    } catch (e) {
        _delay(1000);
    }
}
check(warmup_ok, 'warmup_complete');

if (warmup_ok) {
    const data = sensor.readAirQuality();
    check('aqi' in data && 'tvocPpb' in data && 'eco2Ppm' in data, 'read_air_quality_keys');
    check(data.aqi >= 1 && data.aqi <= 5, 'aqi_range');
    check(data.tvocPpb >= 0, 'tvoc_non_negative');
    check(data.eco2Ppm >= 400, 'eco2_minimum');
}

sensor.setCompensation(25.0, 50.0);
check(true, 'set_compensation');

const tvoc = sensor.readTvoc();
check(tvoc >= 0, 'read_tvoc');

const eco2 = sensor.readEco2();
check(eco2 >= 400, 'read_eco2');

const aqi = sensor.readAqi();
check(aqi >= 1 && aqi <= 5, 'read_aqi');

const actuals = sensor.readCompensationActuals();
check('tempCelsius' in actuals && 'rhPercent' in actuals, 'read_compensation_actuals');

const fw = sensor.getFirmwareVersion();
check('major' in fw && 'minor' in fw && 'release' in fw, 'get_firmware_version');

sensor.sleep();
check(true, 'sleep');
_delay(100);
sensor.wake();
check(true, 'wake');

transport.close();
console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
process.exit(failed === 0 ? 0 : 1);

function _delay(ms) {
    const start = Date.now();
    while (Date.now() - start < ms) { /* spin */ }
}
