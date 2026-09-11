'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { LPS22DFFull }   = require('periph/src/chips/pressure/lps22df');

    function LPS22DFDeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const addr = parseInt(config.addr) || 0x5C;
            const connection = new I2CConnection(parseInt(config.bus), addr);
            node.driver = new LPS22DFFull(connection);
            node.driver.configure(
                parseInt(config.odr)    || 3,
                parseInt(config.avg)    || 0,
                config.enLpfp === 'true',
                parseInt(config.lfpfCfg) || 0,
                config.bdu === 'true'
            );
            node.connection = connection;
        } catch (e) {
            node.error('LPS22DF init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('lps22df-device', LPS22DFDeviceNode);

    function LPS22DFReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No LPS22DF device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                msg.payload = {
                    pressure:    await d.pressure(),
                    temperature: await d.temperature(),
                    altitude:    await d.altitude(parseFloat(config.seaLevelPa) || 101325.0)
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-lps22df', LPS22DFReadNode);
};