'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { PCF85063AFull } = require('periph/src/chips/rtc/pcf85063a');

    const SOURCE_NAMES = [
        [PCF85063AFull.SOURCE_ALARM, 'alarm'],
        [PCF85063AFull.SOURCE_TIMER, 'timer'],
    ];

    function field(enabled, value) {
        return enabled ? parseInt(value, 10) || 0 : null;
    }

    function PCF85063ANode(config) {
        RED.nodes.createNode(this, config);
        const node = this;

        try {
            const connection = new I2CConnection(parseInt(config.bus, 10), 0x51);
            node.driver = new PCF85063AFull(connection);
            node.connection = connection;

            (async () => {
                await node.driver.init();
                await node.driver.setOscillatorLoad(parseFloat(config.oscillatorLoad) === 12.5 ? 12.5 : 7);

                let sources = 0;
                if (config.alarmEnabled) {
                    await node.driver.setAlarm({
                        second:  field(config.alarmSecondEnabled, config.alarmSecond),
                        minute:  field(config.alarmMinuteEnabled, config.alarmMinute),
                        hour:    field(config.alarmHourEnabled, config.alarmHour),
                        day:     field(config.alarmDayEnabled, config.alarmDay),
                        weekday: field(config.alarmWeekdayEnabled, config.alarmWeekday),
                    });
                    sources |= PCF85063AFull.SOURCE_ALARM;
                }
                if (config.timerEnabled) {
                    await node.driver.configureTimer(parseInt(config.timerValue, 10) || 1,
                        config.timerClock || '1hz', config.timerPulsed === true);
                    sources |= PCF85063AFull.SOURCE_TIMER;
                }
                if (sources) {
                    await node.driver.enableInterrupt(sources);
                    await node.driver.onInterrupt((status) => {
                        for (const [bit, name] of SOURCE_NAMES) {
                            if (status & bit) node.send({ payload: { source: name } });
                        }
                    });
                }
            })().catch((e) => node.error('PCF85063A init failed: ' + e.message));
        } catch (e) {
            node.error('PCF85063A init failed: ' + e.message);
        }

        node.on('input', async function(msg, send, done) {
            if (!node.driver) { done(); return; }
            try {
                if (msg.payload && msg.payload.datetime) {
                    const d = msg.payload.datetime;
                    await node.driver.setDatetime(d.year, d.month, d.day, d.weekday, d.hour, d.minute, d.second);
                    done();
                    return;
                }
                if (msg.payload && msg.payload.alarm) {
                    await node.driver.setAlarm(msg.payload.alarm);
                    done();
                    return;
                }
                const dt = await node.driver.getDatetime();
                msg.payload = {
                    ...dt,
                    oscillator_stopped: await node.driver.oscillatorStopped(),
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
            } finally {
                done();
            }
        });
    }
    RED.nodes.registerType('periph-pcf85063a', PCF85063ANode);
};
