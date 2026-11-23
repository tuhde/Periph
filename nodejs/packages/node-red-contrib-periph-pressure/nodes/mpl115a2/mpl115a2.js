'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { MPL115A2Full }  = require('periph/src/chips/pressure/mpl115a2');

    function MPL115A2DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), 0x60);
            node.driver     = new MPL115A2Full(connection);
            node.connection = connection;
            node.driver.ready.catch(function(e) { node.error('MPL115A2 init failed: ' + e.message); });
        } catch (e) {
            node.error('MPL115A2 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('mpl115a2-device', MPL115A2DeviceNode);

    function MPL115A2ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No MPL115A2 device configured', msg);
                done();
                return;
            }
            try {
                const r = await node.device.driver.read();
                msg.payload = { pressure_pa: r.pressure_pa, temperature_c: r.temperature_c };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('mpl115a2', MPL115A2ReadNode);
};
