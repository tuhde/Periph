'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { HS30xxMinimal } = require('periph/src/chips/humidity/hs30xx');

    function HS30xxNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x44;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.connection = connection;
            node.driver = new HS30xxMinimal(connection);
        } catch (e) {
            node.error('HS30xx init failed: ' + e.message);
        }

        node.on('input', async function(msg, send, done) {
            if (!node.driver) {
                node.error('HS30xx not initialised', msg);
                done();
                return;
            }
            try {
                msg.payload = await node.driver.read();
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('periph-hs30xx', HS30xxNode);
};
