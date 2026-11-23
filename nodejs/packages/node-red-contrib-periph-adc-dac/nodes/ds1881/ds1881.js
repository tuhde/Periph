'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { DS1881Full } = require('periph/src/chips/adc_dac/ds1881');

    function DS1881DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.lastDb = [0, 0];
        node.ready = null;

        try {
            const address = parseInt(config.address, 16) || DS1881Full.I2C_ADDRESS;
            const connection = new I2CConnection(parseInt(config.bus, 10), address);
            node.driver = new DS1881Full(connection);
            node.connection = connection;
            node.ready = (async () => {
                await node.driver.init();
                await node.driver.configure(
                    config.option === '2' ? 2 : 1,
                    config.zerocrossing !== false && config.zerocrossing !== 'false',
                    config.nonvolatile === true || config.nonvolatile === 'true'
                );
            })();
            node.ready.catch((e) => node.error('DS1881 init failed: ' + e.message));
        } catch (e) {
            node.error('DS1881 init failed: ' + e.message);
        }

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
    RED.nodes.registerType('ds1881-device', DS1881DeviceNode);

    function DS1881ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        async function readback() {
            const [position0, position1, raw] = await deviceNode.driver.readAll();
            const cfg = await deviceNode.driver.getConfig();
            return {
                position0, position1,
                db0: deviceNode.driver.positionToDb(position0),
                db1: deviceNode.driver.positionToDb(position1),
                config: { option: cfg.option, zeroCrossing: cfg.zeroCrossing, nonvolatile: cfg.nonvolatile, raw },
            };
        }

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) { done(); return; }
            try {
                await deviceNode.ready;
                const p = msg.payload;
                const isObj = p && typeof p === 'object';
                if (isObj && p.store === true) {
                    await deviceNode.driver.store();
                    done();
                    return;
                }
                if (isObj && typeof p.mute === 'boolean') {
                    const channels = typeof p.channel === 'number' ? [p.channel] : [0, 1];
                    for (const ch of channels) {
                        if (p.mute) await deviceNode.driver.mute(ch);
                        else await deviceNode.driver.setAttenuation(ch, deviceNode.lastDb[ch]);
                    }
                    done();
                    return;
                }
                if (isObj && typeof p.db === 'number' && typeof p.channel === 'number') {
                    await deviceNode.driver.setAttenuation(p.channel, p.db);
                    deviceNode.lastDb[p.channel] = p.db;
                    const position = await deviceNode.driver.getPosition(p.channel);
                    msg.payload = { channel: p.channel, position, db: deviceNode.driver.positionToDb(position) };
                } else if (isObj && typeof p.db === 'number') {
                    await deviceNode.driver.setAttenuationBoth(p.db);
                    deviceNode.lastDb = [p.db, p.db];
                    const r = await readback();
                    msg.payload = { position0: r.position0, position1: r.position1, db0: r.db0, db1: r.db1 };
                } else {
                    msg.payload = await readback();
                }
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('ds1881', DS1881ReadNode);
};
