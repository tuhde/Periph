'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { ADS1014Full } = require('periph/src/chips/adc_dac/ads1014');

    function ADS1014Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;

        try {
            const address = parseInt(config.address, 16) || ADS1014Full.I2C_ADDRESS;
            const connection = new I2CConnection(parseInt(config.bus, 10), address);
            node.driver = new ADS1014Full(connection);
            node.connection = connection;
            node.ready = (async () => {
                await node.driver.setGain(parseInt(config.gain, 10));
                await node.driver.setDataRate(parseInt(config.dataRate, 10));
            })();
            node.ready.catch((e) => node.error('ADS1014 init failed: ' + e.message));
        } catch (e) {
            node.error('ADS1014 init failed: ' + e.message);
        }

        node.on('input', async function(msg, send, done) {
            if (!node.driver) { done(); return; }
            try {
                await node.ready;
                const raw = await node.driver.readRaw();
                msg.payload = { raw, voltage: raw * node.driver.lsb() };
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
    RED.nodes.registerType('periph-ads1014', ADS1014Node);
};
