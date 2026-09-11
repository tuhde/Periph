'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { APDS9930Minimal } = require('periph/src/chips/light/apds-9930');

    function APDS9930DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const bus = parseInt(config.bus);
            const addr = parseInt(config.address, 16);
            const connection = new I2CConnection(bus, addr);
            node.driver     = new APDS9930Minimal(connection);
            node.connection = connection;
        } catch (e) {
            node.error('APDS-9930 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('apds9930-device', APDS9930DeviceNode);

    function APDS9930ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No APDS-9930 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const lux       = await d.lux();
                const proximity = await d.proximity();
                msg.payload = { lux, proximity };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('apds9930', APDS9930ReadNode);
};