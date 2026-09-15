'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { LPS28DFWFull }  = require('periph/src/chips/pressure/lps28dfw');

    function LPS28DFWDeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const addr = parseInt(config.addr, 16) || 0x5C;
            const connection = new I2CConnection(parseInt(config.bus), addr);
            node.driver    = new LPS28DFWFull(connection);
            node.driver.configure(
                parseInt(config.odr)  || 0x04,
                parseInt(config.avg)  || 0x02,
                parseInt(config.fsMode) || 0,
                config.lpfEn === 'true',
                parseInt(config.lpfCfg) || 0
            );
            node.connection = connection;
        } catch (e) {
            node.error('LPS28DFW init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('lps28dfw-device', LPS28DFWDeviceNode);

    function LPS28DFWReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No LPS28DFW device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                msg.payload = {
                    pressure:     await d.readPressure(),
                    temperature:  await d.readTemperature(),
                    altitude:    await d.altitude(parseFloat(config.seaLevelHpa) || 1013.25)
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-lps28dfw', LPS28DFWReadNode);
};