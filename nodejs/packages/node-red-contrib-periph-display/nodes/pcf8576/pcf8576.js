'use strict';

module.exports = function(RED) {
    const { I2CTransport } = require('periph/src/transport/i2c');
    const { PCF8576Full } = require('periph/src/chips/display/pcf8576');

    function PCF8576DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const transport = new I2CTransport(parseInt(config.bus), parseInt(config.address, 16));
            node.driver = new PCF8576Full(transport);
            node.transport = transport;
        } catch (e) {
            node.error('PCF8576 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.transport) node.transport.close();
        });
    }
    RED.nodes.registerType('pcf8576-device', PCF8576DeviceNode);

    function PCF8576WriteNode(config) {
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
                const lcd = node.device.driver;
                if (msg.payload && Array.isArray(msg.payload.digits)) {
                    const data = msg.payload.digits.map(d => {
                        if (d === null || d === undefined) return 0x00;
                        if (d < 0 || d > 9) return 0x00;
                        return PCF8576Full.SEG_7SEG[d];
                    });
                    lcd.writeRaw(0, Buffer.from(data));
                } else if (msg.payload && msg.payload.address !== undefined && msg.payload.data) {
                    lcd.writeRaw(msg.payload.address, Buffer.from(msg.payload.data));
                }
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-pcf8576', PCF8576WriteNode);
};