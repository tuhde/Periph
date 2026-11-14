'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { TMP102Full } = require('periph/src/chips/temperature/tmp102');

    function TMP102Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;

        try {
            const address = parseInt(config.address, 16) || TMP102Full.I2C_ADDRESS;
            const connection = new I2CConnection(parseInt(config.bus, 10), address);
            node.driver = new TMP102Full(connection);
            node.connection = connection;

            const rate = parseFloat(config.conversionRate);
            node.driver.setConversionRate(isNaN(rate) ? 4 : rate)
                .catch((e) => node.error('TMP102 init failed: ' + e.message));
        } catch (e) {
            node.error('TMP102 init failed: ' + e.message);
        }

        node.on('input', async function(msg, send, done) {
            if (!node.driver) { done(); return; }
            try {
                msg.payload = { temperature: await node.driver.readTemperature() };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });

        node.on('close', async function(removed, done) {
            try {
                if (node.connection) await node.connection.close();
            } catch (e) {
                // bus may already be gone; nothing more to do on close
            } finally {
                done();
            }
        });
    }
    RED.nodes.registerType('periph-tmp102', TMP102Node);
};
