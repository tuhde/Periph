'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { SPL06Full } = require('periph/src/chips/pressure/spl06');

    function SPL06DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x77;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.connection = connection;
            node.driver = new SPL06Full(connection);
            node.ready = (async () => {
                await node.driver.init();
                const osr = parseInt(config.oversampling);
                await node.driver.setPressureConfig(isNaN(osr) ? 16 : osr, 1);
            })();
            node.ready.catch((e) => {
                node.error('SPL06 init failed: ' + e.message);
            });
        } catch (e) {
            node.error('SPL06 init failed: ' + e.message);
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('spl06-device', SPL06DeviceNode);

    function SPL06ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) {
                node.error('SPL06 not initialised', msg);
                done();
                return;
            }
            try {
                await deviceNode.ready;
                const { temperature_c, pressure_pa } = await deviceNode.driver.read();
                msg.payload = { temperature_c, pressure_pa };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('spl06', SPL06ReadNode);
};
