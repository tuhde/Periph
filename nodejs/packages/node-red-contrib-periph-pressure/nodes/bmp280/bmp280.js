'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { BMP280Full }   = require('periph/src/chips/pressure/bmp280');

    function BMP280DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const addr = parseInt(config.addr) || 0x76;
            const connection = new I2CConnection(parseInt(config.bus), addr);
            node.driver    = new BMP280Full(connection);
            node.driver.configure(
                parseInt(config.osrsT) || 1,
                parseInt(config.osrsP) || 1,
                parseInt(config.mode)  || 1,
                parseInt(config.filter) || 0,
                parseInt(config.tSb)   || 0
            );
            node.connection = connection;
        } catch (e) {
            node.error('BMP280 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('bmp280-device', BMP280DeviceNode);

    function BMP280ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No BMP280 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                msg.payload = {
                    temperature: await d.temperature(),
                    pressure:     await d.pressure(),
                    altitude:    await d.altitude(parseFloat(config.seaLevelHpa) || 1013.25)
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-bmp280', BMP280ReadNode);
};
