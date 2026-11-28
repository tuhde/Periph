// RRH62000 all-in-one air-quality module (Renesas).
//
// The module combines a laser-scattering particulate-matter sensor, an HS40xx temperature and
// humidity sensor and a ZMOD4410 TVOC/IAQ sensor behind a fan. Over I²C (address 0x69, SEL pin
// to GND) it is register addressed: a one-byte command code is the register pointer; reading
// 0x00 returns the 37-byte measurement block with a CRC-8 (polynomial 0x31, init 0xFF). The
// module must not be accessed more often than once per second (tRD); the driver rate-limits
// itself.

package gas

import (
	"encoding/binary"
	"errors"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

const (
	rrh62000CmdRead     byte = 0x00
	rrh62000CmdData     byte = 0x40
	rrh62000CmdSleep    byte = 0x50
	rrh62000CmdClean    byte = 0x51
	rrh62000CmdReset    byte = 0x52
	rrh62000CmdMave     byte = 0x53
	rrh62000CmdTintcH   byte = 0x5A
	rrh62000CmdTintcL   byte = 0x5B
	rrh62000CmdTclean   byte = 0x5C
	rrh62000CmdSpeedFan byte = 0x63
	rrh62000CmdRmox     byte = 0x71
	rrh62000CmdUID      byte = 0x72
	rrh62000CmdArgver   byte = 0x73
	rrh62000CmdCstatus  byte = 0x74
	rrh62000CmdFwver    byte = 0x75

	rrh62000DataTimeout = 4 * time.Second
)

const (
	// RRH62000Address is the fixed 7-bit I²C address.
	RRH62000Address uint8 = 0x69
	// RRH62000BlockLength is the length of the measurement block.
	RRH62000BlockLength = 37
)

var (
	// ErrRRH62000CRC is returned when the CRC-8 of the measurement block is wrong.
	ErrRRH62000CRC = errors.New("rrh62000: CRC error")
	// ErrRRH62000Invalid is returned for an all-0xFF block (read before the start-up time).
	ErrRRH62000Invalid = errors.New("rrh62000: sample invalid (start-up)")
	// ErrRRH62000Timeout is returned when no new data arrives within 4 s.
	ErrRRH62000Timeout = errors.New("rrh62000: no new data within 4 s")
	// ErrRRH62000Argument is returned for an argument out of range.
	ErrRRH62000Argument = errors.New("rrh62000: invalid argument")
)

// RRH62000Measurement holds the main air-quality values of one sample.
type RRH62000Measurement struct {
	PM1UgM3      float64 // PM1, KCl reference, µg/m³
	PM2_5UgM3    float64 // PM2.5, KCl reference, µg/m³
	PM10UgM3     float64 // PM10, KCl reference, µg/m³
	TemperatureC float64 // temperature, °C
	HumidityRH   float64 // relative humidity, %RH
	TVOC         int     // TVOC, µg/m³ (LSB 10 µg/m³)
	ECO2Ppm      int     // estimated CO₂, ppm (400–5000)
	IAQ          float64 // UBA indoor air quality index, 1.00–5.00
}

// RRH62000Status is the decoded status word of the measurement block.
type RRH62000Status struct {
	Raw               uint16 // status word (byte0 | byte1 << 8)
	HighConcentration bool   // bit 0: dust concentration extremely high (not measurable)
	DustAccumulation  bool   // bit 1: dust accumulated inside the sensor (not measurable)
	FanSpeed          bool   // bit 2: fan speed out of the set range
	Fan               bool   // bit 3: fan malfunction or breakdown
}

// RRH62000Particles holds the particle values of the measurement block.
type RRH62000Particles struct {
	PM1UgM3        float64 // PM1, KCl reference, µg/m³
	PM2_5UgM3      float64 // PM2.5, KCl reference, µg/m³
	PM10UgM3       float64 // PM10, KCl reference, µg/m³
	PM1SmokeUgM3   float64 // PM1, cigarette-smoke reference, µg/m³
	PM2_5SmokeUgM3 float64 // PM2.5, cigarette-smoke reference, µg/m³
	PM10SmokeUgM3  float64 // PM10, cigarette-smoke reference, µg/m³
	NC0_3          float64 // particles 0.3–10 µm per cm³
	NC0_5          float64 // particles 0.5–10 µm per cm³
	NC1            float64 // particles 1–10 µm per cm³
	NC2_5          float64 // particles 2.5–10 µm per cm³
	NC4            float64 // particles 4–10 µm per cm³
}

// RRH62000Data is the complete decoded measurement block.
type RRH62000Data struct {
	Status       RRH62000Status
	Particles    RRH62000Particles
	TemperatureC float64 // °C
	HumidityRH   float64 // %RH
	TVOC         int     // µg/m³
	ECO2Ppm      int     // ppm
	IAQ          float64 // UBA index 1.00–5.00
}

// RRH62000CRC8 computes the CRC-8 of the measurement block (polynomial 0x31, init 0xFF;
// RRH62000CRC8([]byte{0xBE, 0xEF}) == 0x92).
func RRH62000CRC8(data []byte) byte {
	crc := byte(0xFF)
	for _, b := range data {
		crc ^= b
		for i := 0; i < 8; i++ {
			if crc&0x80 != 0 {
				crc = crc<<1 ^ 0x31
			} else {
				crc <<= 1
			}
		}
	}
	return crc
}

// RRH62000Decode decodes a 37-byte measurement block; it rejects an all-0xFF block and a CRC
// mismatch.
func RRH62000Decode(b []byte) (RRH62000Data, error) {
	if len(b) != RRH62000BlockLength {
		return RRH62000Data{}, ErrRRH62000Argument
	}
	allFF := true
	for _, x := range b[:36] {
		if x != 0xFF {
			allFF = false
			break
		}
	}
	if allFF {
		return RRH62000Data{}, ErrRRH62000Invalid
	}
	if RRH62000CRC8(b[:36]) != b[36] {
		return RRH62000Data{}, ErrRRH62000CRC
	}
	w := func(i int) float64 { return float64(binary.BigEndian.Uint16(b[i:])) }
	raw := uint16(b[0]) | uint16(b[1])<<8
	return RRH62000Data{
		Status: RRH62000Status{
			Raw:               raw,
			HighConcentration: raw&0x01 != 0,
			DustAccumulation:  raw&0x02 != 0,
			FanSpeed:          raw&0x04 != 0,
			Fan:               raw&0x08 != 0,
		},
		Particles: RRH62000Particles{
			NC0_3:          w(2) / 10,
			NC0_5:          w(4) / 10,
			NC1:            w(6) / 10,
			NC2_5:          w(8) / 10,
			NC4:            w(10) / 10,
			PM1UgM3:        w(12) / 10,
			PM2_5UgM3:      w(14) / 10,
			PM10UgM3:       w(16) / 10,
			PM1SmokeUgM3:   w(18) / 10,
			PM2_5SmokeUgM3: w(20) / 10,
			PM10SmokeUgM3:  w(22) / 10,
		},
		TemperatureC: float64(int16(binary.BigEndian.Uint16(b[24:]))) / 100,
		HumidityRH:   w(26) / 100,
		TVOC:         int(binary.BigEndian.Uint16(b[28:])) * 10,
		ECO2Ppm:      int(binary.BigEndian.Uint16(b[30:])),
		IAQ:          w(32) / 100,
	}, nil
}

// RRH62000Minimal reads the main air-quality values of an RRH62000.
//
// The module defaults (10 moving averages, fan 86 %, cleaning every 7 days for 10 s) are kept.
// The start-up time (1 s for I²C, 3.3 s for valid data) is counted from construction, Wake and
// SoftReset. Every access waits until AccessInterval has passed since the previous one.
type RRH62000Minimal struct {
	conn connection.RegisterConnection
	mu   sync.Mutex
	// AccessInterval is the minimum spacing between two accesses (default 1 s; the module needs
	// at least 1 s — lower it only for mocks). The start-up waits scale with it.
	AccessInterval time.Duration
	// DataTimeout bounds the DATA polling of Read and ReadAll (default 4 s).
	DataTimeout time.Duration
	lastAccess  time.Time
	startup     time.Time
}

// NewRRH62000Minimal creates the minimal driver for a register connection (1-byte register
// addresses) bound to 0x69; it does no bus access and starts the start-up timer.
func NewRRH62000Minimal(conn connection.RegisterConnection) (*RRH62000Minimal, error) {
	if conn == nil {
		return nil, ErrRRH62000Argument
	}
	now := time.Now()
	return &RRH62000Minimal{conn: conn, AccessInterval: time.Second, DataTimeout: rrh62000DataTimeout, startup: now}, nil
}

func (d *RRH62000Minimal) waitAccess() {
	next := d.lastAccess.Add(d.AccessInterval)
	if s := d.startup.Add(d.AccessInterval); s.After(next) {
		next = s
	}
	if wait := time.Until(next); wait > 0 {
		time.Sleep(wait)
	}
}

func (d *RRH62000Minimal) read(cmd byte, n int) ([]byte, error) {
	d.waitAccess()
	b, err := d.conn.ReadReg(uint32(cmd), n)
	d.lastAccess = time.Now()
	return b, err
}

func (d *RRH62000Minimal) write(cmd byte, value byte) error {
	d.waitAccess()
	err := d.conn.WriteReg(uint32(cmd), []byte{value})
	d.lastAccess = time.Now()
	return err
}

func (d *RRH62000Minimal) readByte(cmd byte) (byte, error) {
	b, err := d.read(cmd, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *RRH62000Minimal) readBlock(wait bool) (RRH62000Data, error) {
	valid := d.startup.Add(d.AccessInterval * 33 / 10)
	if w := time.Until(valid); w > 0 {
		time.Sleep(w)
	}
	if wait {
		start := time.Now()
		for {
			v, err := d.readByte(rrh62000CmdData)
			if err != nil {
				return RRH62000Data{}, err
			}
			if v == 0x01 {
				break
			}
			if time.Since(start) >= d.DataTimeout {
				return RRH62000Data{}, ErrRRH62000Timeout
			}
		}
	}
	b, err := d.read(rrh62000CmdRead, RRH62000BlockLength)
	if err != nil {
		return RRH62000Data{}, err
	}
	return RRH62000Decode(b)
}

// Init waits for the start-up time (1 s from construction) and reads the firmware version as a
// presence check.
func (d *RRH62000Minimal) Init() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	_, err := d.read(rrh62000CmdFwver, 2)
	return err
}

// Read waits for a new sample (DATA polled once per second, up to 4 s) and returns the main
// values: PM (KCl reference), temperature, humidity, TVOC, eCO₂ and IAQ.
func (d *RRH62000Minimal) Read() (RRH62000Measurement, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	m, err := d.readBlock(true)
	if err != nil {
		return RRH62000Measurement{}, err
	}
	return RRH62000Measurement{
		PM1UgM3:      m.Particles.PM1UgM3,
		PM2_5UgM3:    m.Particles.PM2_5UgM3,
		PM10UgM3:     m.Particles.PM10UgM3,
		TemperatureC: m.TemperatureC,
		HumidityRH:   m.HumidityRH,
		TVOC:         m.TVOC,
		ECO2Ppm:      m.ECO2Ppm,
		IAQ:          m.IAQ,
	}, nil
}

// RRH62000Full extends RRH62000Minimal with the complete block, status flags, particle bins,
// sleep, fan cleaning, reset, settings (moving average, fan speed, cleaning interval and time),
// MOX resistance and identification.
type RRH62000Full struct {
	*RRH62000Minimal
}

// NewRRH62000Full creates the full driver; it does no bus access.
func NewRRH62000Full(conn connection.RegisterConnection) (*RRH62000Full, error) {
	m, err := NewRRH62000Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &RRH62000Full{m}, nil
}

// ReadAll waits for a new sample and returns the complete decoded block.
func (d *RRH62000Full) ReadAll() (RRH62000Data, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.readBlock(true)
}

// ReadStatus reads the current block (without waiting for new data) and returns its status word.
func (d *RRH62000Full) ReadStatus() (RRH62000Status, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	m, err := d.readBlock(false)
	return m.Status, err
}

// ReadParticles reads the current block (without waiting for new data) and returns the six PM
// values (KCl and smoke, µg/m³) and the five number concentrations (cm⁻³).
func (d *RRH62000Full) ReadParticles() (RRH62000Particles, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	m, err := d.readBlock(false)
	return m.Particles, err
}

// DataReady reports whether new data has arrived (DATA, 0x40).
func (d *RRH62000Full) DataReady() (bool, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	v, err := d.readByte(rrh62000CmdData)
	return v == 0x01, err
}

// Sleep puts the module to sleep (0x50 ← 0x00).
func (d *RRH62000Full) Sleep() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.write(rrh62000CmdSleep, 0x00)
}

// Wake wakes the module up (0x50 ← 0x80) and restarts the start-up timer.
func (d *RRH62000Full) Wake() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	if err := d.write(rrh62000CmdSleep, 0x80); err != nil {
		return err
	}
	d.startup = time.Now()
	return nil
}

// StartCleaning starts the fan dust-cleaning (0x51 ← 0x01).
func (d *RRH62000Full) StartCleaning() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.write(rrh62000CmdClean, 0x01)
}

// CleaningDone reports whether the cleaning is completed (CSTATUS, 0x74).
func (d *RRH62000Full) CleaningDone() (bool, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	v, err := d.readByte(rrh62000CmdCstatus)
	return v == 0x01, err
}

// SoftReset resets the module (0x52 ← 0x81, same as power-on) and restarts the start-up timer;
// a missing acknowledge during the reset is ignored.
func (d *RRH62000Full) SoftReset() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	_ = d.write(rrh62000CmdReset, 0x81)
	d.startup = time.Now()
	return nil
}

// SetMovingAverage sets the number of moving averages (0x53, 1–60, default 10).
func (d *RRH62000Full) SetMovingAverage(count int) error {
	if count < 1 || count > 60 {
		return ErrRRH62000Argument
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.write(rrh62000CmdMave, byte(count))
}

// GetMovingAverage reads the number of moving averages (1–60).
func (d *RRH62000Full) GetMovingAverage() (int, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	v, err := d.readByte(rrh62000CmdMave)
	return int(v), err
}

// SetFanSpeed sets the fan speed (0x63, 60–100 %, default 86).
func (d *RRH62000Full) SetFanSpeed(percent int) error {
	if percent < 60 || percent > 100 {
		return ErrRRH62000Argument
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.write(rrh62000CmdSpeedFan, byte(percent))
}

// GetFanSpeed reads the fan speed in percent (60–100).
func (d *RRH62000Full) GetFanSpeed() (int, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	v, err := d.readByte(rrh62000CmdSpeedFan)
	return int(v), err
}

// SetCleaningInterval sets the automatic cleaning interval (0x5A/0x5B, 16-bit count of 30 s
// units): 0–1 814 400 s, a multiple of 30 (default 604 800 = 7 days).
func (d *RRH62000Full) SetCleaningInterval(seconds int) error {
	if seconds < 0 || seconds > 1814400 || seconds%30 != 0 {
		return ErrRRH62000Argument
	}
	units := seconds / 30
	d.mu.Lock()
	defer d.mu.Unlock()
	if err := d.write(rrh62000CmdTintcH, byte(units>>8)); err != nil {
		return err
	}
	return d.write(rrh62000CmdTintcL, byte(units))
}

// GetCleaningInterval reads the automatic cleaning interval in seconds.
func (d *RRH62000Full) GetCleaningInterval() (int, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	hi, err := d.readByte(rrh62000CmdTintcH)
	if err != nil {
		return 0, err
	}
	lo, err := d.readByte(rrh62000CmdTintcL)
	if err != nil {
		return 0, err
	}
	return (int(hi)<<8 | int(lo)) * 30, nil
}

// SetCleaningTime sets the fan auto-cleaning time (0x5C, 0–60 s, default 10).
func (d *RRH62000Full) SetCleaningTime(seconds int) error {
	if seconds < 0 || seconds > 60 {
		return ErrRRH62000Argument
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.write(rrh62000CmdTclean, byte(seconds))
}

// GetCleaningTime reads the fan auto-cleaning time in seconds (0–60).
func (d *RRH62000Full) GetCleaningTime() (int, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	v, err := d.readByte(rrh62000CmdTclean)
	return int(v), err
}

// ReadMoxResistance reads the MOX sensor resistance in Ω (0x71, 4 bytes big-endian).
func (d *RRH62000Full) ReadMoxResistance() (uint32, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	b, err := d.read(rrh62000CmdRmox, 4)
	if err != nil {
		return 0, err
	}
	return binary.BigEndian.Uint32(b), nil
}

// UniqueID reads the 6-byte unique ID (0x72).
func (d *RRH62000Full) UniqueID() ([]byte, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.read(rrh62000CmdUID, 6)
}

// AlgorithmVersion reads the TVOC algorithm version (0x73) as major, minor, patch.
func (d *RRH62000Full) AlgorithmVersion() (uint8, uint8, uint8, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	b, err := d.read(rrh62000CmdArgver, 3)
	if err != nil {
		return 0, 0, 0, err
	}
	return b[0], b[1], b[2], nil
}

// FirmwareVersion reads the firmware version (0x75) as major, minor.
func (d *RRH62000Full) FirmwareVersion() (uint8, uint8, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	b, err := d.read(rrh62000CmdFwver, 2)
	if err != nil {
		return 0, 0, err
	}
	return b[0], b[1], nil
}
