'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { CS43L22Full, BEEP, FMT } = require('periph/src/chips/adc_dac/cs43l22');

    function CS43L22Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.ready = null;

        try {
            const address = parseInt(config.address, 16) || 0x4A;
            const connection = new I2CConnection(parseInt(config.bus, 10), address);
            let resPin = null;
            if (config.resGpio !== undefined && config.resGpio !== '') {
                // opengpio is optional: only required when a RESET GPIO is configured.
                const { Default } = require('opengpio');
                const { GpioOutputPin } = require('periph/src/connection/output_pin');
                resPin = new GpioOutputPin(Default.output({ chip: parseInt(config.gpioChip || 0, 10), line: parseInt(config.resGpio, 10) }));
            }
            node.driver = new CS43L22Full(connection, resPin);
            node.connection = connection;
            const fmt = { i2s: FMT.I2S, lj: FMT.LJ, rj: FMT.RJ }[config.format] ?? FMT.I2S;
            node.ready = (async () => {
                await node.driver.init();
                if (fmt !== FMT.I2S) await node.driver.setInterface({ fmt });
            })();
            node.ready.catch((e) => node.error('CS43L22 init failed: ' + e.message));
        } catch (e) {
            node.error('CS43L22 init failed: ' + e.message);
        }

        node.on('input', async function(msg, send, done) {
            if (!node.driver) { done(); return; }
            try {
                await node.ready;
                const p = msg.payload;
                const isObj = p && typeof p === 'object';
                let handled = false;
                if (isObj && p.start === true) { await node.driver.start(); handled = true; }
                if (isObj && p.stop === true) { await node.driver.stop(); handled = true; }
                if (isObj && typeof p.volume === 'number') { await node.driver.setVolume(p.volume); handled = true; }
                if (isObj && typeof p.mute === 'boolean') { await node.driver.mute(p.mute); handled = true; }
                if (isObj && p.beep && typeof p.beep === 'object') {
                    const b = p.beep;
                    await node.driver.setBeep(b.frequency ?? 12, b.onTime ?? 2, b.offTime ?? 0, b.volume ?? -6,
                        b.mode ?? BEEP.SINGLE);
                    handled = true;
                }
                if (isObj && p.tone && typeof p.tone === 'object') {
                    await node.driver.setTone(p.tone.treble ?? 0, p.tone.bass ?? 0);
                    handled = true;
                }
                if (handled && !(isObj && typeof p.volume === 'number')) { done(); return; }
                msg.payload = {
                    volume: await node.driver.getVolume(),
                    status: await node.driver.readStatus(),
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });

        node.on('close', async function(removed, done) {
            try {
                if (node.connection) await node.connection.close();
            } catch (e) {
                // bus may already be gone; nothing more to do on close
            } finally {
                done();
            }
        });
    }
    RED.nodes.registerType('periph-cs43l22', CS43L22Node);
};
