'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { BMA180Full }   = require('periph/src/chips/accelerometer/bma180');

    function BMA180DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new BMA180Full(connection);
            node.connection = connection;
            if (config.range)    node.driver.setRange(parseFloat(config.range));
            if (config.bandwidth) node.driver.setBandwidth(parseInt(config.bandwidth));
        } catch (e) {
            node.error('BMA180 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('bma180-device', BMA180DeviceNode);

    function BMA180ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No BMA180 device configured', msg);
                done();
                return;
            }
            try {
                const [x, y, z] = await node.device.driver.read();
                let temperature;
                try { temperature = await node.device.driver.readTemperature(); } catch (e) { temperature = null; }
                msg.payload = { x, y, z, temperature };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('bma180', BMA180ReadNode);
};