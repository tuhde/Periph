'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { TMP117Full } = require('periph/src/chips/temperature/tmp117');

    const SOURCE_NAMES = [
        [TMP117Full.SOURCE_HIGH, 'high'],
        [TMP117Full.SOURCE_LOW, 'low'],
    ];

    function TMP117Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const averaging = parseInt(config.averaging, 10);
        const cycleSeconds = parseFloat(config.cycleSeconds) || 1.0;

        try {
            const address = parseInt(config.address, 16) || TMP117Full.I2C_ADDRESS;
            const connection = new I2CConnection(parseInt(config.bus, 10), address);
            node.driver = new TMP117Full(connection);
            node.connection = connection;

            (async () => {
                await node.driver.init();
                await node.driver.configure('continuous', isNaN(averaging) ? 8 : averaging, cycleSeconds);
                if (config.alertEnabled) {
                    await node.driver.setHighLimit(parseFloat(config.highLimit));
                    await node.driver.setLowLimit(parseFloat(config.lowLimit));
                    await node.driver.configureAlert(config.alertMode || 'alert',
                        config.polarity || 'active_low', 'alert');
                    await node.driver.onInterrupt((status) => {
                        for (const [bit, name] of SOURCE_NAMES) {
                            if (status & bit) node.send({ payload: { source: name } });
                        }
                    });
                }
            })().catch((e) => node.error('TMP117 init failed: ' + e.message));
        } catch (e) {
            node.error('TMP117 init failed: ' + e.message);
        }

        node.on('input', async function(msg, send, done) {
            if (!node.driver) { done(); return; }
            try {
                const p = msg.payload;
                if (p && typeof p === 'object' && typeof p.mode === 'string') {
                    await node.driver.configure(p.mode, isNaN(averaging) ? 8 : averaging, cycleSeconds);
                    done();
                    return;
                }
                const temperature = await node.driver.readTemperature();
                const status = await node.driver.pollInterrupt();
                msg.payload = {
                    temperature_c: temperature,
                    high_alert: (status & TMP117Full.SOURCE_HIGH) !== 0,
                    low_alert: (status & TMP117Full.SOURCE_LOW) !== 0,
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });

        node.on('close', async function(removed, done) {
            try {
                if (node.driver) await node.driver.offInterrupt();
                if (node.connection) await node.connection.close();
            } catch (e) {
                // bus may already be gone; nothing more to do on close
            } finally {
                done();
            }
        });
    }
    RED.nodes.registerType('periph-tmp117', TMP117Node);
};
