'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { ADXL345Full }   = require('periph/src/chips/accelerometer/adxl345');

    function ADXL345DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new ADXL345Full(connection);
            node.connection = connection;
            if (config.range)    node.driver.setRange(parseInt(config.range));
            if (config.dataRate) node.driver.setDataRate(parseFloat(config.dataRate));
        } catch (e) {
            node.error('ADXL345 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('adxl345-device', ADXL345DeviceNode);

    function ADXL345ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No ADXL345 device configured', msg);
                done();
                return;
            }
            try {
                const [x, y, z] = await node.device.driver.read();
                msg.payload = { x, y, z };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('adxl345', ADXL345ReadNode);
};