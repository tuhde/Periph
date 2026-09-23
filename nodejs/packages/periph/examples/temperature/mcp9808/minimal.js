'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { MCP9808Minimal } = require('periph/src/chips/temperature/mcp9808');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, MCP9808Minimal.I2C_ADDRESS);
    const sensor = new MCP9808Minimal(connection);                                 // Create MCP9808 driver, (connection)
    await sensor.init();                                                           // Confirm device identity, () → None

    for (let i = 0; i < 10; i++) {
        const t = await sensor.readTemperature();                                  // Read ambient temperature, () → number °C
        console.log(`${t.toFixed(4)} °C`);
        await sleep(1000);
    }

    await connection.close();
})();
