'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { BMP581Full }   = require('periph/src/chips/pressure/bmp581');

    function BMP581DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const addr = parseInt(config.addr) || 0x46;
            const connection = new I2CConnection(parseInt(config.bus), addr);
            node.driver    = new BMP581Full(connection);
            node.driver.configure(
                parseInt(config.odr)   || 0x1C,
                parseInt(config.osrP)  || 0,
                parseInt(config.osrT)  || 0,
                config.pressEn !== 'false'
            );
            node.connection = connection;
        } catch (e) {
            node.error('BMP581 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('bmp581-device', BMP581DeviceNode);

    function BMP581ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No BMP581 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                msg.payload = {
                    pressure:     await d.pressure(),
                    temperature:  await d.temperature(),
                    altitude:     await d.altitude(parseFloat(config.seaLevelPa) || 101325)
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-bmp581', BMP581ReadNode);
};