'use strict';

module.exports = function(RED) {
    const SPIConnection = require('periph/src/connection/spi').SPIConnection;
    const AD7706Full    = require('periph/src/chips/adc_dac/ad7706').AD7706Full;

    function AD7706DeviceNode(config) {
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
            const chip    = new AD7706Full(connection, vref, mclkHz);

            const channel1 = config.channel1 || { enabled: true, gain: '1', bipolar: true, buffered: false, rate: '50' };
            const channel2 = config.channel2 || { enabled: false, gain: '1', bipolar: true, buffered: false, rate: '50' };
            const channel3 = config.channel3 || { enabled: false, gain: '1', bipolar: true, buffered: false, rate: '50' };

            const initChannel = (chNum, ch) => {
                if (ch.enabled) {
                    chip.configure(chNum, parseInt(ch.gain), ch.bipolar, ch.buffered, parseInt(ch.rate))
                        .then(() => chip.selfCalibrate(chNum))
                        .catch((err) => node.error(`AD7706 channel ${chNum} init failed: ` + err.message));
                }
            };

            initChannel(2, channel2);
            initChannel(3, channel3);

            chip.selfCalibrate(1).then(() => {
                node.chip = chip;
                node.connection = connection;
                node.channel2Enabled = channel2.enabled;
                node.channel3Enabled = channel3.enabled;
            }).catch((err) => {
                node.error('AD7706 channel 1 init failed: ' + err.message);
            });
        } catch (e) {
            node.error('AD7706 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('ad7706-device', AD7706DeviceNode);

    function AD7706Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.chip) {
                done(new Error('No AD7706 device configured'));
                return;
            }
            try {
                const chip = node.device.chip;
                if (msg.payload === 'calibrate') {
                    await chip.selfCalibrate(1);
                    if (node.device.channel2Enabled) {
                        await chip.selfCalibrate(2);
                    }
                    if (node.device.channel3Enabled) {
                        await chip.selfCalibrate(3);
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
                if (node.device.channel3Enabled) {
                    const v3 = await chip.readVoltageChannel(3);
                    result.channel3 = v3;
                }
                msg.payload = result;
                send(msg); done();
            } catch (e) { done(e); }
        });
    }
    RED.nodes.registerType('periph-ad7706', AD7706Node);
};
