'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { DRV2605Full } = require('periph/src/chips/motor/drv2605');

    const LIBRARIES = { A: 1, B: 2, C: 3, D: 4, E: 5, LRA: 6 };

    function DRV2605Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;

        try {
            const lra = config.actuator === 'lra';
            const connection = new I2CConnection(parseInt(config.bus, 10), DRV2605Full.I2C_ADDRESS);
            node.driver = new DRV2605Full(connection, lra);
            node.connection = connection;
            node.ready = node.driver.init()
                .then(() => node.driver.setLibrary(LIBRARIES[config.library] || (lra ? 6 : 1)))
                .catch((e) => { node.driver = null; node.error('DRV2605 init failed: ' + e.message); });
        } catch (e) {
            node.error('DRV2605 init failed: ' + e.message);
        }

        node.on('input', async function(msg, send, done) {
            await node.ready;
            if (!node.driver) { done(); return; }
            try {
                const p = msg.payload;
                if (typeof p === 'number') {
                    await node.driver.playEffect(p);
                    msg.payload = { ok: true };
                } else if (p && typeof p === 'object') {
                    if (typeof p.effect === 'number') {
                        await node.driver.playEffect(p.effect);
                        msg.payload = { ok: true };
                    } else if (Array.isArray(p.sequence)) {
                        await node.driver.playSequence(p.sequence);
                        msg.payload = { ok: true };
                    } else if (typeof p.rtp === 'number') {
                        await node.driver.rtpLevel(p.rtp);
                        msg.payload = { ok: true };
                    } else if (p.stop === true) {
                        await node.driver.stop();
                        msg.payload = { ok: true };
                    } else if (p.status === true) {
                        const device_id = await node.driver.readDeviceId();
                        const st = await node.driver.readStatus();
                        const supply_voltage = await node.driver.readSupplyVoltage();
                        msg.payload = { device_id, over_temp: st.overTemp, over_current: st.overCurrent, supply_voltage };
                    } else {
                        done(); return;
                    }
                    send(msg);
                    done();
                    return;
                } else {
                    done(); return;
                }
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });

        node.on('close', async function(removed, done) {
            try {
                if (node.driver) await node.driver.stop();
                if (node.connection) await node.connection.close();
            } catch (e) {
                // bus may already be gone; nothing more to do on close
            } finally {
                done();
            }
        });
    }
    RED.nodes.registerType('periph-drv2605', DRV2605Node);
};
