'use strict';

module.exports = function(RED) {
    const SPIConnection = require('periph/src/connection/spi').SPIConnection;
    const HY3131Full    = require('periph/src/chips/adc_dac/hy3131').HY3131Full;

    function HY3131DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new SPIConnection(
                parseInt(config.bus),
                parseInt(config.device),
                { mode: 0, maxSpeedHz: 1_000_000 }
            );
            const vref = parseFloat(config.vref) || 1.2;
            const chip = new HY3131Full(connection, vref);
            chip.init()
                .then(() => chip.configureAd1({
                    osr: parseInt(config.osr),
                    chop: parseInt(config.chop),
                    inputGain: parseInt(config.inputGain),
                }))
                .then(() => {
                    node.chip = chip;
                    node.connection = connection;
                })
                .catch((err) => node.error('HY3131 init failed: ' + err.message));
        } catch (e) {
            node.error('HY3131 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('hy3131-device', HY3131DeviceNode);

    function HY3131Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.chip) {
                done(new Error('No HY3131 device configured'));
                return;
            }
            try {
                const chip = node.device.chip;
                const raw = await chip.readRaw();
                msg.payload = { voltage: await chip.readVoltage(), raw };
                send(msg); done();
            } catch (e) { done(e); }
        });
    }
    RED.nodes.registerType('periph-hy3131', HY3131Node);
};
