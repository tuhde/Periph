'use strict';

module.exports = function(RED) {
    const { Default }         = require('opengpio');
    const { HX711Connection } = require('periph/src/connection/hx711');
    const { HX710BFull }      = require('periph/src/chips/adc_dac/hx710b');

    function HX710BDeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const chip   = parseInt(config.gpioChip || 0);
            const dout   = Default.input({ chip, line: parseInt(config.doutPin) });
            const pd_sck = Default.output({ chip, line: parseInt(config.pdSckPin) });
            node.connection = new HX711Connection(dout, pd_sck);
            node.driver     = new HX710BFull(node.connection);

            const rate = parseInt(config.rate || 10);
            if (rate !== 10) node.driver.setRate(rate);
            node.driver._offset = parseInt(config.tareOffset || 0);
            node.driver.setScale(parseFloat(config.scaleFactor || 1.0));
        } catch (e) {
            node.error('HX710B init failed: ' + e.message);
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('hx710b-device', HX710BDeviceNode);

    function HX710BReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', function(msg, send, done) {
            if (!deviceNode.driver) { done(); return; }
            try {
                if (msg.payload === 'tare') {
                    deviceNode.driver.tare(10);
                    done();
                } else if (msg.payload === 'supply_diff') {
                    const raw_supply_diff = deviceNode.driver.readSupplyDiffRaw();
                    msg.payload = { raw_supply_diff };
                    send(msg);
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
    RED.nodes.registerType('hx710b', HX710BReadNode);
};
