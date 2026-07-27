'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { MCP4725Full }   = require('periph/src/chips/adc_dac/mcp4725');

    function MCP4725DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new MCP4725Full(connection);
            node.connection = connection;
        } catch (e) {
            node.error('MCP4725 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('mcp4725-device', MCP4725DeviceNode);

    function MCP4725WriteNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);
        node.inputMode = config.inputMode || 'fraction';

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No MCP4725 device configured', msg);
                done();
                return;
            }
            try {
                const dac = node.device.driver;
                const raw = parseFloat(msg.payload);
                if (node.inputMode === 'raw') {
                    await dac.set_raw(raw);
                } else {
                    await dac.set_voltage(raw);
                }
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-mcp4725', MCP4725WriteNode);
};
