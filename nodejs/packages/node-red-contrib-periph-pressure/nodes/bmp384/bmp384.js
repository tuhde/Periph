'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { BMP384Full }   = require('periph/src/chips/pressure/bmp384');

    function BMP384DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const addr = parseInt(config.addr) || 0x76;
            const connection = new I2CConnection(parseInt(config.bus), addr);
            node.driver    = new BMP384Full(connection);
            node.driver.configure(
                parseInt(config.osrP) || 4,
                parseInt(config.osrT) || 1,
                parseInt(config.iir)  || 2,
                parseInt(config.odr)  || 0x03
            ).catch((e) => node.error('BMP384 configure failed: ' + e.message));
            node.connection = connection;
        } catch (e) {
            node.error('BMP384 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('bmp384-device', BMP384DeviceNode);

    function BMP384ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No BMP384 device configured', msg);
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
    RED.nodes.registerType('periph-bmp384', BMP384ReadNode);
};
