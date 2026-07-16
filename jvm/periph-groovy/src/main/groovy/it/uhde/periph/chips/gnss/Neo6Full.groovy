package it.uhde.periph.chips.gnss

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport

/**
 * NEO-6 with UBX binary messaging, rate/platform configuration, and richer
 * NMEA fields (speed, course, UTC time/date, HDOP).
 *
 * <p>Extends {@link Neo6Minimal}; all Minimal methods are inherited unchanged.
 */
@CompileStatic
class Neo6Full extends Neo6Minimal {

    private static final int UBX_SYNC1 = 0xB5
    private static final int UBX_SYNC2 = 0x62
    private static final int CLASS_ACK = 0x05
    private static final int ID_ACK_NAK = 0x00
    private static final int MAX_FRAMES = 400
    private static final int MAX_IDLE = 4000

    private Double speedValue
    private Double courseValue
    private String utcTimeValue
    private String utcDateValue
    private Double hdopValue

    Neo6Full(Transport transport) {
        super(transport)
    }

    Neo6Full(Transport transport, BusType busType) {
        super(transport, busType)
    }

    @Override
    protected void handleExtra(String sentenceId, String[] fields) {
        switch (sentenceId) {
            case 'GGA':
                if (fields.length > 1 && !fields[1].isEmpty()) utcTimeValue = fields[1]
                if (fields.length > 8 && !fields[8].isEmpty()) {
                    try {
                        hdopValue = Double.parseDouble(fields[8])
                    } catch (NumberFormatException ignored) { }
                }
                break
            case 'RMC':
                parseRmc(fields)
                break
            case 'VTG':
                parseVtg(fields)
                break
            default:
                break
        }
    }

    private void parseRmc(String[] fields) {
        if (fields.length < 10) return
        if (!fields[1].isEmpty()) utcTimeValue = fields[1]
        try {
            if (!fields[7].isEmpty()) speedValue = Double.parseDouble(fields[7]) * 0.514444d
            if (!fields[8].isEmpty()) courseValue = Double.parseDouble(fields[8])
        } catch (NumberFormatException ignored) { }
        if (!fields[9].isEmpty()) utcDateValue = fields[9]
    }

    private void parseVtg(String[] fields) {
        try {
            if (fields.length > 1 && !fields[1].isEmpty()) courseValue = Double.parseDouble(fields[1])
            if (fields.length > 7 && !fields[7].isEmpty()) speedValue = Double.parseDouble(fields[7]) / 3.6d
        } catch (NumberFormatException ignored) { }
    }

    /** @return speed over ground in m/s (from RMC/VTG), or null until the first speed field is parsed */
    Double speed() { speedValue }

    /** @return course over ground in degrees 0-360 (from RMC/VTG), or null until the first course field is parsed */
    Double course() { courseValue }

    /** @return UTC time of the last GGA or RMC sentence, "hhmmss.ss", or null until parsed */
    String utcTime() { utcTimeValue }

    /** @return UTC date of the last RMC sentence, "ddmmyy", or null until parsed */
    String utcDate() { utcDateValue }

    /** @return horizontal dilution of precision from the last GGA sentence, or null until parsed */
    Double hdop() { hdopValue }

    /** Frame and write a UBX message with an empty payload (a poll request). */
    void sendUbx(int msgClass, int msgId) {
        sendUbx(msgClass, msgId, new byte[0])
    }

    /** Frame and write a UBX message (adds sync bytes, length, checksum). */
    void sendUbx(int msgClass, int msgId, byte[] payload) {
        int length = payload.length
        byte[] body = new byte[4 + length]
        body[0] = (byte) msgClass
        body[1] = (byte) msgId
        body[2] = (byte) (length & 0xFF)
        body[3] = (byte) ((length >> 8) & 0xFF)
        System.arraycopy(payload, 0, body, 4, length)
        int[] cs = ubxChecksum(body)
        byte[] frame = new byte[2 + body.length + 2]
        frame[0] = (byte) UBX_SYNC1
        frame[1] = (byte) UBX_SYNC2
        System.arraycopy(body, 0, frame, 2, body.length)
        frame[frame.length - 2] = (byte) cs[0]
        frame[frame.length - 1] = (byte) cs[1]
        transport.write(frame)
    }

    /**
     * Send a poll request and return the response payload.
     *
     * @throws IOException if the module answers with ACK-NAK, no matching
     *   response arrives before the internal idle budget is spent, or a
     *   transport error occurs
     */
    byte[] pollUbx(int msgClass, int msgId) {
        sendUbx(msgClass, msgId, new byte[0])
        return readUbxResponse(msgClass, msgId)
    }

    private byte[] readUbxResponse(int wantClass, int wantId) {
        int idle = 0
        int frames = 0
        while (frames < MAX_FRAMES) {
            Integer b = readByte()
            if (b == null) {
                idle++
                if (idle > MAX_IDLE) throw new IOException('UBX response timeout')
                continue
            }
            idle = 0
            if (b != UBX_SYNC1) continue
            Integer sync2 = readByte()
            if (sync2 == null || sync2 != UBX_SYNC2) continue
            Integer cls = readByte()
            Integer mid = readByte()
            Integer lenLo = readByte()
            Integer lenHi = readByte()
            if (cls == null || mid == null || lenLo == null || lenHi == null) continue
            int length = lenLo | (lenHi << 8)
            byte[] payload = new byte[length]
            int got = 0
            for (; got < length; got++) {
                Integer pb = readByte()
                if (pb == null) break
                payload[got] = (byte) pb.intValue()
            }
            if (got != length) {
                frames++
                continue
            }
            Integer ckA = readByte()
            Integer ckB = readByte()
            byte[] header = [(byte) cls.intValue(), (byte) mid.intValue(),
                              (byte) lenLo.intValue(), (byte) lenHi.intValue()] as byte[]
            byte[] checked = new byte[4 + length]
            System.arraycopy(header, 0, checked, 0, 4)
            System.arraycopy(payload, 0, checked, 4, length)
            int[] expected = ubxChecksum(checked)
            frames++
            if (ckA == null || ckB == null || ckA != expected[0] || ckB != expected[1]) continue
            if (cls == CLASS_ACK && mid == ID_ACK_NAK) {
                throw new IOException(String.format('UBX NAK for class 0x%02X id 0x%02X', wantClass, wantId))
            }
            if (cls == wantClass && mid == wantId) return payload
        }
        throw new IOException('UBX response timeout')
    }

    /** Set the navigation update rate via CFG-RATE. hz: 1-5 Hz for standard NEO-6 models. */
    void setRate(int hz) {
        int measRateMs = 1000.intdiv(hz)
        byte[] payload = [
            (byte) (measRateMs & 0xFF), (byte) ((measRateMs >> 8) & 0xFF),
            (byte) 1, (byte) 0,
            (byte) 0, (byte) 0
        ] as byte[]
        sendUbx(0x06, 0x08, payload)
    }

    /**
     * Set the dynamic platform model via CFG-NAV5.
     *
     * model: 0=portable, 2=stationary, 3=pedestrian, 4=automotive, 5=sea,
     * 6=airborne&lt;1g, 7=airborne&lt;2g, 8=airborne&lt;4g.
     */
    void setPlatform(int model) {
        byte[] payload = new byte[36]
        payload[0] = 0x01 // mask: apply dynModel only
        payload[1] = 0x00
        payload[2] = (byte) (model & 0xFF)
        sendUbx(0x06, 0x24, payload)
    }

    /** Force a cold start via CFG-RST (clears almanac, ephemeris, and last known position). */
    void coldStart() {
        byte[] payload = [(byte) 0xFF, (byte) 0xFF, (byte) 0x02, (byte) 0x00] as byte[]
        sendUbx(0x06, 0x04, payload)
    }

    /** Persist the current configuration via CFG-CFG (saves to battery-backed RAM and flash, where available). */
    void saveConfig() {
        byte[] payload = [
            0, 0, 0, 0,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            0, 0, 0, 0,
            0x07
        ] as byte[]
        sendUbx(0x06, 0x09, payload)
    }
}
