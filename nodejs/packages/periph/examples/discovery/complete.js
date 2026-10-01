'use strict';
const { scan, scanDetailed, discover } = require('../../src/discovery/discovery');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);

(async () => {
    const addresses = await scan(I2C_BUS);             // Scan bus, (bus=1, first=0x08, last=0x77) → number[] 7-bit addresses
                                                       // zero-length write per address, read byte on 0x30-0x37 / 0x50-0x5F; EBUSY counts as present
    console.log(addresses.map(a => '0x' + a.toString(16)));

    const detail = await scanDetailed(I2C_BUS);        // Scan with kernel-binding info, (bus=1, first=0x08, last=0x77) → Map<number, boolean>
                                                       // value is true when a kernel driver owns the address (i2cdetect "UU")
    console.log([...detail.entries()]);

    let devices = await discover(I2C_BUS);             // Discover chips, (bus=1, {active=false, registry}) → DiscoveredDevice[]
                                                       // identity reads confirm a chip only when exactly one candidate matches
    for (const dev of devices) {
        console.log(dev.address, dev.candidates, dev.identified, dev.driver, dev.inUseByKernel, dev.probeSkippedReason, dev.aliases);
    }

    devices = await discover(I2C_BUS, { active: true }); // Discover chips incl. write-sensitive addresses, (bus=1, {active=true}) → DiscoveredDevice[]
                                                       // also probes addresses shared with PCF8574/PCF8591/MCP4725-style chips; may change their outputs
    console.log(devices.length);
    console.log('===DONE: 0 passed, 0 failed===');
})();
