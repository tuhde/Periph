'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { RDA5807MFull } = require('../../../src/chips/comms/rda5807m');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x10', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const fm = new RDA5807MFull(transport, 87.5, 10);

function sleep(ms) {
    const end = Date.now() + ms;
    while (Date.now() < end) {}
}

// --- FM band scanner ---
// Start at the bottom of the world-wide band and repeatedly seek upward with
// SKMODE=1 (stop at band limit, the Minimal/Full default) so a seek that
// returns null means the top of the band has been reached and the scan is done.
fm.enableRds(true);

const stations = [];
while (true) {
    const freq = fm.seek(true);
    if (freq === null) break;
    if (!fm.isStation()) continue;

    const rssi = fm.signalStrength();
    const stereo = fm.isStereo() ? 'stereo' : 'mono';
    let name = null;

    // --- Try to read the Program Service (station) name via RDS ---
    // Group types 0A/0B carry the 8-character PS name, four segments of two
    // characters each, addressed by block B bits 1:0. Give the decoder up to
    // 2 seconds to assemble a full name before moving on to the next station.
    const psChars = new Array(8).fill(null);
    const deadline = Date.now() + 2000;
    while (Date.now() < deadline) {
        if (fm.rdsReady()) {
            const group = fm.readRdsGroup();
            if (group !== null) {
                const [, blockB, , blockD] = group;
                const groupType = blockB >> 12;
                const isBVariant = (blockB >> 11) & 1;
                if (groupType === 0 && isBVariant === 0) {
                    const segment = blockB & 0x03;
                    psChars[segment * 2] = String.fromCharCode(blockD >> 8);
                    psChars[segment * 2 + 1] = String.fromCharCode(blockD & 0xFF);
                    if (!psChars.includes(null)) {
                        name = psChars.join('');
                        break;
                    }
                }
            }
        }
        sleep(40);
    }

    const label = name ? name.trim() : '(no RDS name)';
    console.log(`${freq.toFixed(2)} MHz  RSSI=${rssi}  ${stereo}  ${label}`);
    stations.push({ freq, rssi, stereo, label });
}

console.log();
console.log(`Scan complete: ${stations.length} station(s) found`);
