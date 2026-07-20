'use strict';

module.exports = function(RED) {
    const { I2CTransport } = require('periph/src/transport/i2c');
    const { BME280Full }   = require('periph/src/chips/environmental/bme280');

    function BME280DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const addr = parseInt(config.addr) || 0x76;
            const transport = new I2CTransport(parseInt(config.bus), addr);
            node.driver    = new BME280Full(transport);
            node.driver.configure(
                parseInt(config.osrsT) || 1,
                parseInt(config.osrsP) || 1,
                parseInt(config.osrsH) || 1,
                parseInt(config.mode) || 0,
                parseInt(config.filter) || 0,
                parseInt(config.tSb) || 0
            );
            node.transport = transport;
        } catch (e) {
            node.error('BME280 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.transport) node.transport.close();
        });
    }
    RED.nodes.registerType('bme280-device', BME280DeviceNode);

    function BME280ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No BME280 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const t = d.temperature();
                const p = d.pressure();
                const h = d.humidity();
                const seaLevelHpa = parseFloat(config.seaLevelHpa) || 1013.25;
                const altitude = 44330 * (1 - Math.pow(p / seaLevelHpa, 1 / 5.255));
                const a = 17.27, b = 237.7;
                let dewPoint = null;
                if (h > 0) {
                    const alpha = (a * t) / (b + t) + Math.log(h / 100);
                    dewPoint = (b * alpha) / (a - alpha);
                }
                msg.payload = {
                    temperature: t,
                    pressure:    p,
                    humidity:    h,
                    altitude:    altitude,
                    dew_point:   dewPoint
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-bme280', BME280ReadNode);
};
