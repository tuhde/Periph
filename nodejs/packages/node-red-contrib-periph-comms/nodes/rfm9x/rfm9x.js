'use strict';

const VARIANTS = {
    'RFM95W': { class: 'RFM95Full', freqMin: 862000000, freqMax: 1020000000 },
    'RFM96W': { class: 'RFM96Full', freqMin: 410000000, freqMax:  525000000 },
    'RFM97W': { class: 'RFM97Full', freqMin: 862000000, freqMax: 1020000000 },
    'RFM98W': { class: 'RFM98Full', freqMin: 410000000, freqMax:  525000000 },
};

module.exports = function(RED) {
    const { SPIConnection } = require('periph/src/connection/spi');
    const rfm9x             = require('periph/src/chips/comms/rfm9x');

    function RFM9xDeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const variant = VARIANTS[config.variant] || VARIANTS['RFM95W'];
            const connection = new SPIConnection(
                parseInt(config.bus),
                parseInt(config.device),
                { mode: 0, maxSpeedHz: 5_000_000 }
            );
            const cls = rfm9x[variant.class];
            const radio = new cls(connection, parseInt(config.frequency));
            radio.init().then(() => {
                radio.configure(parseInt(config.sf), parseFloat(config.bandwidth), parseInt(config.cr), !!config.crc);
                radio.setTxPower(parseInt(config.power), true);
                node.radio = radio;
                node.connection = connection;
            });
        } catch (e) {
            node.error('RFM9x init failed: ' + e.message);
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('rfm9x-device', RFM9xDeviceNode);

    function RFM9xReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.radio) { done(new Error('RFM9x not initialised')); return; }
            try {
                const d = deviceNode.radio;
                const p = msg.payload || {};

                if (p.command === 'send') {
                    let data = p.data;
                    if (typeof data === 'string') data = Buffer.from(data, 'utf8');
                    await d.send(Buffer.from(data));
                    msg.payload = { sent: true, bytes: Buffer.from(data).length };
                    send(msg);
                    done();
                    return;
                }
                if (p.command === 'receive') {
                    const buf = await d.receive(parseInt(p.timeout) || 1000);
                    msg.payload = {
                        data: buf,
                        rssi: buf ? await d.lastPacketRssi() : null,
                        snr:  buf ? await d.lastPacketSnr()  : null,
                    };
                    send(msg);
                    done();
                    return;
                }
                if (p.command === 'frequency') {
                    await d.setFrequency(parseInt(p.frequency));
                    msg.payload = { frequency: d._frequencyHz };
                    send(msg);
                    done();
                    return;
                }

                done(new Error('payload.command must be "send", "receive", or "frequency"'));
            } catch (e) { done(e); }
        });
    }
    RED.nodes.registerType('rfm9x', RFM9xReadNode);
};
