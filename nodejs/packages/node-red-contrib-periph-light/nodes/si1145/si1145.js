'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { Si1145Full } = require('periph/src/chips/light/si1145');

    function Si1145DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x60;
            const level = parseInt(config.ledLevel, 10);
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.connection = connection;
            node.driver = new Si1145Full(connection);
            node.ready = node.driver.init().then(() => {
                if (Number.isInteger(level) && level !== 3) return node.driver.setLedCurrent(1, level);
            });
            node.ready.catch((e) => {
                node.error('Si1145 init failed: ' + e.message);
            });
        } catch (e) {
            node.error('Si1145 init failed: ' + e.message);
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('si1145-device', Si1145DeviceNode);

    function Si1145ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) {
                node.error('Si1145 not initialised', msg);
                done();
                return;
            }
            try {
                await deviceNode.ready;
                const { proximity, uv_index, visible, infrared } = await deviceNode.driver.read();
                msg.payload = { proximity, uv_index, visible, infrared };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('si1145', Si1145ReadNode);
};
