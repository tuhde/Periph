'use strict';

module.exports = function(RED) {
    const { I2CTransport } = require('periph/src/transport/i2c');
    const { MCP4728Full }   = require('periph/src/chips/adc_dac/mcp4728');

    function MCP4728DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const transport = new I2CTransport(parseInt(config.bus), parseInt(config.address, 16));
            node.driver    = new MCP4728Full(transport);
            node.transport = transport;
        } catch (e) {
            node.error('MCP4728 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.transport) node.transport.close();
        });
    }
    RED.nodes.registerType('mcp4728-device', MCP4728DeviceNode);

    function MCP4728WriteNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);
        node.channel   = (config.channel === undefined ? 'A' : config.channel);
        node.inputMode = config.inputMode || 'fraction';
        node.vref      = (config.vref === 'internal') ? 1 : 0;
        node.gain      = (config.gain === 'x2') ? 2 : 1;
        node.persist   = !!config.persist;

        node.on('input', function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No MCP4728 device configured', msg);
                done();
                return;
            }
            try {
                const dac = node.device.driver;
                const chIdx = { 'A': 0, 'B': 1, 'C': 2, 'D': 3 }[node.channel];
                if (chIdx === undefined) {
                    node.error('Invalid channel: ' + node.channel, msg);
                    done();
                    return;
                }
                if (Array.isArray(msg.payload) && msg.payload.length === 4) {
                    dac.set_all(msg.payload);
                } else {
                    const v = parseFloat(msg.payload);
                    if (Number.isNaN(v)) {
                        node.error('Invalid payload: ' + msg.payload, msg);
                        done();
                        return;
                    }
                    if (node.inputMode === 'raw') {
                        if (node.persist) dac.set_raw_eeprom(chIdx, v, node.vref, node.gain);
                        else              dac.set_raw(chIdx, v);
                    } else {
                        if (node.persist) dac.set_voltage_eeprom(chIdx, v, node.vref, node.gain);
                        else              dac.set_voltage(chIdx, v);
                    }
                }
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-mcp4728', MCP4728WriteNode);
};
