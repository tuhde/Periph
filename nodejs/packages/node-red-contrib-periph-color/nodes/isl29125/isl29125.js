'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { ISL29125Full } = require('periph/src/chips/color/isl29125');

    function ISL29125DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            node.connection = new I2CConnection(parseInt(config.bus, 10), 0x44);
            node.driver = new ISL29125Full(node.connection);
            node.ready = (async () => {
                await node.driver.init();
                await node.driver.setRange(parseInt(config.range, 10) === 375 ? 375 : 10000);
                await node.driver.setResolution(parseInt(config.resolution, 10) === 12 ? 12 : 16);
            })();
            node.ready.then(() => node.status({ fill: 'green', shape: 'dot', text: 'I2C' }))
                .catch((e) => node.error('ISL29125 init failed: ' + e.message));
        } catch (e) {
            node.error('ISL29125 init failed: ' + e.message);
        }

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
    RED.nodes.registerType('isl29125-device', ISL29125DeviceNode);

    function ISL29125ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) { done(new Error('ISL29125 not initialised')); return; }
            try {
                await deviceNode.ready;
                const counts = await deviceNode.driver.read();
                msg.payload = counts;
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('isl29125', ISL29125ReadNode);
};
