'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { TSL2561Full } = require('periph/src/chips/light/tsl2561');

    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

    function TSL2561DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.autoRange = config.gain === 'auto';
        try {
            const address = parseInt(config.address || '0x39', 16);
            node.connection = new I2CConnection(parseInt(config.bus, 10), address);
            node.driver = new TSL2561Full(node.connection);
            node.ready = (async () => {
                await node.driver.init();
                if (!node.autoRange) {
                    await node.driver.setGain(parseInt(config.gain, 10) === 16 ? 16 : 1);
                    await node.driver.setIntegrationTime(parseInt(config.integration, 10) || 402);
                }
                node.driver.setPackage(config.package || 'T');
            })();
            node.ready.then(() => node.status({ fill: 'green', shape: 'dot', text: 'I2C' }))
                .catch((e) => node.error('TSL2561 init failed: ' + e.message));
        } catch (e) {
            node.error('TSL2561 init failed: ' + e.message);
        }

        node.on('close', async function(removed, done) {
            try {
                if (node.connection) await node.connection.close();
            } catch (e) {
                // bus may already be gone; nothing more to do on close
            } finally {
                done();
            }
        });
    }
    RED.nodes.registerType('tsl2561-device', TSL2561DeviceNode);

    function TSL2561ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) { done(new Error('TSL2561 not initialised')); return; }
            try {
                await deviceNode.ready;
                const driver = deviceNode.driver;
                let lux;
                if (deviceNode.autoRange) {
                    lux = await driver.autoRange();
                } else {
                    // Wait for a full integration so the reading is fresh.
                    await sleep(driver.getIntegrationTime() * 1.1 + 5);
                }
                const r = await driver.readLuxRaw();
                msg.payload = { lux: deviceNode.autoRange ? lux : r.lux, ch0: r.ch0, ch1: r.ch1 };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('tsl2561', TSL2561ReadNode);
};
