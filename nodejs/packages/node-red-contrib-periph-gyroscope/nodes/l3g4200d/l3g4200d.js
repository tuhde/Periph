'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { L3G4200DFull }   = require('periph/src/chips/gyroscope/l3g4200d');

    function L3G4200DDeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const addr = parseInt(config.addr, 16) || 0x68;
            const connection = new I2CConnection(parseInt(config.bus), addr);
            node.driver = new L3G4200DFull(connection);
            node.driver.configure(
                parseInt(config.odr, 10) || 0,
                parseInt(config.bandwidth, 10) || 0,
                parseInt(config.fullScale, 10) || 250
            );
            node.connection = connection;
        } catch (e) {
            node.error('L3G4200D init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('l3g4200d-device', L3G4200DDeviceNode);

    function L3G4200DReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No L3G4200D device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const [x, y, z] = await d.angularRate();
                msg.payload = { x: x, y: y, z: z };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-l3g4200d', L3G4200DReadNode);
};
