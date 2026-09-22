'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { DS3231Full } = require('periph/src/chips/rtc/ds3231');

    function DS3231Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;

        try {
            const connection = new I2CConnection(parseInt(config.bus, 10), 0x68);
            node.driver = new DS3231Full(connection);
            node.connection = connection;

            (async () => {
                await node.driver.init();

                // Alarm 1 / Alarm 2 and the square wave all share INT/SQW's
                // INTCN bit — the editor UI keeps them mutually exclusive,
                // but guard here too since a flow could be imported with
                // both set.
                const alarmsEnabled = config.alarm1Enabled || config.alarm2Enabled;

                if (config.alarm1Enabled) {
                    await node.driver.setAlarm1(
                        parseInt(config.alarm1Second, 10) || 0,
                        parseInt(config.alarm1Minute, 10) || 0,
                        parseInt(config.alarm1Hour, 10) || 0,
                        parseInt(config.alarm1DayOrDate, 10) || 1,
                        !!config.alarm1IsDayOfWeek,
                        parseInt(config.alarm1MatchMode, 10));
                    await node.driver.enableInterrupt(DS3231Full.SOURCE_ALARM1);
                }
                if (config.alarm2Enabled) {
                    await node.driver.setAlarm2(
                        parseInt(config.alarm2Minute, 10) || 0,
                        parseInt(config.alarm2Hour, 10) || 0,
                        parseInt(config.alarm2DayOrDate, 10) || 1,
                        !!config.alarm2IsDayOfWeek,
                        parseInt(config.alarm2MatchMode, 10));
                    await node.driver.enableInterrupt(DS3231Full.SOURCE_ALARM2);
                }
                if (alarmsEnabled) {
                    await node.driver.onInterrupt((status) => {
                        if (status & DS3231Full.SOURCE_ALARM1) node.send({ payload: { source: 'alarm1' } });
                        if (status & DS3231Full.SOURCE_ALARM2) node.send({ payload: { source: 'alarm2' } });
                    });
                } else if (config.squareWaveEnabled) {
                    await node.driver.enableSquareWave(
                        parseInt(config.squareWaveRate, 10) || 8192,
                        !!config.squareWaveBatteryBacked);
                }
            })().catch((e) => node.error('DS3231 init failed: ' + e.message));
        } catch (e) {
            node.error('DS3231 init failed: ' + e.message);
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
                const dt = await node.driver.getDatetime();
                const temperatureC = await node.driver.readTemperature();
                msg.payload = { ...dt, temperature_c: temperatureC };
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
    RED.nodes.registerType('periph-ds3231', DS3231Node);
};
