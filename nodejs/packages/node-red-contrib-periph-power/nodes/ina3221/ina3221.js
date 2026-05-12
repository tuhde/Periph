'use strict';

module.exports = function(RED) {
    const { I2CTransport } = require('periph/src/transport/i2c');
    const { INA3221Full }   = require('periph/src/chips/power/ina3221');

    function INA3221DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const transport = new I2CTransport(parseInt(config.bus), parseInt(config.address, 16));
            const rShunt = config.rShunt || 0.1;
            node.driver    = new INA3221Full(transport, rShunt);
            node.transport = transport;
        } catch (e) {
            node.error('INA3221 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.transport) node.transport.close();
        });
    }
    RED.nodes.registerType('ina3221-device', INA3221DeviceNode);

    function INA3221ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No INA3221 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const payload = {};
                for (const ch of [1, 2, 3]) {
                    payload['ch' + ch] = {
                        voltage:      d.voltage(ch),
                        shuntVoltage: d.shuntVoltage(ch),
                        current:      d.current(ch),
                        power:        d.power(ch)
                    };
                }
                msg.payload = payload;
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-ina3221', INA3221ReadNode);
};