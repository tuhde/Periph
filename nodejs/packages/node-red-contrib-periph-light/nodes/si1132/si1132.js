'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { Si1132Full } = require('periph/src/chips/light/si1132');

    function Si1132DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x60;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.connection = connection;
            node.driver = new Si1132Full(connection);
            node.ready = (async () => {
                await node.driver.init();
                const visible = config.highRangeVisible === true || config.highRangeVisible === 'true';
                const infrared = config.highRangeInfrared === true || config.highRangeInfrared === 'true';
                if (visible || infrared) await node.driver.setHighRange(visible, infrared);
            })();
            node.ready.catch((e) => {
                node.error('Si1132 init failed: ' + e.message);
            });
        } catch (e) {
            node.error('Si1132 init failed: ' + e.message);
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('si1132-device', Si1132DeviceNode);

    function Si1132ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) {
                node.error('Si1132 not initialised', msg);
                done();
                return;
            }
            try {
                await deviceNode.ready;
                const { uv_index, visible, infrared } = await deviceNode.driver.read();
                msg.payload = { uv_index, visible, infrared };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('si1132', Si1132ReadNode);
};
