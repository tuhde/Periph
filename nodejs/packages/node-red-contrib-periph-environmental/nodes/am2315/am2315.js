'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { AM2315Full } = require('periph/src/chips/environmental/am2315');

    function AM2315Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x5C;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.connection = connection;
            node.driver = new AM2315Full(connection);
        } catch (e) {
            node.error('AM2315 init failed: ' + e.message);
        }

        // Messages are handled one at a time; the driver itself waits the >= 2 s between transactions.
        let queue = Promise.resolve();
        node.on('input', function(msg, send, done) {
            if (!node.driver) {
                node.error('AM2315 not initialised', msg);
                done();
                return;
            }
            queue = queue.then(async () => {
                try {
                    msg.payload = await node.driver.read();
                    send(msg);
                    done();
                } catch (e) {
                    done(e);
                }
            });
        });

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('periph-am2315', AM2315Node);
};
