'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { HMC5883LFull }   = require('periph/src/chips/magnetometer/hmc5883l');

    function HMC5883LDeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new HMC5883LFull(connection);
            node.connection = connection;
        } catch (e) {
            node.error('HMC5883L init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('hmc5883l-device', HMC5883LDeviceNode);

    function HMC5883LReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No HMC5883L device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const { x, y, z } = await d.magneticField();
                msg.payload = { x, y, z };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-hmc5883l', HMC5883LReadNode);
};