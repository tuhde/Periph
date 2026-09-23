'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { MCP9808Full } = require('periph/src/chips/temperature/mcp9808');

    const SOURCE_NAMES = [
        [MCP9808Full.SOURCE_LOWER, 'lower'],
        [MCP9808Full.SOURCE_UPPER, 'upper'],
        [MCP9808Full.SOURCE_CRITICAL, 'critical'],
    ];

    function MCP9808Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;

        try {
            const address = parseInt(config.address, 16) || MCP9808Full.I2C_ADDRESS;
            const connection = new I2CConnection(parseInt(config.bus, 10), address);
            node.driver = new MCP9808Full(connection);
            node.connection = connection;

            (async () => {
                await node.driver.init();
                await node.driver.setResolution(parseFloat(config.resolution) || 0.0625);
                if (config.alertEnabled) {
                    await node.driver.setUpperLimit(parseFloat(config.upperLimit));
                    await node.driver.setLowerLimit(parseFloat(config.lowerLimit));
                    await node.driver.setCriticalLimit(parseFloat(config.criticalLimit));
                    await node.driver.setHysteresis(parseFloat(config.hysteresis) || 0);
                    await node.driver.configureAlert(config.alertMode || 'all',
                        config.alertOutput || 'comparator', 'active_low');
                    await node.driver.enableAlert();
                    await node.driver.onInterrupt((status) => {
                        for (const [bit, name] of SOURCE_NAMES) {
                            if (status & bit) node.send({ payload: { source: name } });
                        }
                    });
                }
            })().catch((e) => node.error('MCP9808 init failed: ' + e.message));
        } catch (e) {
            node.error('MCP9808 init failed: ' + e.message);
        }

        node.on('input', async function(msg, send, done) {
            if (!node.driver) { done(); return; }
            try {
                const p = msg.payload;
                if (p && typeof p === 'object') {
                    if (p.shutdown === true) { await node.driver.shutdown(); done(); return; }
                    if (p.shutdown === false) { await node.driver.wake(); done(); return; }
                    if (p.clear_interrupt === true) { await node.driver.clearInterrupt(); done(); return; }
                }
                msg.payload = {
                    temperature_c: await node.driver.readTemperature(),
                    alert_asserted: await node.driver.isAlertAsserted(),
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });

        node.on('close', async function(removed, done) {
            try {
                if (node.driver) await node.driver.offInterrupt();
                if (node.connection) await node.connection.close();
            } catch (e) {
                // bus may already be gone; nothing more to do on close
            } finally {
                done();
            }
        });
    }
    RED.nodes.registerType('periph-mcp9808', MCP9808Node);
};
