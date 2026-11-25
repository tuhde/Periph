'use strict';

// Minimal example: read both differential inputs.

const { I2CConnection } = require('periph/src/connection/i2c');
const { MCP3422Minimal } = require('periph/src/chips/adc_dac/mcp3422');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, MCP3422Minimal.I2C_ADDRESS);
    const adc = new MCP3422Minimal(connection);                             // Create MCP3422 driver, (connection)
    await adc.init();                                                       // Write the default configuration, () → void (one-shot, 16 bit, x1, RDY = 0)

    for (let i = 0; i < 10; i++) {
        const raw = await adc.readRaw(0);                                   // Read a signed code, (channel 0–1) → int
        const volts = await adc.readVoltage(1);                             // Read the differential voltage, (channel 0–1) → float V
        console.log('CH1 raw', raw, 'CH2', volts.toFixed(6), 'V');
        console.log(await adc.readAll());                                   // Read both channels, () → [float V, float V]
        await sleep(1000);
    }

    await connection.close();
})();
