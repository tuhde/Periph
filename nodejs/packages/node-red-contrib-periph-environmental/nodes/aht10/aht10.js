'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { AHT10Full }    = require('periph/src/chips/environmental/aht10');

    function AHT10DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new AHT10Full(connection);
            node.connection = connection;
        } catch (e) {
            node.error('AHT10 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('aht10-device', AHT10DeviceNode);

    function AHT10ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No AHT10 device configured', msg);
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
    RED.nodes.registerType('aht10', AHT10ReadNode);
};
