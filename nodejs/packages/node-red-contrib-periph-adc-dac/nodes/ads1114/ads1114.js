'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { ADS1114Full }       = require('periph/src/chips/adc_dac/ads1114');

    function ADS1114DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new ADS1114Full(connection);
            node.connection = connection;
            node.ready = (async () => {
                await node.driver.setGain(parseInt(config.gain));
                await node.driver.setDataRate(parseInt(config.dataRate));
            })();
        } catch (e) {
            node.error('ADS1114 init failed: ' + e.message);
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('ads1114-device', ADS1114DeviceNode);

    function ADS1114ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) { done(); return; }
            try {
                await deviceNode.ready;
                const driver = deviceNode.driver;
                const raw = await driver.readRaw();
                msg.payload = { raw, voltage: raw * driver.lsb() };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('ads1114', ADS1114ReadNode);
};
