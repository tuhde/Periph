'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { BMP180Full }   = require('periph/src/chips/pressure/bmp180');

    function BMP180DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), 0x77);
            node.driver     = new BMP180Full(connection, parseInt(config.oss) || 0);
            node.connection = connection;
        } catch (e) {
            node.error('BMP180 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('bmp180-device', BMP180DeviceNode);

    function BMP180ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No BMP180 device configured', msg);
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
    RED.nodes.registerType('periph-bmp180', BMP180ReadNode);
};
