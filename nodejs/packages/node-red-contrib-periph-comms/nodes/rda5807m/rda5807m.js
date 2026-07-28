'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { RDA5807MFull } = require('periph/src/chips/comms/rda5807m');

    function RDA5807MNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;

        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16) || 0x10);
            node.driver = new RDA5807MFull(connection, parseFloat(config.frequency) || 100.0, parseInt(config.volume) || 8);
            node.driver.configure({ band: parseInt(config.band), space: parseInt(config.space) });
            node.connection = connection;
        } catch (e) {
            node.error('RDA5807M init failed: ' + e.message);
        }

        node.on('input', async function(msg, send, done) {
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
                        await d.setFrequency(payload.frequency);
                        break;
                    case 'seek': {
                        const freq = await d.seek(payload.direction !== 'down');
                        if (freq === null) {
                            send(Object.assign({}, msg, { payload: { frequency: null } }));
                            done();
                            return;
                        }
                        break;
                    }
                    case 'volume':
                        await d.setVolume(payload.level);
                        send(Object.assign({}, msg, { payload: { volume: payload.level } }));
                        done();
                        return;
                    case 'mute':
                        await d.mute(!!payload.enable);
                        send(Object.assign({}, msg, { payload: { muted: !!payload.enable } }));
                        done();
                        return;
                    default:
                        break;
                }

                msg.payload = {
                    frequency: await d.frequency(),
                    stereo: await d.isStereo(),
                    station: await d.isStation(),
                    ready: await d.isReady(),
                    rssi: await d.signalStrength()
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('periph-rda5807m', RDA5807MNode);
};
