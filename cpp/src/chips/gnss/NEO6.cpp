#include "NEO6.h"
#include <stdlib.h>
#include <string.h>

namespace {

int hexVal(uint8_t c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
}

bool nmeaChecksumOk(const uint8_t* sentence, size_t len) {
    size_t star = 0;
    bool found = false;
    for (size_t i = 1; i < len; i++) {
        if (sentence[i] == '*') { star = i; found = true; break; }
    }
    if (!found || star < 1 || star + 4 > len) return false;
    uint8_t cs = 0;
    for (size_t i = 1; i < star; i++) cs ^= sentence[i];
    int hi = hexVal(sentence[star + 1]);
    int lo = hexVal(sentence[star + 2]);
    if (hi < 0 || lo < 0) return false;
    return cs == static_cast<uint8_t>((hi << 4) | lo);
}

float nmeaToDegrees(const char* raw, char hemisphere) {
    float value = static_cast<float>(atof(raw));
    int deg = static_cast<int>(value / 100.0f);
    float minutes = value - deg * 100.0f;
    float decimal = deg + minutes / 60.0f;
    if (hemisphere == 'S' || hemisphere == 'W') decimal = -decimal;
    return decimal;
}

void ubxChecksum(const uint8_t* data, size_t len, uint8_t& ckA, uint8_t& ckB) {
    ckA = 0;
    ckB = 0;
    for (size_t i = 0; i < len; i++) {
        ckA = static_cast<uint8_t>(ckA + data[i]);
        ckB = static_cast<uint8_t>(ckB + ckA);
    }
}

}  // namespace

int NEO6Minimal::_splitFields(char* body, char** fields, int maxFields) {
    int n = 0;
    if (maxFields <= 0) return 0;
    fields[n++] = body;
    for (char* p = body; *p != '\0'; p++) {
        if (*p == ',') {
            *p = '\0';
            if (n < maxFields) {
                fields[n++] = p + 1;
            }
        }
    }
    return n;
}

NEO6Minimal::NEO6Minimal(Transport& transport, NEO6BusType bus_type)
    : _transport(transport), _bus_type(bus_type)
{
}

bool NEO6Minimal::_tryReadByte(uint8_t& out) {
    if (_bus_type == NEO6BusType::Uart) {
        // Only calling read() once a byte is confirmed queued avoids the
        // "garbage on timeout" problem: UARTTransport::read() has a void
        // return with no way to signal a partial/failed read, and the
        // Zephyr/Linux GCC variants either block or throw on timeout, which
        // this shared, no-exception driver code cannot rely on catching.
        if (_transport.available() == 0)
            return false;
        _transport.read(&out, 1);
        return true;
    }
    if (_bus_type == NEO6BusType::I2c) {
        // DDC random-read: set the register pointer to 0xFF, then read one
        // stream byte. The pointer saturates at 0xFF once set, so
        // re-sending it on every byte is redundant but harmless.
        static const uint8_t reg = 0xFF;
        _transport.write_read(&reg, 1, &out, 1);
        return true;
    }
    // SPI has no register-address concept, so the write phase must stay
    // empty: write_read(data, data_len, buf, buf_len) clocks data_len
    // response bytes and discards them, and any non-empty data_len here
    // would throw away a real byte of the module's output stream. An
    // empty write phase makes the whole call one true 1:1 full-duplex
    // transfer, so no incoming byte is ever discarded (MOSI carries 0x00
    // instead of the spec's literal 0xFF during this call, which is
    // harmless -- see NEO6Minimal's class comment).
    _transport.write_read(nullptr, 0, &out, 1);
    return true;
}

bool NEO6Minimal::update() {
    uint8_t byte;
    if (!_tryReadByte(byte)) return false;

    if (byte == SENTENCE_START) {
        _buf[0] = byte;
        _buf_len = 1;
        _in_sentence = true;
        return false;
    }
    if (!_in_sentence) return false;

    if (_buf_len < MAX_SENTENCE) {
        _buf[_buf_len++] = byte;
    } else {
        _buf_len = 0;
        _in_sentence = false;
        return false;
    }

    if (byte == LF && _buf_len >= 2 && _buf[_buf_len - 2] == CR) {
        bool result = _onSentence(_buf, _buf_len);
        _buf_len = 0;
        _in_sentence = false;
        return result;
    }
    return false;
}

bool NEO6Minimal::_onSentence(const uint8_t* sentence, size_t len) {
    if (!nmeaChecksumOk(sentence, len)) return false;

    size_t star = 0;
    for (size_t i = 1; i < len; i++) {
        if (sentence[i] == '*') { star = i; break; }
    }
    if (star < 6) return false;

    char body[MAX_SENTENCE];
    size_t bodyLen = star - 1;
    if (bodyLen >= sizeof(body)) return false;
    memcpy(body, sentence + 1, bodyLen);
    body[bodyLen] = '\0';

    char* fields[MAX_FIELDS];
    int nFields = _splitFields(body, fields, MAX_FIELDS);
    if (nFields < 1 || strlen(fields[0]) < 5) return false;

    char sentenceId[4] = { fields[0][2], fields[0][3], fields[0][4], '\0' };

    bool result = false;
    if (strcmp(sentenceId, "GGA") == 0) {
        result = _parseGga(fields, nFields);
    }
    _handleExtra(sentenceId, fields, nFields);
    return result;
}

bool NEO6Minimal::_parseGga(char** fields, int nFields) {
    if (nFields < 15) return false;

    int fixVal = fields[6][0] != '\0' ? atoi(fields[6]) : 0;
    _fix = fixVal;
    _satellites = fields[7][0] != '\0' ? atoi(fields[7]) : 0;

    if (fixVal > 0 && fields[2][0] != '\0' && fields[3][0] != '\0' &&
        fields[4][0] != '\0' && fields[5][0] != '\0') {
        _lat = nmeaToDegrees(fields[2], fields[3][0]);
        _lon = nmeaToDegrees(fields[4], fields[5][0]);
        _alt = fields[9][0] != '\0' ? static_cast<float>(atof(fields[9])) : (0.0f / 0.0f);
    }
    return fixVal > 0;
}

// --- NEO6Full ---

NEO6Full::NEO6Full(Transport& transport, NEO6BusType bus_type)
    : NEO6Minimal(transport, bus_type)
{
}

void NEO6Full::_handleExtra(const char* sentenceId, char** fields, int nFields) {
    if (strcmp(sentenceId, "GGA") == 0) {
        if (nFields > 1 && fields[1][0] != '\0') {
            strncpy(_utcTime, fields[1], sizeof(_utcTime) - 1);
            _utcTime[sizeof(_utcTime) - 1] = '\0';
        }
        if (nFields > 8 && fields[8][0] != '\0') {
            _hdop = static_cast<float>(atof(fields[8]));
        }
    } else if (strcmp(sentenceId, "RMC") == 0) {
        _parseRmc(fields, nFields);
    } else if (strcmp(sentenceId, "VTG") == 0) {
        _parseVtg(fields, nFields);
    }
}

void NEO6Full::_parseRmc(char** fields, int nFields) {
    if (nFields < 10) return;
    if (fields[1][0] != '\0') {
        strncpy(_utcTime, fields[1], sizeof(_utcTime) - 1);
        _utcTime[sizeof(_utcTime) - 1] = '\0';
    }
    if (fields[7][0] != '\0') {
        _speed = static_cast<float>(atof(fields[7])) * 0.514444f;
    }
    if (fields[8][0] != '\0') {
        _course = static_cast<float>(atof(fields[8]));
    }
    if (fields[9][0] != '\0') {
        strncpy(_utcDate, fields[9], sizeof(_utcDate) - 1);
        _utcDate[sizeof(_utcDate) - 1] = '\0';
    }
}

void NEO6Full::_parseVtg(char** fields, int nFields) {
    if (nFields > 1 && fields[1][0] != '\0') {
        _course = static_cast<float>(atof(fields[1]));
    }
    if (nFields > 7 && fields[7][0] != '\0') {
        _speed = static_cast<float>(atof(fields[7])) / 3.6f;
    }
}

void NEO6Full::sendUbx(uint8_t msgClass, uint8_t msgId, const uint8_t* payload, size_t payloadLen) {
    uint8_t frame[8 + 64];  // sync(2) + class/id/len(4) + payload + checksum(2)
    size_t pos = 0;
    frame[pos++] = UBX_SYNC1;
    frame[pos++] = UBX_SYNC2;
    frame[pos++] = msgClass;
    frame[pos++] = msgId;
    frame[pos++] = static_cast<uint8_t>(payloadLen & 0xFF);
    frame[pos++] = static_cast<uint8_t>((payloadLen >> 8) & 0xFF);
    size_t bodyStart = 2;
    for (size_t i = 0; i < payloadLen && pos < sizeof(frame) - 2; i++) {
        frame[pos++] = payload[i];
    }
    uint8_t ckA, ckB;
    ubxChecksum(frame + bodyStart, pos - bodyStart, ckA, ckB);
    frame[pos++] = ckA;
    frame[pos++] = ckB;
    _transport.write(frame, pos);
}

bool NEO6Full::pollUbx(uint8_t msgClass, uint8_t msgId, uint8_t* outPayload, size_t& outLen, size_t maxLen) {
    sendUbx(msgClass, msgId);
    return _readUbxResponse(msgClass, msgId, outPayload, outLen, maxLen);
}

bool NEO6Full::_readUbxResponse(uint8_t wantClass, uint8_t wantId,
                                uint8_t* outPayload, size_t& outLen, size_t maxLen) {
    const int kMaxFrames = 400;
    const int kMaxIdle = 4000;
    int idle = 0;
    int frames = 0;

    while (frames < kMaxFrames) {
        uint8_t byte;
        if (!_tryReadByte(byte)) {
            if (++idle > kMaxIdle) return false;
            continue;
        }
        idle = 0;
        if (byte != UBX_SYNC1) continue;
        uint8_t sync2;
        if (!_tryReadByte(sync2) || sync2 != UBX_SYNC2) continue;

        uint8_t header[4];
        bool headerOk = true;
        for (int i = 0; i < 4; i++) {
            if (!_tryReadByte(header[i])) { headerOk = false; break; }
        }
        if (!headerOk) continue;

        uint8_t cls = header[0], mid = header[1];
        uint16_t length = static_cast<uint16_t>(header[2]) | (static_cast<uint16_t>(header[3]) << 8);

        uint8_t payload[256];
        size_t got = 0;
        bool payloadOk = true;
        for (uint16_t i = 0; i < length; i++) {
            if (got >= sizeof(payload) || !_tryReadByte(payload[got])) { payloadOk = false; break; }
            got++;
        }
        frames++;
        if (!payloadOk || got != length) continue;

        uint8_t ckA, ckB;
        if (!_tryReadByte(ckA) || !_tryReadByte(ckB)) continue;

        uint8_t expA, expB;
        uint8_t checkBuf[4 + 256] = { cls, mid, header[2], header[3] };
        memcpy(checkBuf + 4, payload, got);
        ubxChecksum(checkBuf, 4 + got, expA, expB);
        if (ckA != expA || ckB != expB) continue;

        if (cls == CLASS_ACK && mid == ID_ACK_NAK) return false;
        if (cls == wantClass && mid == wantId) {
            outLen = got < maxLen ? got : maxLen;
            memcpy(outPayload, payload, outLen);
            return true;
        }
    }
    return false;
}

void NEO6Full::setRate(int hz) {
    int measRateMs = 1000 / hz;
    uint8_t payload[6];
    payload[0] = static_cast<uint8_t>(measRateMs & 0xFF);
    payload[1] = static_cast<uint8_t>((measRateMs >> 8) & 0xFF);
    payload[2] = 1;  // navRate: always 1 cycle
    payload[3] = 0;
    payload[4] = 0;  // timeRef: 0 = UTC
    payload[5] = 0;
    sendUbx(0x06, 0x08, payload, sizeof(payload));
}

void NEO6Full::setPlatform(uint8_t model) {
    uint8_t payload[36] = {0};
    payload[0] = 0x01;  // mask lo: apply dynModel only
    payload[1] = 0x00;  // mask hi
    payload[2] = model;
    sendUbx(0x06, 0x24, payload, sizeof(payload));
}

void NEO6Full::coldStart() {
    uint8_t payload[4] = { 0xFF, 0xFF, 0x02, 0x00 };  // navBbrMask=0xFFFF, resetMode=0x02 (GNSS-only sw reset)
    sendUbx(0x06, 0x04, payload, sizeof(payload));
}

void NEO6Full::saveConfig() {
    uint8_t payload[13] = {0};
    // clearMask = 0
    payload[4] = 0xFF; payload[5] = 0xFF; payload[6] = 0xFF; payload[7] = 0xFF;  // saveMask = all
    // loadMask = 0
    payload[12] = 0x07;  // deviceMask: BBR | Flash | EEPROM
    sendUbx(0x06, 0x09, payload, sizeof(payload));
}
