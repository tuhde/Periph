'use strict';

module.exports = function(RED) {
    const { I2CTransport } = require('periph/src/transport/i2c');
    const { BME680Full }   = require('periph/src/chips/environmental/bme680');

    function BME680DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const addr = parseInt(config.addr) || 0x76;
            const transport = new I2CTransport(parseInt(config.bus), addr);
            node.driver    = new BME680Full(transport);
            node.driver.configure(
                parseInt(config.osrsT) || 1,
                parseInt(config.osrsP) || 1,
                parseInt(config.osrsH) || 1,
                1,
                parseInt(config.filter) || 0
            );
            node.driver.setHeater(
                parseInt(config.heaterTemp) || 320,
                parseInt(config.heaterDur) || 150
            );
            node.transport = transport;
        } catch (e) {
            node.error('BME680 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.transport) node.transport.close();
        });
    }
    RED.nodes.registerType('bme680-device', BME680DeviceNode);

    function BME680ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No BME680 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const r = d.readAll();
                const seaLevelHpa = parseFloat(config.seaLevelHpa) || 1013.25;
                const altitude = 44330 * (1 - Math.pow(r.pressure / seaLevelHpa, 1 / 5.255));
                const a = 17.62, b = 243.12;
                const gamma = Math.log(r.humidity / 100) + (a * r.temperature) / (b + r.temperature);
                const dewPoint = (b * gamma) / (a - gamma);
                msg.payload = {
                    temperature:      r.temperature,
                    pressure:         r.pressure,
                    humidity:         r.humidity,
                    gas_resistance:   isNaN(r.gasResistance) ? null : r.gasResistance,
                    altitude:         altitude,
                    dew_point:        dewPoint
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-bme680', BME680ReadNode);
};
