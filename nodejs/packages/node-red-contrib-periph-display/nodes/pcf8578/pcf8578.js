'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { PCF8578Full }  = require('periph/src/chips/display/pcf8578');

    const DISPLAY = {
        blank:   PCF8578Full.DISPLAY_BLANK,
        normal:  PCF8578Full.DISPLAY_NORMAL,
        all_on:  PCF8578Full.DISPLAY_ALL_ON,
        inverse: PCF8578Full.DISPLAY_INVERSE,
    };

    function PCF8578DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new PCF8578Full(connection, parseInt(config.rows) || 16);
            node.connection = connection;
        } catch (e) {
            node.error('PCF8578 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('pcf8578-device', PCF8578DeviceNode);

    function PCF8578Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No PCF8578 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const p = msg.payload || {};
                let handled = false;
                if (p.clear) {
                    await d.clear();
                    handled = true;
                }
                if (p.pixel) {
                    await d.setPixel(p.pixel.x, p.pixel.y, p.pixel.on !== false);
                    handled = true;
                }
                if (p.columns) {
                    await d.writeColumns(p.columns.x, p.columns.bank, p.columns.data);
                    handled = true;
                }
                if (typeof p.display === 'string') {
                    if (!(p.display in DISPLAY)) {
                        throw new Error('display must be blank, normal, all_on or inverse');
                    }
                    await d.setDisplay(DISPLAY[p.display]);
                    handled = true;
                }
                if (typeof p.scroll === 'number') {
                    await d.scroll(p.scroll);
                    handled = true;
                }
                if (!handled) {
                    node.error('Payload must contain clear, pixel, columns, display or scroll', msg);
                } else {
                    send(msg);
                }
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-pcf8578', PCF8578Node);
};
