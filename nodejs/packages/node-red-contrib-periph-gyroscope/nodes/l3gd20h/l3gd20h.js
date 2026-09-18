'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { L3GD20HFull }   = require('periph/src/chips/gyroscope/l3gd20h');

    function L3GD20HDeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const addr = parseInt(config.addr, 16) || 0x6A;
            const connection = new I2CConnection(parseInt(config.bus), addr);
            node.driver = new L3GD20HFull(connection);
            node.driver.configure(
                parseInt(config.odr, 10) || 0,
                parseInt(config.bandwidth, 10) || 0,
                parseInt(config.fullScale, 10) || 0
            );
            node.connection = connection;
        } catch (e) {
            node.error('L3GD20H init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('l3gd20h-device', L3GD20HDeviceNode);

    function L3GD20HReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No L3GD20H device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const [x, y, z] = await d.gyro();
                msg.payload = { x: x, y: y, z: z };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-l3gd20h', L3GD20HReadNode);
};