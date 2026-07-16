'use strict';

module.exports = function(RED) {
    const { I2CTransport } = require('periph/src/transport/i2c');
    const { RDA5807MFull } = require('periph/src/chips/comms/rda5807m');

    function RDA5807MNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;

        try {
            const transport = new I2CTransport(parseInt(config.bus), parseInt(config.address, 16) || 0x10);
            node.driver = new RDA5807MFull(transport, parseFloat(config.frequency) || 100.0, parseInt(config.volume) || 8);
            node.driver.configure({ band: parseInt(config.band), space: parseInt(config.space) });
            node.transport = transport;
        } catch (e) {
            node.error('RDA5807M init failed: ' + e.message);
        }

        node.on('input', function(msg, send, done) {
            if (!node.driver) {
                node.error('RDA5807M not initialized', msg);
                done();
                return;
            }
            try {
                const d = node.driver;
                const payload = msg.payload || {};

                switch (payload.command) {
                    case 'tune':
                        d.setFrequency(payload.frequency);
                        break;
                    case 'seek': {
                        const freq = d.seek(payload.direction !== 'down');
                        if (freq === null) {
                            send(Object.assign({}, msg, { payload: { frequency: null } }));
                            done();
                            return;
                        }
                        break;
                    }
                    case 'volume':
                        d.setVolume(payload.level);
                        send(Object.assign({}, msg, { payload: { volume: payload.level } }));
                        done();
                        return;
                    case 'mute':
                        d.mute(!!payload.enable);
                        send(Object.assign({}, msg, { payload: { muted: !!payload.enable } }));
                        done();
                        return;
                    default:
                        break;
                }

                msg.payload = {
                    frequency: d.frequency(),
                    stereo: d.isStereo(),
                    station: d.isStation(),
                    ready: d.isReady(),
                    rssi: d.signalStrength()
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });

        node.on('close', function() {
            if (node.transport) node.transport.close();
        });
    }
    RED.nodes.registerType('periph-rda5807m', RDA5807MNode);
};
