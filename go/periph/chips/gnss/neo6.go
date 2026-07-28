// Package gnss contains drivers for GNSS / GPS modules.
package gnss

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// NEO-6 NMEA / UBX framing constants.
const (
	neo6SentenceStart byte = '$' // 0x24
	neo6CR            byte = '\r'
	neo6LF            byte = '\n'
	neo6MaxSentence        = 96
	neo6MaxFields          = 24

	// UBX framing
	neo6UbxSync1 byte = 0xB5
	neo6UbxSync2 byte = 0x62

	// UBX class/ID
	neo6ClassACK byte = 0x05
	neo6IDACKNAK byte = 0x00
	neo6IDACKACK byte = 0x01

	// Max UBX payload
	neo6MaxUBXPayload = 40
)

// Errors reported by NEO6Full UBX operations.
var (
	ErrNeo6Nak     = errors.New("neo6: UBX NAK")
	ErrNeo6Timeout = errors.New("neo6: UBX response timeout")
	ErrNeo6Payload = errors.New("neo6: UBX payload too large")
)

// NEO6Minimal is the u-blox NEO-6 GNSS/GPS receiver driver — minimal
// interface.
//
// Reads bytes from the connection and assembles complete NMEA sentences
// terminated by CR/LF, validating the *XX checksum before parsing.
// Works out of the box with the module's factory defaults (NMEA output
// at 9600 baud, 1 Hz, all standard sentences enabled); no chip-side
// configuration is sent.
//
// The driver is connection-agnostic: pass the bus type via the
// busType argument:
//
//   - "uart" (default) — a UART connection. Read one byte at a time;
//     a connection that returns no bytes during the call is treated as
//     "no new byte this call", not an error.
//   - "i2c" — an I²C (DDC) connection. Each byte is fetched with a
//     random-read to register 0xFF, per the DDC protocol.
//   - "spi" — an SPI connection. Each byte is fetched with a full-duplex
//     transfer.
type NEO6Minimal struct {
	connection  connection.Connection
	busType    string
	buf        []byte
	inSentence bool

	lat       *float64
	lon       *float64
	alt       *float64
	fix       uint8
	satellites uint8

	// optional hook for Full to receive every checksum-valid sentence
	onSentence func(id string, fields []string)
}

// NewNEO6Minimal creates a new NEO6Minimal over the given connection.
//
// busType must be one of "uart", "i2c", or "spi". The default "uart"
// is the primary connection for this chip; "i2c" (DDC) and "spi" are
// supported with the same NMEA byte-stream interface.
func NewNEO6Minimal(t connection.Connection, busType string) *NEO6Minimal {
	return &NEO6Minimal{
		connection:  t,
		busType:    busType,
		buf:        make([]byte, 0, neo6MaxSentence),
		inSentence: false,
		fix:        0,
	}
}

// readByte fetches one byte if available, or returns (0, nil) if no
// byte is available yet. Only genuine bus errors are returned as the
// error.
func (d *NEO6Minimal) readByte() (byte, error) {
	switch d.busType {
	case "i2c":
		// DDC random-read: set the register pointer to 0xFF, then read
		// one stream byte. The pointer saturates at 0xFF once set, so
		// re-sending it on every byte is redundant but harmless.
		buf, err := d.connection.WriteRead([]byte{0xFF}, 1)
		if err != nil {
			return 0, err
		}
		return buf[0], nil
	case "spi":
		// SPI has no register-address concept; the write phase stays
		// empty so a real byte of the module's output is never discarded.
		buf, err := d.connection.WriteRead(nil, 1)
		if err != nil {
			return 0, err
		}
		return buf[0], nil
	default:
		// UART
		buf, err := d.connection.Read(1)
		if err != nil {
			return 0, err
		}
		if len(buf) == 0 {
			return 0, nil
		}
		return buf[0], nil
	}
}

// Update reads available bytes and parses at most one complete NMEA
// sentence.
//
// Returns true if a GGA sentence with a valid fix (fix status > 0)
// was parsed during this call.
func (d *NEO6Minimal) Update() (bool, error) {
	b, err := d.readByte()
	if err != nil {
		return false, err
	}
	if b == 0 && !d.inSentence {
		return false, nil
	}
	if b == neo6SentenceStart {
		d.buf = d.buf[:0]
		d.buf = append(d.buf, b)
		d.inSentence = true
		return false, nil
	}
	if !d.inSentence {
		return false, nil
	}
	if len(d.buf) >= neo6MaxSentence {
		d.buf = d.buf[:0]
		d.inSentence = false
		return false, nil
	}
	d.buf = append(d.buf, b)
	if b == neo6LF && len(d.buf) >= 2 && d.buf[len(d.buf)-2] == neo6CR {
		sentence := append([]byte(nil), d.buf...)
		d.buf = d.buf[:0]
		d.inSentence = false
		return d.onSentenceBytes(sentence), nil
	}
	return false, nil
}

// nmeaChecksumOK validates the *XX checksum of a $...*XX\r\n NMEA sentence.
func nmeaChecksumOK(sentence []byte) bool {
	star := -1
	for i, c := range sentence {
		if c == '*' {
			star = i
			break
		}
	}
	if star < 1 || star+4 > len(sentence) {
		return false
	}
	var cs byte
	for i := 1; i < star; i++ {
		cs ^= sentence[i]
	}
	hex := string(sentence[star+1 : star+3])
	expected, err := strconv.ParseUint(hex, 16, 8)
	if err != nil {
		return false
	}
	return cs == byte(expected)
}

// nmeaToDegrees converts NMEA ddmm.mmmm / dddmm.mmmm to signed decimal degrees.
func nmeaToDegrees(raw, hemisphere string) (float64, error) {
	v, err := strconv.ParseFloat(raw, 64)
	if err != nil {
		return 0, err
	}
	deg := int(v / 100)
	minutes := v - float64(deg)*100.0
	dec := float64(deg) + minutes/60.0
	if hemisphere == "S" || hemisphere == "W" {
		dec = -dec
	}
	return dec, nil
}

func (d *NEO6Minimal) onSentenceBytes(sentence []byte) bool {
	if !nmeaChecksumOK(sentence) {
		return false
	}
	star := -1
	for i, c := range sentence {
		if c == '*' {
			star = i
			break
		}
	}
	body := string(sentence[1:star])
	fields := strings.Split(body, ",")
	if len(fields) == 0 || len(fields[0]) < 5 {
		return false
	}
	id := fields[0][2:5]
	result := false
	if id == "GGA" {
		result = d.parseGGA(fields)
	}
	if d.onSentence != nil {
		d.onSentence(id, fields)
	}
	return result
}

func (d *NEO6Minimal) parseGGA(fields []string) bool {
	if len(fields) < 15 {
		return false
	}
	fix, _ := strconv.ParseUint(fields[6], 10, 8)
	d.fix = uint8(fix)
	sats, _ := strconv.ParseUint(fields[7], 10, 8)
	d.satellites = uint8(sats)
	if d.fix > 0 && fields[2] != "" && fields[3] != "" && fields[4] != "" && fields[5] != "" {
		lat, errLat := nmeaToDegrees(fields[2], fields[3])
		lon, errLon := nmeaToDegrees(fields[4], fields[5])
		if errLat == nil && errLon == nil {
			d.lat = &lat
			d.lon = &lon
			if fields[9] != "" {
				if alt, err := strconv.ParseFloat(fields[9], 64); err == nil {
					d.alt = &alt
				}
			}
		}
	}
	return d.fix > 0
}

// Latitude returns the latitude of the last valid fix, in decimal
// degrees (positive north). Returns nil until the first valid GGA fix.
func (d *NEO6Minimal) Latitude() *float64 { return d.lat }

// Longitude returns the longitude of the last valid fix, in decimal
// degrees (positive east). Returns nil until the first valid GGA fix.
func (d *NEO6Minimal) Longitude() *float64 { return d.lon }

// Altitude returns the height above mean sea level of the last valid
// fix, in meters. Returns nil until the first valid GGA fix.
func (d *NEO6Minimal) Altitude() *float64 { return d.alt }

// Fix returns the GGA fix quality of the last parsed GGA sentence:
// 0 = no fix, 1 = GPS, 2 = DGPS.
func (d *NEO6Minimal) Fix() uint8 { return d.fix }

// Satellites returns the number of satellites used in the last GGA fix.
func (d *NEO6Minimal) Satellites() uint8 { return d.satellites }

// UBXChecksum computes the 8-bit Fletcher checksum over the given data.
func ubxChecksum(data []byte) (ckA, ckB byte) {
	for _, b := range data {
		ckA += b
		ckB += ckA
	}
	return
}

// NEO6Full is the NEO-6 driver — full interface. Extends NEO6Minimal
// with RMC/VTG parsing (speed, course, UTC date), UBX binary messaging,
// and rate/platform configuration.
//
// Embeds NEO6Minimal to inherit Update, Latitude, Longitude, Altitude,
// Fix, and Satellites; adds Full-only methods below.
type NEO6Full struct {
	*NEO6Minimal

	speed    *float64
	course   *float64
	utcTime  string
	utcDate  string
	hdop     *float64
}

// NewNEO6Full creates a new NEO6Full and wires its onSentence hook to
// the Full-specific parsers.
func NewNEO6Full(t connection.Connection, busType string) *NEO6Full {
	m := NewNEO6Minimal(t, busType)
	f := &NEO6Full{NEO6Minimal: m}
	m.onSentence = f.handleSentence
	return f
}

func (f *NEO6Full) handleSentence(id string, fields []string) {
	switch id {
	case "GGA":
		if len(fields) > 1 && fields[1] != "" {
			f.utcTime = fields[1]
		}
		if len(fields) > 8 && fields[8] != "" {
			if v, err := strconv.ParseFloat(fields[8], 64); err == nil {
				f.hdop = &v
			}
		}
	case "RMC":
		if len(fields) >= 10 {
			if fields[1] != "" {
				f.utcTime = fields[1]
			}
			if fields[7] != "" {
				if v, err := strconv.ParseFloat(fields[7], 64); err == nil {
					ms := v * 0.514444
					f.speed = &ms
				}
			}
			if fields[8] != "" {
				if v, err := strconv.ParseFloat(fields[8], 64); err == nil {
					f.course = &v
				}
			}
			if fields[9] != "" {
				f.utcDate = fields[9]
			}
		}
	case "VTG":
		if len(fields) > 1 && fields[1] != "" {
			if v, err := strconv.ParseFloat(fields[1], 64); err == nil {
				f.course = &v
			}
		}
		if len(fields) > 7 && fields[7] != "" {
			if v, err := strconv.ParseFloat(fields[7], 64); err == nil {
				ms := v / 3.6
				f.speed = &ms
			}
		}
	}
}

// Speed returns speed over ground in m/s, converted from RMC (knots ×
// 0.514444) or VTG (km/h ÷ 3.6). Returns nil until the first speed
// field is parsed.
func (f *NEO6Full) Speed() *float64 { return f.speed }

// Course returns course over ground in degrees (0-360), from RMC or
// VTG. Returns nil until the first course field is parsed.
func (f *NEO6Full) Course() *float64 { return f.course }

// UTCTime returns the UTC time of the last GGA or RMC sentence, in
// hhmmss.ss format. Returns "" until the first sentence with a time
// field is parsed.
func (f *NEO6Full) UTCTime() string { return f.utcTime }

// UTCDate returns the UTC date of the last RMC sentence, in ddmmyy
// format. Returns "" until the first RMC sentence is parsed.
func (f *NEO6Full) UTCDate() string { return f.utcDate }

// HDOP returns the horizontal dilution of precision from the last GGA
// sentence. Returns nil until the first GGA sentence with a populated
// HDOP field is parsed.
func (f *NEO6Full) HDOP() *float64 { return f.hdop }

// SendUBX frames and writes a UBX message (adds sync bytes, length,
// checksum). payload must be at most neo6MaxUBXPayload (40) bytes.
func (f *NEO6Full) SendUBX(msgClass, msgID byte, payload []byte) error {
	if len(payload) > neo6MaxUBXPayload {
		return ErrNeo6Payload
	}
	frame := make([]byte, 0, 8+len(payload))
	frame = append(frame, neo6UbxSync1, neo6UbxSync2, msgClass, msgID)
	frame = append(frame, byte(len(payload)&0xFF), byte((len(payload)>>8)&0xFF))
	frame = append(frame, payload...)
	ckA, ckB := ubxChecksum(frame[2:])
	frame = append(frame, ckA, ckB)
	return f.connection.Write(frame)
}

// PollUBX sends a poll request and returns the response payload.
// Returns ErrNeo6Nak if the module answers with UBX ACK-NAK, or
// ErrNeo6Timeout if no matching response arrives.
func (f *NEO6Full) PollUBX(msgClass, msgID byte) ([]byte, error) {
	if err := f.SendUBX(msgClass, msgID, nil); err != nil {
		return nil, err
	}
	return f.readUBXResponse(msgClass, msgID)
}

func (f *NEO6Full) readUBXResponse(wantClass, wantID byte) ([]byte, error) {
	const maxFrames = 400
	const maxIdle = 4000
	idle := 0
	frames := 0
	for frames < maxFrames {
		b, err := f.NEO6Minimal.readByte()
		if err != nil {
			return nil, err
		}
		if b == 0 {
			idle++
			if idle > maxIdle {
				return nil, ErrNeo6Timeout
			}
			time.Sleep(time.Millisecond)
			continue
		}
		idle = 0
		if b != neo6UbxSync1 {
			continue
		}
		b, err = f.NEO6Minimal.readByte()
		if err != nil || b != neo6UbxSync2 {
			continue
		}
		header := make([]byte, 4)
		ok := true
		for i := 0; i < 4; i++ {
			b, err = f.NEO6Minimal.readByte()
			if err != nil {
				ok = false
				break
			}
			header[i] = b
		}
		if !ok {
			frames++
			continue
		}
		cls, mid, lenLo, lenHi := header[0], header[1], header[2], header[3]
		length := int(lenLo) | (int(lenHi) << 8)
		if length > neo6MaxUBXPayload {
			frames++
			continue
		}
		payload := make([]byte, length)
		got := 0
		for got < length {
			b, err = f.NEO6Minimal.readByte()
			if err != nil {
				break
			}
			payload[got] = b
			got++
		}
		if got != length {
			frames++
			continue
		}
		ckA, err := f.NEO6Minimal.readByte()
		if err != nil {
			frames++
			continue
		}
		ckB, err := f.NEO6Minimal.readByte()
		if err != nil {
			frames++
			continue
		}
		body := append([]byte{cls, mid, lenLo, lenHi}, payload...)
		expA, expB := ubxChecksum(body)
		frames++
		if ckA != expA || ckB != expB {
			continue
		}
		if cls == neo6ClassACK && mid == neo6IDACKNAK {
			return nil, fmt.Errorf("%w: class 0x%02X id 0x%02X", ErrNeo6Nak, wantClass, wantID)
		}
		if cls == wantClass && mid == wantID {
			return payload, nil
		}
	}
	return nil, ErrNeo6Timeout
}

// SetRate sets the navigation update rate via CFG-RATE. hz is the
// update rate in Hz (1-5 Hz for standard NEO-6 models).
func (f *NEO6Full) SetRate(hz uint16) error {
	measRate := uint16(1000 / hz)
	payload := []byte{
		byte(measRate & 0xFF),
		byte((measRate >> 8) & 0xFF),
		1, 0, // navRate = 1
		0, 0, // timeRef = 0 (UTC)
	}
	return f.SendUBX(0x06, 0x08, payload)
}

// SetPlatform sets the dynamic platform model via CFG-NAV5. model
// values: 0=portable, 2=stationary, 3=pedestrian, 4=automotive,
// 5=sea, 6=airborne<1g, 7=airborne<2g, 8=airborne<4g.
func (f *NEO6Full) SetPlatform(model uint8) error {
	payload := make([]byte, 36)
	payload[0] = 0x01 // mask: apply dynModel only
	payload[2] = model
	return f.SendUBX(0x06, 0x24, payload)
}

// ColdStart forces a cold start via CFG-RST (clears almanac,
// ephemeris, and last known position).
func (f *NEO6Full) ColdStart() error {
	payload := []byte{0xFF, 0xFF, 0x02, 0x00}
	return f.SendUBX(0x06, 0x04, payload)
}

// SaveConfig persists the current configuration via CFG-CFG (saves
// to battery-backed RAM and flash, where available).
func (f *NEO6Full) SaveConfig() error {
	payload := []byte{
		0x00, 0x00, 0x00, 0x00, // clearMask = 0
		0xFF, 0xFF, 0xFF, 0xFF, // saveMask = all
		0x00, 0x00, 0x00, 0x00, // loadMask = 0
		0x07, // deviceMask: BBR|Flash|EEPROM
	}
	return f.SendUBX(0x06, 0x09, payload)
}
