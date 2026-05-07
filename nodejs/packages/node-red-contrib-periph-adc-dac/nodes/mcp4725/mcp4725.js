'use strict';

module.exports = function(RED) {
    const { I2CTransport } = require('periph/src/transport/i2c');
    const { MCP4725Full }   = require('periph/src/chips/adc_dac/mcp4725');

    function MCP4725DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const transport = new I2CTransport(parseInt(config.bus), parseInt(config.address, 16));
            node.driver    = new MCP4725Full(transport);
            node.transport = transport;
        } catch (e) {
            node.error('MCP4725 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.transport) node.transport.close();
        });
    }
    RED.nodes.registerType('periph-mcp4725-device', MCP4725DeviceNode);

    function MCP4725WriteNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);
        node.mode = config.mode || 'fraction';
        node.persist = config.persist || false;

        node.on('input', function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No MCP4725 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const val = parseFloat(msg.payload);
                if (node.mode === 'raw') {
                    const code = Math.round(Math.max(0, Math.min(4095, val)));
                    if (node.persist) d.set_raw_eeprom(code);
                    else d.set_raw(code);
                } else {
                    const fraction = Math.max(0.0, Math.min(1.0, val));
                    if (node.persist) d.set_voltage_eeprom(fraction);
                    else d.set_voltage(fraction);
                }
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-mcp4725', MCP4725WriteNode);
};