'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { DS3502Full } = require('periph/src/chips/adc_dac/ds3502');

    function DS3502Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;

        const vHigh = config.vhigh === '' || config.vhigh === undefined ? 1.0 : parseFloat(config.vhigh);
        const vLow = config.vlow === '' || config.vlow === undefined ? 0.0 : parseFloat(config.vlow);
        const address = parseInt(config.address || '0x28', 16);
        try {
            const connection = new I2CConnection(parseInt(config.bus, 10), address);
            node.driver = new DS3502Full(connection, vHigh, vLow);
            node.connection = connection;
            node.ready = node.driver.init();
            node.ready.catch((e) => node.error('DS3502 init failed: ' + e.message));
        } catch (e) {
            node.error('DS3502 init failed: ' + e.message);
        }

        async function readback(extra) {
            const code = await node.driver.getWiper();
            const ratio = await node.driver.getRatio();
            const voltage = await node.driver.getVoltage();
            return Object.assign({ code, ratio, voltage }, extra);
        }

        node.on('input', async function(msg, send, done) {
            if (!node.driver) { done(); return; }
            try {
                await node.ready;
                const p = msg.payload;
                let extra;
                if (p && typeof p === 'object') {
                    if (typeof p.code === 'number') await node.driver.setWiper(p.code);
                    else if (typeof p.ratio === 'number') await node.driver.setRatio(p.ratio);
                    else if (typeof p.voltage === 'number') await node.driver.setVoltage(p.voltage);
                    if (p.store === true) {
                        await node.driver.storeWiper();
                        extra = { stored: true };
                    }
                }
                msg.payload = await readback(extra);
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
    RED.nodes.registerType('periph-ds3502', DS3502Node);
};
