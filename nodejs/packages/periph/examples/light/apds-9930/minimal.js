// Minimal example for the APDS-9930 ambient light + proximity sensor.

const { APDS9930Minimal } = require('periph/src/chips/light/apds-9930');
const { I2CConnection }   = require('periph/src/connection/i2c_auto');

async function main() {
    const connection = new I2CConnection(0x39);                       // Create I2C connection, (addr=0x39, bus=undefined) → I2CConnection
    const apds = new APDS9930Minimal(connection);                     // Construct APDS-9930 Minimal, (connection) → APDS9930Minimal
                                                                     // initialises with ATIME=0xDB, PTIME=0xFF, PPULSE=8, CONTROL=0x20
    await new Promise(r => setTimeout(r, 110));
    setInterval(async () => {
        const lx = await apds.lux();                                 // Read ambient illuminance, () → Promise<number> lx
                                                                     // IR-compensated lux via Ch0/Ch1 difference
        const p  = await apds.proximity();                            // Read proximity count, () → Promise<number> count
                                                                     // 16-bit ADC value; higher = closer object
        console.log(`lux=${lx.toFixed(1)}  proximity=${p}`);
    }, 1000);
}

main().catch(err => { console.error(err); process.exit(1); });