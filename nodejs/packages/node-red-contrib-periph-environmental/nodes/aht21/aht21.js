'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { AHT21Full }    = require('periph/src/chips/environmental/aht21');

    function AHT21DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new AHT21Full(connection);
            node.connection = connection;
        } catch (e) {
            node.error('AHT21 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('aht21-device', AHT21DeviceNode);

    function AHT21ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No AHT21 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const r = await d.read();
                msg.payload = {
                    temperature_c: r.temperature_c,
                    humidity_pct:  r.humidity_pct
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('aht21', AHT21ReadNode);
};
