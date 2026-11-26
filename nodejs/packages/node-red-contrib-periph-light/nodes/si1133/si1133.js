'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { Si1133Full } = require('periph/src/chips/light/si1133');

    function Si1133DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x55;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.connection = connection;
            node.driver = new Si1133Full(connection);
            node.ready = node.driver.init();
            node.ready.catch((e) => {
                node.error('Si1133 init failed: ' + e.message);
            });
        } catch (e) {
            node.error('Si1133 init failed: ' + e.message);
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('si1133-device', Si1133DeviceNode);

    function Si1133ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) {
                node.error('Si1133 not initialised', msg);
                done();
                return;
            }
            try {
                await deviceNode.ready;
                const { uv, white } = await deviceNode.driver.read();
                msg.payload = { uv, white };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('si1133', Si1133ReadNode);
};
