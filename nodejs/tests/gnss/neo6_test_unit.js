'use strict';
const { Neo6ConnectionMock } = require('../../packages/periph/src/connection/neo6_mock');
const { NEO6Minimal, NEO6Full } = require('../../packages/periph/src/chips/gnss/neo6');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function closeEnough(a, b, eps = 0.001) {
    return Math.abs(a - b) < eps;
}

/** Build a $<body>*XX\r\n NMEA sentence with a correct XOR checksum. */
function nmea(body) {
    let checksum = 0;
    const bytes = Buffer.from(body, 'ascii');
    for (let i = 0; i < bytes.length; i++) checksum ^= bytes[i];
    const hex = checksum.toString(16).toUpperCase().padStart(2, '0');
    return Buffer.from(`$${body}*${hex}\r\n`, 'ascii');
}

/**
 * Build a UBX frame with a correct Fletcher checksum, mirroring
 * NEO6Full.sendUbx's own framing (verified independently here, not by
 * reusing the driver's internal ubxChecksum).
 */
function ubxFrame(msgClass, msgId, payload = Buffer.alloc(0)) {
    const length = payload.length;
    const body = Buffer.concat([Buffer.from([msgClass, msgId, length & 0xFF, (length >> 8) & 0xFF]), payload]);
    let ckA = 0, ckB = 0;
    for (let i = 0; i < body.length; i++) {
        ckA = (ckA + body[i]) & 0xFF;
        ckB = (ckB + ckA) & 0xFF;
    }
    return Buffer.concat([Buffer.from([0xB5, 0x62]), body, Buffer.from([ckA, ckB])]);
}

/**
 * Queue data and drive update() enough times to consume it all, returning
 * true if any call returned true (a GGA fix was parsed).
 */
async function feed(sensor, connection, data) {
    connection.queueBytes(data);
    let gotFix = false;
    for (let i = 0; i < data.length; i++) {
        if (await sensor.update()) gotFix = true;
    }
    return gotFix;
}

// Field lists are built explicitly and joined with ',' rather than
// hand-typed as comma-heavy literals, to avoid miscounting empty fields.
const GGA_FIX = [
    'GPGGA', '092750.000', '5321.6802', 'N', '00630.3372', 'W',
    '1', '08', '1.03', '61.7', 'M', '55.2', 'M', '', '',
].join(',');
const GGA_NO_FIX = [
    'GPGGA', '092750.000', '', '', '', '',
    '0', '00', '', '', '', '', '', '', '',
].join(',');
const RMC = [
    'GPRMC', '092750.000', 'A', '5321.6802', 'N', '00630.3372', 'W',
    '022.4', '084.4', '230394', '003.1', 'W', 'A',
].join(',');
const VTG = [
    'GPVTG', '084.4', 'T', '077.4', 'M', '022.4', 'N', '041.5', 'K', 'A',
].join(',');

if (GGA_FIX.split(',').length !== 15) throw new Error('GGA_FIX field count mismatch');
if (GGA_NO_FIX.split(',').length !== 15) throw new Error('GGA_NO_FIX field count mismatch');
if (RMC.split(',').length !== 13) throw new Error('RMC field count mismatch');

async function main() {
    // --- NEO6Minimal: GGA decode across all three bus types ---

    for (const busType of ['uart', 'i2c', 'spi']) {
        const connection = new Neo6ConnectionMock();
        const gps = new NEO6Minimal(connection, busType);
        checkTrue(`init_fix_zero[${busType}]`, gps.fix() === 0);
        checkTrue(`init_latitude_none[${busType}]`, gps.latitude() === null);

        const gotFix = await feed(gps, connection, nmea(GGA_FIX));
        checkTrue(`gga_fix_returned_true[${busType}]`, gotFix);
        checkTrue(`gga_fix_value[${busType}]`, gps.fix() === 1);
        checkTrue(`gga_satellites[${busType}]`, gps.satellites() === 8);
        checkTrue(`gga_latitude[${busType}]`, closeEnough(gps.latitude(), 53.361336667, 1e-6));
        checkTrue(`gga_longitude[${busType}]`, closeEnough(gps.longitude(), -6.505620, 1e-6));
        checkTrue(`gga_altitude[${busType}]`, closeEnough(gps.altitude(), 61.7));
    }

    // --- NEO6Minimal: no-fix GGA updates fix/satellites but not lat/lon ---

    {
        const connection = new Neo6ConnectionMock();
        const gps = new NEO6Minimal(connection);
        await feed(gps, connection, nmea(GGA_FIX));
        const gotFix = await feed(gps, connection, nmea(GGA_NO_FIX));
        checkTrue('gga_no_fix_returns_false', !gotFix);
        checkTrue('gga_no_fix_clears_fix_value', gps.fix() === 0);
        checkTrue('gga_no_fix_keeps_last_latitude', closeEnough(gps.latitude(), 53.361336667, 1e-6));
    }

    // --- Checksum validation: a corrupted sentence is silently discarded ---

    {
        const connection = new Neo6ConnectionMock();
        const gps = new NEO6Minimal(connection);
        const bad = Buffer.from(nmea(GGA_FIX));
        bad[bad.length - 4] ^= 0xFF; // corrupt one checksum hex digit
        const gotFix = await feed(gps, connection, bad);
        checkTrue('bad_checksum_discarded', !gotFix);
        checkTrue('bad_checksum_leaves_fix_zero', gps.fix() === 0);
    }

    // --- Non-GGA / non-sentence bytes before '$' are ignored ---

    {
        const connection = new Neo6ConnectionMock();
        const gps = new NEO6Minimal(connection);
        const data = Buffer.concat([Buffer.from([0xFF, 0xFF, 0xFF]), nmea(GGA_FIX)]);
        const gotFix = await feed(gps, connection, data);
        checkTrue('leading_garbage_ignored', gotFix);
    }

    // --- NEO6Full: RMC (speed/course/time/date) and VTG (course/speed) ---

    {
        const connection = new Neo6ConnectionMock();
        const gps = new NEO6Full(connection);
        await feed(gps, connection, nmea(RMC));
        checkTrue('rmc_speed', closeEnough(gps.speed(), 22.4 * 0.514444, 1e-4));
        checkTrue('rmc_course', closeEnough(gps.course(), 84.4));
        checkTrue('rmc_utc_time', gps.utcTime() === '092750.000');
        checkTrue('rmc_utc_date', gps.utcDate() === '230394');
    }

    {
        const connection2 = new Neo6ConnectionMock();
        const gps2 = new NEO6Full(connection2);
        await feed(gps2, connection2, nmea(VTG));
        checkTrue('vtg_course', closeEnough(gps2.course(), 84.4));
        checkTrue('vtg_speed', closeEnough(gps2.speed(), 41.5 / 3.6, 1e-4));
    }

    {
        const connection3 = new Neo6ConnectionMock();
        const gps3 = new NEO6Full(connection3);
        await feed(gps3, connection3, nmea(GGA_FIX));
        checkTrue('gga_hdop', closeEnough(gps3.hdop(), 1.03));
    }

    // --- NEO6Full: sendUbx / pollUbx / setRate / setPlatform / coldStart /
    // saveConfig ---

    {
        const connection = new Neo6ConnectionMock();
        const gps = new NEO6Full(connection);

        await gps.sendUbx(0x06, 0x08, Buffer.from([1, 2, 3]));
        checkTrue('send_ubx_frames_correctly',
            connection.writes[connection.writes.length - 1].equals(ubxFrame(0x06, 0x08, Buffer.from([1, 2, 3]))));

        await gps.setRate(5);
        const measRateMs = Math.trunc(1000 / 5);
        let expectedPayload = Buffer.alloc(6);
        expectedPayload.writeUInt16LE(measRateMs, 0);
        expectedPayload.writeUInt16LE(1, 2);
        expectedPayload.writeUInt16LE(0, 4);
        checkTrue('set_rate_sends_cfg_rate',
            connection.writes[connection.writes.length - 1].equals(ubxFrame(0x06, 0x08, expectedPayload)));

        await gps.setPlatform(4);
        expectedPayload = Buffer.alloc(36);
        expectedPayload.writeUInt16LE(0x0001, 0);
        expectedPayload[2] = 4;
        checkTrue('set_platform_sends_cfg_nav5',
            connection.writes[connection.writes.length - 1].equals(ubxFrame(0x06, 0x24, expectedPayload)));

        await gps.coldStart();
        expectedPayload = Buffer.alloc(4);
        expectedPayload.writeUInt16LE(0xFFFF, 0);
        expectedPayload[2] = 0x02;
        expectedPayload[3] = 0x00;
        checkTrue('cold_start_sends_cfg_rst',
            connection.writes[connection.writes.length - 1].equals(ubxFrame(0x06, 0x04, expectedPayload)));

        await gps.saveConfig();
        expectedPayload = Buffer.alloc(13);
        expectedPayload.writeUInt32LE(0x00000000, 0);
        expectedPayload.writeUInt32LE(0xFFFFFFFF, 4);
        expectedPayload.writeUInt32LE(0x00000000, 8);
        expectedPayload[12] = 0x07;
        checkTrue('save_config_sends_cfg_cfg',
            connection.writes[connection.writes.length - 1].equals(ubxFrame(0x06, 0x09, expectedPayload)));
    }

    // pollUbx(): queue a matching UBX response frame, expect its payload back.
    {
        const connection = new Neo6ConnectionMock();
        const gps = new NEO6Full(connection);
        const responsePayload = Buffer.from(Array.from({ length: 28 }, (_, i) => i));
        connection.queueBytes(ubxFrame(0x01, 0x02, responsePayload));
        const payload = await gps.pollUbx(0x01, 0x02);
        checkTrue('poll_ubx_returns_payload', payload.equals(responsePayload));
        checkTrue('poll_ubx_sends_poll_frame', connection.writes[0].equals(ubxFrame(0x01, 0x02)));
    }

    // pollUbx() rejects on ACK-NAK.
    {
        const connection = new Neo6ConnectionMock();
        const gps = new NEO6Full(connection);
        connection.queueBytes(ubxFrame(0x05, 0x00, Buffer.from([0x06, 0x08]))); // ACK-NAK for CFG-RATE
        let threw = false;
        try {
            await gps.pollUbx(0x06, 0x08);
        } catch (e) {
            threw = e instanceof Error;
        }
        checkTrue('poll_ubx_nak_raises', threw);
    }

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(err => { console.error(err); process.exit(1); });
