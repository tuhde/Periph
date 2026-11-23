'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { ADS1014Full } = require('periph/src/chips/adc_dac/ads1014');

    function ADS1014DeviceNode(config) {
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
    RED.nodes.registerType('ads1014-device', ADS1014DeviceNode);

    function ADS1014ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) { done(); return; }
            try {
                await deviceNode.ready;
                const raw = await deviceNode.driver.readRaw();
                msg.payload = { raw, voltage: raw * deviceNode.driver.lsb() };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('ads1014', ADS1014ReadNode);
};
