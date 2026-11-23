'use strict';

module.exports = function(RED) {
    const { Default }        = require('opengpio');
    const { HX711Connection } = require('periph/src/connection/hx711');
    const { HX711Full }       = require('periph/src/chips/adc_dac/hx711');

    function HX711DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const chip   = parseInt(config.gpioChip || 0);
            const dout   = Default.input({ chip, line: parseInt(config.doutPin) });
            const pd_sck = Default.output({ chip, line: parseInt(config.pdSckPin) });
            node.connection = new HX711Connection(dout, pd_sck);
            node.driver     = new HX711Full(node.connection);

            const gain = parseInt(config.gain || 128);
            if (gain !== 128) node.driver.setGain(gain);
            node.driver._offset = parseInt(config.tareOffset || 0);
            node.driver.setScale(parseFloat(config.scaleFactor || 1.0));
        } catch (e) {
            node.error('HX711 init failed: ' + e.message);
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('hx711-device', HX711DeviceNode);

    function HX711ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', function(msg, send, done) {
            if (!deviceNode.driver) { done(); return; }
            try {
                if (msg.payload === 'tare') {
                    deviceNode.driver.tare(10);
                    done();
                } else {
                    const raw    = deviceNode.driver.readRaw();
                    const weight = (raw - deviceNode.driver.getOffset()) / deviceNode.driver.getScale();
                    msg.payload = { raw, weight };
                    send(msg);
                    done();
                }
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('hx711', HX711ReadNode);
};
