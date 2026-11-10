'use strict';

module.exports = function(RED) {
    const { SPIConnection } = require('periph/src/connection/spi');
    const { TMC2130Full } = require('periph/src/chips/motor/tmc2130');

    function gpioOut(spec) {
        // "<chip>:<line>", e.g. "0:17" -- gpiod chip number and line offset
        const [chip, line] = String(spec).split(':').map((v) => parseInt(v, 10));
        const { Default } = require('opengpio');
        const { GpioOutputPin } = require('periph/src/connection/output_pin');
        return new GpioOutputPin(Default.output({ chip, line }));
    }

    function TMC2130Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.ready = false;
        node.speed = parseFloat(config.speed) || 200;

        (async () => {
            try {
                const connection = new SPIConnection(parseInt(config.bus, 10) || 0, parseInt(config.device, 10) || 0, {
                    mode: 3,
                    maxSpeedHz: parseInt(config.speedHz, 10) || 1000000,
                });
                node.connection = connection;
                node.driver = new TMC2130Full(connection, gpioOut(config.stepGpio), gpioOut(config.dirGpio), {
                    rSenseOhm: parseFloat(config.rsense) || 0.11,
                    enPin: config.enGpio ? gpioOut(config.enGpio) : null,
                });
                await node.driver.init();
                const current = parseFloat(config.current);
                if (current > 0) await node.driver.setCurrent(current);
                const microsteps = parseInt(config.microsteps, 10);
                if (microsteps > 0) await node.driver.setMicrosteps(microsteps);
                node.ready = true;
            } catch (e) {
                node.error('TMC2130 init failed: ' + e.message);
            }
        })();

        async function snapshot() {
            const status = await node.driver.readStatus();
            return {
                standstill: status.stst,
                fault: await node.driver.readFault(),
                cs_actual: status.cs_actual,
                sg_result: status.sg_result,
            };
        }

        node.on('input', async function(msg, send, done) {
            if (!node.driver || !node.ready) { done(); return; }
            try {
                const p = msg.payload;
                let handled = false;
                if (p && typeof p === 'object') {
                    if (typeof p.microsteps === 'number') { await node.driver.setMicrosteps(p.microsteps); handled = true; }
                    if (typeof p.current === 'number') { await node.driver.setCurrent(p.current); handled = true; }
                    if (typeof p.stall_threshold === 'number') { await node.driver.setStallThreshold(p.stall_threshold); handled = true; }
                    if (typeof p.spreadcycle === 'boolean') { await node.driver.setChopperMode(p.spreadcycle); handled = true; }
                    if (typeof p.enable === 'boolean') {
                        if (p.enable) await node.driver.enable(); else await node.driver.disable();
                        handled = true;
                    }
                    if (typeof p.move === 'number') {
                        await node.driver.move(p.move, typeof p.speed === 'number' ? p.speed : node.speed);
                        handled = true;
                    }
                }
                if (handled) {
                    msg.payload = await snapshot();
                } else {
                    const status = await node.driver.readStatus();
                    msg.payload = {
                        standstill: status.stst,
                        fault: await node.driver.readFault(),
                        cs_actual: status.cs_actual,
                        sg_result: status.sg_result,
                        tstep: await node.driver.readTstep(),
                        stallguard: status.stallguard,
                        otpw: status.otpw,
                        ot: status.ot,
                    };
                }
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });

        node.on('close', async function(removed, done) {
            try {
                if (node.driver && node.ready) await node.driver.disable();
                if (node.connection) await node.connection.close();
            } catch (e) {
                // bus may already be gone; nothing more to do on close
            } finally {
                done();
            }
        });
    }
    RED.nodes.registerType('periph-tmc2130', TMC2130Node);
};
