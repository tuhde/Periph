'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { ES8311Full }    = require('periph/src/chips/adc_dac/es8311');

    function ES8311DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.connection = connection;
            node.driver = new ES8311Full(connection, parseInt(config.mclk) || 12288000, parseInt(config.sampleRate) || 48000);
            node.ready = (async () => {
                if (config.volume !== undefined && config.volume !== '') await node.driver.setVolume(parseFloat(config.volume));
                if (config.micGain !== undefined && config.micGain !== '') await node.driver.setMicGain(parseFloat(config.micGain));
            })();
            node.ready.catch((e) => node.error('ES8311 init failed: ' + e.message));
        } catch (e) {
            node.error('ES8311 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('es8311-device', ES8311DeviceNode);

    function ES8311Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No ES8311 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const p = (msg.payload !== null && typeof msg.payload === 'object') ? msg.payload : {};
                let command = false;
                if (p.volume !== undefined) { await d.setVolume(Number(p.volume)); command = true; }
                if (p.mute !== undefined) { await d.setMute(!!p.mute); command = true; }
                if (p.mic_gain !== undefined) { await d.setMicGain(Number(p.mic_gain)); command = true; }
                if (!command) {
                    msg.payload = { volume: await d.getVolume(), mic_gain: await d.getMicGain() };
                    send(msg);
                }
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-es8311', ES8311Node);
};
