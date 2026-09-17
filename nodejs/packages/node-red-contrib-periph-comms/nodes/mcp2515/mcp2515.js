'use strict';

module.exports = function(RED) {
    const SPIConnection = require('periph/src/connection/spi').SPIConnection;
    const MCP2515Full   = require('periph/src/chips/comms/mcp2515').MCP2515Full;

    function MCP2515Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;

        try {
            const connection = new SPIConnection(
                parseInt(config.bus),
                parseInt(config.device),
                { mode: 0, maxSpeedHz: 10_000_000 }
            );
            const bitrate      = parseInt(config.bitrate) || 125;
            const mode         = config.mode || 'both';
            const pollInterval = parseInt(config.pollInterval) || 10;
            const chip = new MCP2515Full(connection, bitrate);
            chip.init(bitrate, 8).then(() => {
                node.chip = chip;
                node.connection = connection;
                node.mode = mode;
                if (mode === 'receive' || mode === 'both') {
                    node.pollHandle = setInterval(async () => {
                        if (!node.chip) return;
                        const frame = await node.chip.recv(0);
                        if (frame) {
                            const msg = {
                                payload: {
                                    id: frame.id,
                                    data: Array.from(frame.data),
                                    extended: frame.extended,
                                    rtr: frame.rtr,
                                },
                            };
                            node.send(msg);
                        }
                    }, pollInterval);
                }
            }).catch((err) => {
                node.error('MCP2515 init failed: ' + err.message);
            });
        } catch (e) {
            node.error('MCP2515 init failed: ' + e.message);
        }

        node.on('input', async function(msg, send, done) {
            if (!node.chip) { done(new Error('MCP2515 not initialised')); return; }
            try {
                const p = msg.payload || {};
                if (p.command === 'send') {
                    const data = Buffer.isBuffer(p.data) ? p.data : Buffer.from(Array.from(p.data || []));
                    await node.chip.send(p.id, data, !!p.extended);
                    msg.payload = { sent: true, bytes: data.length };
                    send(msg); done();
                    return;
                }
                if (p.command === 'recv') {
                    const frame = await node.chip.recv(parseInt(p.timeout) || 0);
                    msg.payload = frame ? {
                        id: frame.id,
                        data: Array.from(frame.data),
                        extended: frame.extended,
                        rtr: frame.rtr,
                    } : null;
                    send(msg); done();
                    return;
                }
                if (p.command === 'set_mode') {
                    await node.chip.setMode(p.mode);
                    msg.payload = { mode: await node.chip.getMode() };
                    send(msg); done();
                    return;
                }
                done(new Error('payload.command must be "send", "recv", or "set_mode"'));
            } catch (e) { done(e); }
        });

        node.on('close', function() {
            if (node.pollHandle) clearInterval(node.pollHandle);
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('periph-mcp2515', MCP2515Node);
};
