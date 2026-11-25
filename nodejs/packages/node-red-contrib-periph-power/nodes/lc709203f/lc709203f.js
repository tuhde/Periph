'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { LC709203FFull } = require('periph/src/chips/power/lc709203f');

    function LC709203FDeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new LC709203FFull(connection, parseInt(config.capacity), parseInt(config.profile));
            node.connection = connection;
        } catch (e) {
            node.error('LC709203F init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('lc709203f-device', LC709203FDeviceNode);

    function LC709203FReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No LC709203F device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                if (typeof msg.temperature === 'number') {
                    await d.setTemperature(msg.temperature);
                }
                msg.payload = {
                    voltage_v:     await d.voltage(),
                    percent:       await d.percent(),
                    temperature_c: await d.temperature()
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('lc709203f', LC709203FReadNode);
};
