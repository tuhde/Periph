'use strict';

module.exports = function(RED) {
    const SPIConnection = require('periph/src/connection/spi').SPIConnection;
    const AD7705Full    = require('periph/src/chips/adc_dac/ad7705').AD7705Full;

    function AD7705DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new SPIConnection(
                parseInt(config.bus),
                parseInt(config.device),
                { mode: 3, maxSpeedHz: 5_000_000 }
            );
            const vref    = parseFloat(config.vref) || 2.5;
            const mclkHz  = parseInt(config.mclkHz) || 2457600;
            const chip    = new AD7705Full(connection, vref, mclkHz);

            const channel1 = config.channel1 || { enabled: true, gain: '1', bipolar: true, buffered: false, rate: '50' };
            const channel2 = config.channel2 || { enabled: false, gain: '1', bipolar: true, buffered: false, rate: '50' };

            if (channel2.enabled) {
                chip.configure(2, parseInt(channel2.gain), channel2.bipolar, channel2.buffered, parseInt(channel2.rate))
                    .then(() => chip.selfCalibrate(2))
                    .catch((err) => node.error('AD7705 channel 2 init failed: ' + err.message));
            }

            chip.selfCalibrate(1).then(() => {
                node.chip = chip;
                node.connection = connection;
                node.channel2Enabled = channel2.enabled;
            }).catch((err) => {
                node.error('AD7705 channel 1 init failed: ' + err.message);
            });
        } catch (e) {
            node.error('AD7705 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('ad7705-device', AD7705DeviceNode);

    function AD7705Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.chip) {
                done(new Error('No AD7705 device configured'));
                return;
            }
            try {
                const chip = node.device.chip;
                if (msg.payload === 'calibrate') {
                    await chip.selfCalibrate(1);
                    if (node.device.channel2Enabled) {
                        await chip.selfCalibrate(2);
                    }
                    msg.payload = { calibrated: true };
                    send(msg); done();
                    return;
                }
                const v1 = await chip.readVoltageChannel(1);
                const result = { channel1: v1 };
                if (node.device.channel2Enabled) {
                    const v2 = await chip.readVoltageChannel(2);
                    result.channel2 = v2;
                }
                msg.payload = result;
                send(msg); done();
            } catch (e) { done(e); }
        });
    }
    RED.nodes.registerType('periph-ad7705', AD7705Node);
};
