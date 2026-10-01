'use strict';
const { discover } = require('../../src/discovery/discovery');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);

(async () => {
    // --- Take inventory of an unknown bench setup ---
    // Scan /dev/i2c-1 and name everything that answers. A chip is only named when
    // its identity register matches exactly one registry entry; shared addresses
    // without an ID register stay as candidate lists.
    const devices = await discover(I2C_BUS);           // Discover chips, (bus=1, {active=false}) → DiscoveredDevice[]

    // --- Report what was found ---
    // identified: confirmed chip; candidates: could be any of these; [] means the
    // address answers but the registry does not know it.
    for (const dev of devices) {
        const hex = '0x' + dev.address.toString(16).padStart(2, '0');
        if (dev.identified) console.log(`${hex}  ${dev.identified} (driver: ${dev.driver})`);
        else if (dev.inUseByKernel) console.log(`${hex}  claimed by a kernel driver, could be ${dev.candidates}`);
        else if (dev.candidates.length) console.log(`${hex}  one of ${dev.candidates}`);
        else console.log(`${hex}  unknown device`);
    }

    // --- Flag addresses we deliberately did not probe ---
    // Some candidates (port expanders, DACs) treat a stray write as data, so the
    // identity probe is skipped unless discover(bus, { active: true }) is used.
    const skipped = devices.filter(d => d.probeSkippedReason === 'write_sensitive_candidate');
    console.log(`${skipped.length} address(es) not probed because a candidate is write-sensitive`);
    console.log('===DONE: 0 passed, 0 failed===');
})();
