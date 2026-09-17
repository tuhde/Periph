'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { MPR121Minimal } = require('periph/src/chips/other/mpr121');

    function MPR121DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const bus = parseInt(config.bus);
            const addr = parseInt(config.address, 16);
            const connection = new I2CConnection(bus, addr);
            node.driver     = new MPR121Minimal(connection);
            node.connection = connection;
        } catch (e) {
            node.error('MPR121 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('mpr121-device', MPR121DeviceNode);

    function MPR121ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No MPR121 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const touched = await d.touched();
                const electrodes = [];
                for (let i = 0; i < 12; i++) electrodes.push((touched & (1 << i)) !== 0);
                msg.payload = { touched, electrodes };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('mpr121', MPR121ReadNode);
};
