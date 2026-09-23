'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { PollingInputPin } = require('periph/src/connection/input_pin');
    const { VL53L0XFull } = require('periph/src/chips/tof/vl53l0x');

    const THRESHOLD_SOURCES = {
        level_low: VL53L0XFull.SOURCE_LEVEL_LOW,
        level_high: VL53L0XFull.SOURCE_LEVEL_HIGH,
        out_of_window: VL53L0XFull.SOURCE_OUT_OF_WINDOW,
    };
    const SOURCE_NAMES = {};
    for (const [name, value] of Object.entries(THRESHOLD_SOURCES)) SOURCE_NAMES[value] = name;

    function VL53L0XNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const continuous = config.mode === 'continuous';
        const periodMs = parseInt(config.periodMs, 10) || 0;
        const threshold = continuous ? THRESHOLD_SOURCES[config.threshold] : undefined;

        // Every driver call goes through one queue: the input handler and the
        // interrupt callback must not interleave their I²C sequences.
        let queue = Promise.resolve();
        const serial = (fn) => {
            const run = queue.then(fn);
            queue = run.catch(() => {});
            return run;
        };

        try {
            const address = parseInt(config.address, 16) || VL53L0XFull.I2C_ADDRESS;
            let intPin = null;
            const line = parseInt(config.gpioLine, 10);
            if (threshold && !isNaN(line)) {
                try {
                    const { Default } = require('opengpio');
                    const input = Default.input({ chip: 0, line });
                    node.gpio = input;
                    intPin = new PollingInputPin(() => (input.value ? 1 : 0));
                } catch (e) {
                    node.warn('GPIO1 line unavailable, polling the status over I²C instead: ' + e.message);
                }
            }
            const connection = new I2CConnection(parseInt(config.bus, 10), address, intPin);
            node.driver = new VL53L0XFull(connection);
            node.connection = connection;

            serial(async () => {
                await node.driver.init();
                await node.driver.setProfile(config.profile || 'default');
                if (threshold) {
                    await node.driver.setInterruptThresholds(parseInt(config.lowMm, 10), parseInt(config.highMm, 10));
                    await node.driver.enableInterrupt(threshold);
                    await node.driver.onInterrupt((status) => serial(async () => {
                        const m = await node.driver.readMeasurement();
                        node.send({ payload: { source: SOURCE_NAMES[status] || config.threshold, distance_mm: m.distanceMm } });
                    }).catch((e) => node.error('VL53L0X interrupt read failed: ' + e.message)));
                }
                if (continuous) await node.driver.startContinuous(periodMs);
                node.status({ fill: 'green', shape: 'dot', text: continuous ? 'continuous' : 'ready' });
            }).catch((e) => {
                node.status({ fill: 'red', shape: 'ring', text: 'init failed' });
                node.error('VL53L0X init failed: ' + e.message);
            });
        } catch (e) {
            node.error('VL53L0X init failed: ' + e.message);
        }

        node.on('input', function(msg, send, done) {
            if (!node.driver) { done(); return; }
            serial(async () => {
                if (!continuous) await node.driver.distance();
                const m = await node.driver.readMeasurement();
                msg.payload = {
                    distance_mm: m.distanceMm,
                    valid: m.rangeStatus === VL53L0XFull.RANGE_STATUS_VALID,
                    range_status: m.rangeStatus,
                    signal_rate_mcps: m.signalRateMcps,
                    ambient_rate_mcps: m.ambientRateMcps,
                };
                send(msg);
            }).then(() => done(), (e) => done(e));
        });

        node.on('close', function(removed, done) {
            serial(async () => {
                if (node.driver) {
                    await node.driver.offInterrupt();
                    if (continuous) await node.driver.stopContinuous();
                }
                if (node.connection) await node.connection.close();
                if (node.gpio && node.gpio.stop) node.gpio.stop();
            }).catch(() => {
                // bus may already be gone; nothing more to do on close
            }).finally(() => done());
        });
    }
    RED.nodes.registerType('periph-vl53l0x', VL53L0XNode);
};
