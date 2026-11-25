'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { MS5607Full } = require('periph/src/chips/pressure/ms5607');

    function MS5607DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x77;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.connection = connection;
            node.driver = new MS5607Full(connection);
            node.ready = (async () => {
                await node.driver.init();
                const osr = parseInt(config.oversampling);
                node.driver.setOversampling(isNaN(osr) ? 4096 : osr);
            })();
            node.ready.catch((e) => {
                node.error('MS5607 init failed: ' + e.message);
            });
        } catch (e) {
            node.error('MS5607 init failed: ' + e.message);
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('ms5607-device', MS5607DeviceNode);

    function MS5607ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) {
                node.error('MS5607 not initialised', msg);
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
    RED.nodes.registerType('ms5607', MS5607ReadNode);
};
