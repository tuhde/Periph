'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { AS7262Full } = require('periph/src/chips/color/as7262');

    function AS7262DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.ready = null;
        try {
            const address = parseInt(config.address, 16) || 0x49;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.connection = connection;
            node.driver = new AS7262Full(connection);
            node.ready = (async () => {
                await node.driver.init();
                await node.driver.setGain(parseFloat(config.gain) || 16);
                await node.driver.setIntegrationTime(parseFloat(config.integrationTime) || 165.2);
            })();
            node.ready.catch((e) => node.error('AS7262 init failed: ' + e.message));
        } catch (e) {
            node.error('AS7262 init failed: ' + e.message);
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('as7262-device', AS7262DeviceNode);

    function AS7262ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) {
                node.error('AS7262 not initialised', msg);
                done();
                return;
            }
            try {
                await deviceNode.ready;
                const payload = await deviceNode.driver.read();
                payload.temperature = await deviceNode.driver.temperature();
                msg.payload = payload;
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('as7262', AS7262ReadNode);
};
