'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { MAX17048Full } = require('periph/src/chips/power/max17048');

    function MAX17048DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new MAX17048Full(connection, parseInt(config.cells));
            node.connection = connection;
        } catch (e) {
            node.error('MAX17048 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('periph-max17048-device', MAX17048DeviceNode);

    function MAX17048ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No MAX17048 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                msg.payload = {
                    voltage: await d.voltage(),
                    percent: await d.percent()
                };
                if (config.rate) msg.payload.charge_rate = await d.chargeRate();
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-max17048', MAX17048ReadNode);
};
