'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { PCF8523Full } = require('periph/src/chips/rtc/pcf8523');

    const SOURCE_NAMES = [
        [PCF8523Full.SOURCE_SECOND, 'second'],
        [PCF8523Full.SOURCE_TIMER_A, 'timer_a'],
        [PCF8523Full.SOURCE_TIMER_B, 'timer_b'],
        [PCF8523Full.SOURCE_ALARM, 'alarm'],
        [PCF8523Full.SOURCE_BATTERY_SWITCH, 'battery_switch'],
        [PCF8523Full.SOURCE_BATTERY_LOW, 'battery_low'],
    ];

    function field(enabled, value) {
        return enabled ? parseInt(value, 10) || 0 : null;
    }

    function PCF8523DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus, 10), 0x68);
            node.driver = new PCF8523Full(connection);
            node.connection = connection;

            (async () => {
                await node.driver.init();
                await node.driver.configureBatteryBackup(config.batteryMode || 'standard', config.lowDetection !== false);

                let sources = 0;
                if (config.alarmEnabled) {
                    await node.driver.setAlarm({
                        minute:  field(config.alarmMinuteEnabled, config.alarmMinute),
                        hour:    field(config.alarmHourEnabled, config.alarmHour),
                        day:     field(config.alarmDayEnabled, config.alarmDay),
                        weekday: field(config.alarmWeekdayEnabled, config.alarmWeekday),
                    });
                    sources |= PCF8523Full.SOURCE_ALARM;
                }
                if (config.timerAEnabled) {
                    await node.driver.configureTimerA(config.timerAMode || 'countdown',
                        parseInt(config.timerAValue, 10) || 0, config.timerAClock || '1hz');
                    sources |= PCF8523Full.SOURCE_TIMER_A;
                }
                if (config.timerBEnabled) {
                    await node.driver.configureTimerB(parseInt(config.timerBValue, 10) || 0, config.timerBClock || '1hz');
                    sources |= PCF8523Full.SOURCE_TIMER_B;
                }
                if (sources) {
                    // INT1 is shared with CLKOUT: free it so the interrupts reach the pin.
                    await node.driver.disableClockOutput();
                    await node.driver.enableInterrupt(sources);
                    await node.driver.onInterrupt((status) => {
                        for (const [bit, name] of SOURCE_NAMES) {
                            if (status & bit) node.emit('periph-data', { payload: { source: name } });
                        }
                    });
                }
            })().catch((e) => node.error('PCF8523 init failed: ' + e.message));
        } catch (e) {
            node.error('PCF8523 init failed: ' + e.message);
        }

        node.on('close', async function(removed, done) {
            try {
                if (node.driver) await node.driver.offInterrupt();
                if (node.connection) await node.connection.close();
            } finally {
                done();
            }
        });
    }
    RED.nodes.registerType('pcf8523-device', PCF8523DeviceNode);

    function PCF8523ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};
        if (deviceNode.on) {
            const onData = (m) => node.send(m);
            deviceNode.on('periph-data', onData);
            node.on('close', () => deviceNode.removeListener('periph-data', onData));
        }

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) { done(); return; }
            try {
                if (msg.payload && msg.payload.datetime) {
                    const d = msg.payload.datetime;
                    await deviceNode.driver.setDatetime(d.year, d.month, d.day, d.weekday, d.hour, d.minute, d.second);
                    done();
                    return;
                }
                if (msg.payload && msg.payload.alarm) {
                    await deviceNode.driver.setAlarm(msg.payload.alarm);
                    done();
                    return;
                }
                const dt = await deviceNode.driver.getDatetime();
                msg.payload = {
                    ...dt,
                    battery_low: await deviceNode.driver.isBatteryLow(),
                    battery_switched_over: await deviceNode.driver.isBatterySwitchedOver(),
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('pcf8523', PCF8523ReadNode);
};
