'use strict';

module.exports = function(RED) {
    const { Default }         = require('opengpio');
    const { HX711Connection } = require('periph/src/connection/hx711');
    const { HX710AFull }      = require('periph/src/chips/adc_dac/hx710a');

    function HX710ANode(config) {
        RED.nodes.createNode(this, config);
        const node = this;

        try {
            const chip   = parseInt(config.gpioChip || 0);
            const dout   = Default.input({ chip, line: parseInt(config.doutPin) });
            const pd_sck = Default.output({ chip, line: parseInt(config.pdSckPin) });
            node.connection = new HX711Connection(dout, pd_sck);
            node.driver     = new HX710AFull(node.connection);

            const rate = parseInt(config.rate || 10);
            if (rate !== 10) node.driver.setRate(rate);
            node.driver._offset = parseInt(config.tareOffset || 0);
            node.driver.setScale(parseFloat(config.scaleFactor || 1.0));
        } catch (e) {
            node.error('HX710A init failed: ' + e.message);
        }

        node.on('input', function(msg, send, done) {
            if (!node.driver) { done(); return; }
            try {
                if (msg.payload === 'tare') {
                    node.driver.tare(10);
                    done();
                } else if (msg.payload === 'temperature') {
                    const raw_temperature = node.driver.readTemperatureRaw();
                    msg.payload = { raw_temperature };
                    send(msg);
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
            if (node.connection) node.connection.close();
        });
    }

    RED.nodes.registerType('periph-hx710a', HX710ANode);
};
