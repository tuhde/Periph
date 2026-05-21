'use strict';

module.exports = function(RED) {
    const { I2CTransport } = require('periph/src/transport/i2c');
    const { BMP280Full }   = require('periph/src/chips/pressure/bmp280');

    function BMP280DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const transport = new I2CTransport(parseInt(config.bus), parseInt(config.address, 16));
            node.driver    = new BMP280Full(
                transport,
                parseInt(config.osrs_t) || 1,
                parseInt(config.osrs_p) || 1,
                parseInt(config.mode) || 1,
                parseInt(config.filter) || 0,
                parseInt(config.t_sb) || 0
            );
            node.transport = transport;
        } catch (e) {
            node.error('BMP280 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.transport) node.transport.close();
        });
    }
    RED.nodes.registerType('bmp280-device', BMP280DeviceNode);

    function BMP280ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No BMP280 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const seaLevelHpa = parseFloat(config.seaLevelHpa) || 1013.25;
                msg.payload = {
                    temperature: d.temperature(),
                    pressure:    d.pressure(),
                    altitude:    d.altitude(seaLevelHpa)
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