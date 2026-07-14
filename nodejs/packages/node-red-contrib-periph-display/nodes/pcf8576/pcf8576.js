'use strict';

module.exports = function(RED) {
    const { I2CTransport } = require('periph/src/transport/i2c');
    const { PCF8576Full }  = require('periph/src/chips/display/pcf8576');

    function PCF8576DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const transport = new I2CTransport(parseInt(config.bus), parseInt(config.address, 16));
            node.driver    = new PCF8576Full(transport);
            node.driver.setMode(parseInt(config.backplanes) || 4, parseInt(config.bias) || 0);
            node.transport = transport;
        } catch (e) {
            node.error('PCF8576 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.transport) node.transport.close();
        });
    }
    RED.nodes.registerType('pcf8576-device', PCF8576DeviceNode);

    function PCF8576DigitsNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No PCF8576 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const payload = msg.payload;
                if (payload && Array.isArray(payload.digits)) {
                    const digits = payload.digits;
                    const out = digits.map(v => (v == null) ? 0x00 : PCF8576Full.SEVEN_SEG[Math.max(0, Math.min(9, v))]);
                    d.writeRaw(0, out);
                    send(msg);
                } else if (payload && typeof payload.address === 'number' && Array.isArray(payload.data)) {
                    d.writeRaw(payload.address, payload.data);
                    send(msg);
                } else {
                    node.error('Payload must have digits[] or {address, data[]}', msg);
                }
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-pcf8576', PCF8576DigitsNode);
};
