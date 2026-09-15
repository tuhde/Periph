'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { LPS33HWFull }   = require('periph/src/chips/pressure/lps33hw');

    function LPS33HWDeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const addr = parseInt(config.addr) || 0x5C;
            const connection = new I2CConnection(parseInt(config.bus), addr);
            node.driver    = new LPS33HWFull(connection);
            node.driver.configure(
                parseInt(config.odr)    || 1,
                parseInt(config.bdu)    ? 1 : 0,
                parseInt(config.enLpfp) ? 1 : 0,
                parseInt(config.lpfpCfg) || 0,
                parseInt(config.lcEn)   ? 1 : 0,
                parseInt(config.sim)    ? 1 : 0
            );
            node.connection = connection;
        } catch (e) {
            node.error('LPS33HW init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('lps33hw-device', LPS33HWDeviceNode);

    function LPS33HWReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No LPS33HW device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                msg.payload = {
                    pressure_Pa:    await d.pressure(),
                    temperature_C:  await d.temperature()
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-lps33hw', LPS33HWReadNode);
};
