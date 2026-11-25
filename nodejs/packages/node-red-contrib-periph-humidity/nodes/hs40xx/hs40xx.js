'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { HS40xxFull } = require('periph/src/chips/humidity/hs40xx');

    function HS40xxDeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x54;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.connection = connection;
            node.driver = new HS40xxFull(connection);
        } catch (e) {
            node.error('HS40xx init failed: ' + e.message);
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('hs40xx-device', HS40xxDeviceNode);

    function HS40xxReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) {
                node.error('HS40xx not initialised', msg);
                done();
                return;
            }
            try {
                if (!node.resolutionSet) {
                    const bits = parseInt(config.resolution) || 14;
                    if (bits !== 14) await deviceNode.driver.setResolution(bits, bits);
                    node.resolutionSet = true;
                }
                msg.payload = await deviceNode.driver.read();
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('hs40xx', HS40xxReadNode);
};
