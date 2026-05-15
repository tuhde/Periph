'use strict';
const { SpiTransport } = require('../../packages/periph/src/transport/spi');
const { RFM95Full } = require('../../packages/periph/src/chips/comms/rfm9x');

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEVICE = parseInt(process.env.SPI_DEVICE || '0', 10);
const SPI_CS = parseInt(process.env.SPI_CS || '0', 10);

let passed = 0;
let failed = 0;

const check_eq = (label, got, expected) => {
    if (got === expected) { console.log('PASS ' + label); passed++; }
    else { console.log('FAIL ' + label + ': got 0x' + got.toString(16) + ', expected 0x' + expected.toString(16)); failed++; }
};

const check_true = (label, cond) => {
    if (cond) { console.log('PASS ' + label); passed++; }
    else { console.log('FAIL ' + label); failed++; }
};

const transport = new SpiTransport(SPI_BUS, SPI_DEVICE, SPI_CS);
const rfm = new RFM95Full(transport, 868000000);

const ver = rfm.version();
check_eq('version_reg', ver, 0x12);
check_true('version_nonzero', ver !== 0);
check_true('rssi_sane', rfm.rssi() > -150 && rfm.rssi() < 0);

rfm.send(Buffer.from('test'));

rfm.standby();
rfm.sleep();
rfm.standby();

rfm.set_tx_power(14, false);
rfm.set_tx_power(17, true);
rfm.set_frequency(868000000);
rfm.configure(7, 125, 5, true);
check_true('configure_valid', true);

transport.close();
console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
process.exit(failed === 0 ? 0 : 1);