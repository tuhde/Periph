'use strict';

module.exports = function(RED) {
    const { I2CTransport } = require('periph/src/transport/i2c');
    const { PCF8591Full }   = require('periph/src/chips/adc_dac/pcf8591');

    function PCF8591Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const transport = new I2CTransport(parseInt(config.bus), parseInt(config.address, 16));
            node.driver    = new PCF8591Full(transport);
            node.transport = transport;
        } catch (e) {
            node.error('PCF8591 init failed: ' + e.message);
        }
        node.on('input', function(msg, send, done) {
            if (!node.driver) { done(); return; }
            try {
                const driver = node.driver;
                if (msg.payload && msg.payload.dac !== undefined) {
                    driver.set_dac(msg.payload.dac);
                }
                const all = driver.read_all();
                msg.payload = { ch0: all[0], ch1: all[1], ch2: all[2], ch3: all[3] };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
        node.on('close', function() {
            if (node.transport) node.transport.close();
        });
    }
    RED.nodes.registerType('periph-pcf8591', PCF8591Node);
};
