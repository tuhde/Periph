'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { AM2320Full } = require('periph/src/chips/environmental/am2320');

    function AM2320DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x5C;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.connection = connection;
            node.driver = new AM2320Full(connection);
        } catch (e) {
            node.error('AM2320 init failed: ' + e.message);
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('am2320-device', AM2320DeviceNode);

    function AM2320ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        // Messages are handled one at a time; the driver itself waits the >= 2 s between transactions.
        let queue = Promise.resolve();
        node.on('input', function(msg, send, done) {
            if (!deviceNode.driver) {
                node.error('AM2320 not initialised', msg);
                done();
                return;
            }
            queue = queue.then(async () => {
                try {
                    msg.payload = await deviceNode.driver.read();
                    send(msg);
                    done();
                } catch (e) {
                    done(e);
                }
            });
        });
    }
    RED.nodes.registerType('am2320', AM2320ReadNode);
};
