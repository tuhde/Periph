'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { AHT20Full }    = require('periph/src/chips/environmental/aht20');

    function AHT20DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new AHT20Full(connection);
            node.connection = connection;
        } catch (e) {
            node.error('AHT20 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('aht20-device', AHT20DeviceNode);

    function AHT20ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No AHT20 device configured', msg);
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
    RED.nodes.registerType('aht20', AHT20ReadNode);
};
