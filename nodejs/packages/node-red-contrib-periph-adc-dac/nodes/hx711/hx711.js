'use strict';

module.exports = function(RED) {
    const Gpio               = require('onoff').Gpio;
    const { HX711Transport } = require('periph/src/transport/hx711');
    const { HX711Full }      = require('periph/src/chips/adc_dac/hx711');

    function HX711Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;

        try {
            const dout   = new Gpio(parseInt(config.doutPin),  'in');
            const pd_sck = new Gpio(parseInt(config.pdSckPin), 'out');
            node.transport = new HX711Transport(dout, pd_sck);
            node.driver    = new HX711Full(node.transport);

            const gain = parseInt(config.gain || 128);
            if (gain !== 128) node.driver.setGain(gain);
            node.driver._offset = parseInt(config.tareOffset || 0);
            node.driver.setScale(parseFloat(config.scaleFactor || 1.0));
        } catch (e) {
            node.error('HX711 init failed: ' + e.message);
        }

        node.on('input', function(msg, send, done) {
            if (!node.driver) { done(); return; }
            try {
                if (msg.payload === 'tare') {
                    node.driver.tare(10);
                    done();
                } else {
                    const raw    = node.driver.readRaw();
                    const weight = (raw - node.driver.getOffset()) / node.driver.getScale();
                    msg.payload = { raw, weight };
                    send(msg);
                    done();
                }
            } catch (e) {
                done(e);
            }
        });

        node.on('close', function() {
            if (node.transport) node.transport.close();
        });
    }

    RED.nodes.registerType('periph-hx711', HX711Node);
};
