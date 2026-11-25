'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { LTC4151Full } = require('periph/src/chips/power/ltc4151');

    function LTC4151DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new LTC4151Full(connection, parseFloat(config.rsense) / 1000);
            node.connection = connection;
        } catch (e) {
            node.error('LTC4151 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('ltc4151-device', LTC4151DeviceNode);

    function LTC4151ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No LTC4151 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const r = await d.readAll();
                msg.payload = {
                    voltage: r.vin,
                    current: r.current,
                    power:   r.power
                };
                if (config.adin) msg.payload.adin = r.adin;
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('ltc4151', LTC4151ReadNode);
};
