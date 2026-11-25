'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { BMA150Full }   = require('periph/src/chips/accelerometer/bma150');

    function BMA150DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new BMA150Full(connection);
            node.connection = connection;
            if (config.range)    node.driver.setRange(parseInt(config.range));
            if (config.bandwidth) node.driver.setBandwidth(parseInt(config.bandwidth));
        } catch (e) {
            node.error('BMA150 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('bma150-device', BMA150DeviceNode);

    function BMA150ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No BMA150 device configured', msg);
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
    RED.nodes.registerType('bma150', BMA150ReadNode);
};
