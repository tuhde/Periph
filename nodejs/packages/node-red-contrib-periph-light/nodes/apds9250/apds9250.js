'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { APDS9250Full } = require('periph/src/chips/light/apds9250');

    function APDS9250DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            node.connection = new I2CConnection(parseInt(config.bus, 10), 0x52);
            node.driver = new APDS9250Full(node.connection);
            node.ready = (async () => {
                await node.driver.init();
                await node.driver.setResolution(parseInt(config.resolution, 10) || 18);
                await node.driver.setMeasurementRate(parseInt(config.rate, 10) || 100);
                await node.driver.setGain(parseInt(config.gain, 10) || 3);
            })();
            node.ready.then(() => node.status({ fill: 'green', shape: 'dot', text: 'I2C' }))
                .catch((e) => node.error('APDS9250 init failed: ' + e.message));
        } catch (e) {
            node.error('APDS9250 init failed: ' + e.message);
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
    RED.nodes.registerType('apds9250-device', APDS9250DeviceNode);

    function APDS9250ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) { done(new Error('APDS9250 not initialised')); return; }
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
    RED.nodes.registerType('apds9250', APDS9250ReadNode);
};
