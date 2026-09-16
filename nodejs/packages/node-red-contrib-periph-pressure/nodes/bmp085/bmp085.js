'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { BMP085Full }   = require('periph/src/chips/pressure/bmp085');

    function BMP085DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), 0x77);
            node.driver     = new BMP085Full(connection, parseInt(config.oss) || 0);
            node.connection = connection;
        } catch (e) {
            node.error('BMP085 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('bmp085-device', BMP085DeviceNode);

    function BMP085ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No BMP085 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                msg.payload = {
                    temperature: await d.temperature(),
                    pressure:     await d.pressure(),
                    altitude:    await d.altitude(parseFloat(config.seaLevelPa) || 101325.0)
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-bmp085', BMP085ReadNode);
};