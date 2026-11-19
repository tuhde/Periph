'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { AS7262Full } = require('periph/src/chips/color/as7262');

    function AS7262Node(config) {
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

        node.on('input', async function(msg, send, done) {
            if (!node.driver) {
                node.error('AS7262 not initialised', msg);
                done();
                return;
            }
            try {
                await node.ready;
                const payload = await node.driver.read();
                payload.temperature = await node.driver.temperature();
                msg.payload = payload;
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
    RED.nodes.registerType('periph-as7262', AS7262Node);
};
