package humidity

import (
	"errors"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// SHT31 command codes (16-bit, sent MSB first; the chip has no register map).
const (
	sht31CmdSoftReset   = 0x30A2
	sht31CmdHeaterOn    = 0x306D
	sht31CmdHeaterOff   = 0x3066
	sht31CmdReadStatus  = 0xF32D
	sht31CmdClearStatus = 0x3041
	sht31CmdFetch       = 0xE000
	sht31CmdBreak       = 0x3093
	sht31CmdART         = 0x2B32
)

// SHT31 repeatability levels for SetRepeatability / StartPeriodic.
const (
	SHT31RepeatabilityLow    = "low"
	SHT31RepeatabilityMedium = "medium"
	SHT31RepeatabilityHigh   = "high"
)

// sht31Single holds the single-shot codes (no stretching, stretching) and the wait.
type sht31SingleCmd struct {
	noStretch, stretch uint16
	wait               time.Duration
}

var sht31Single = map[string]sht31SingleCmd{
	SHT31RepeatabilityHigh:   {0x2400, 0x2C06, 15 * time.Millisecond},
	SHT31RepeatabilityMedium: {0x240B, 0x2C0D, 6 * time.Millisecond},
	SHT31RepeatabilityLow:    {0x2416, 0x2C10, 4 * time.Millisecond},
}

// sht31Periodic maps rate (measurements per second) × 2 → repeatability → command.
var sht31Periodic = map[int]map[string]uint16{
	1:  {SHT31RepeatabilityHigh: 0x2032, SHT31RepeatabilityMedium: 0x2024, SHT31RepeatabilityLow: 0x202F},
	2:  {SHT31RepeatabilityHigh: 0x2130, SHT31RepeatabilityMedium: 0x2126, SHT31RepeatabilityLow: 0x212D},
	4:  {SHT31RepeatabilityHigh: 0x2236, SHT31RepeatabilityMedium: 0x2220, SHT31RepeatabilityLow: 0x222B},
	8:  {SHT31RepeatabilityHigh: 0x2334, SHT31RepeatabilityMedium: 0x2322, SHT31RepeatabilityLow: 0x2329},
	20: {SHT31RepeatabilityHigh: 0x2737, SHT31RepeatabilityMedium: 0x2721, SHT31RepeatabilityLow: 0x272A},
}

// ErrSHT31CRC is returned when a CRC-8 check fails (for single-shot reads only after one retry).
var ErrSHT31CRC = errors.New("sht31: CRC mismatch")

// ErrSHT31Timeout is returned when the result is not ready within the polling window.
var ErrSHT31Timeout = errors.New("sht31: measurement did not complete")

// SHT31CRC8 computes the SHT3x CRC-8 (polynomial 0x31, init 0xFF, no reflection, no final XOR).
func SHT31CRC8(data []byte) byte {
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

func sht31Decode(b []byte) (rawT, rawRH uint16, err error) {
	if SHT31CRC8(b[0:2]) != b[2] || SHT31CRC8(b[3:5]) != b[5] {
		return 0, 0, ErrSHT31CRC
	}
	return uint16(b[0])<<8 | uint16(b[1]), uint16(b[3])<<8 | uint16(b[4]), nil
}

func sht31Convert(rawT, rawRH uint16) (t, rh float32) {
	return -45.0 + 175.0*float32(rawT)/65535.0, 100.0 * float32(rawRH) / 65535.0
}

// SHT31Minimal is the SHT31 temperature and humidity sensor driver (Sensirion) —
// minimal interface.
//
// Each Read runs one single-shot measurement at high repeatability with clock
// stretching disabled and CRC checking on; the heater stays off. The
// constructor soft-resets the chip (0x30A2) with 1 ms waits. After the fixed
// conversion wait (15 ms) the driver keeps polling for up to 20 ms while the
// read header is NACKed. Both CRC-8 bytes are verified with one retry of the
// whole measurement.
type SHT31Minimal struct {
	conn          connection.Connection
	repeatability string
	stretch       bool
}

// NewSHT31Minimal soft-resets the sensor. conn must be a configured I²C
// connection bound to 0x44 (ADDR low) or 0x45 (ADDR high).
func NewSHT31Minimal(conn connection.Connection) (*SHT31Minimal, error) {
	d := &SHT31Minimal{conn: conn, repeatability: SHT31RepeatabilityHigh}
	time.Sleep(time.Millisecond)
	if err := d.command(sht31CmdSoftReset); err != nil {
		return nil, err
	}
	time.Sleep(time.Millisecond)
	return d, nil
}

func (d *SHT31Minimal) command(code uint16) error {
	return d.conn.Write([]byte{byte(code >> 8), byte(code)})
}

func (d *SHT31Minimal) singleShot() (rawT, rawRH uint16, err error) {
	cmd := sht31Single[d.repeatability]
	for attempt := 0; attempt < 2; attempt++ {
		code := cmd.noStretch
		if d.stretch {
			code = cmd.stretch
		}
		if err = d.command(code); err != nil {
			return 0, 0, err
		}
		if !d.stretch {
			time.Sleep(cmd.wait)
		}
		var data []byte
		for i := 0; i < 21; i++ {
			data, err = d.conn.Read(6)
			if err == nil {
				break
			}
			time.Sleep(time.Millisecond) // read header NACKed: not ready yet
		}
		if err != nil {
			return 0, 0, ErrSHT31Timeout
		}
		rawT, rawRH, err = sht31Decode(data)
		if err == nil {
			return rawT, rawRH, nil
		}
	}
	return 0, 0, err
}

// Read runs one single-shot measurement and returns the temperature in °C and
// the relative humidity in %RH. Returns ErrSHT31CRC if a CRC mismatch persists
// after one retry.
func (d *SHT31Minimal) Read() (temperature, humidity float32, err error) {
	rawT, rawRH, err := d.singleShot()
	if err != nil {
		return 0, 0, err
	}
	t, rh := sht31Convert(rawT, rawRH)
	return t, rh, nil
}

// SHT31Full is the SHT31 full interface: repeatability, clock stretching,
// periodic mode, heater and status. It embeds SHT31Minimal.
type SHT31Full struct {
	*SHT31Minimal
}

// NewSHT31Full creates the Full driver; conn is as for NewSHT31Minimal.
func NewSHT31Full(conn connection.Connection) (*SHT31Full, error) {
	m, err := NewSHT31Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &SHT31Full{SHT31Minimal: m}, nil
}

// ReadTemperature runs one measurement and returns the temperature in °C.
func (d *SHT31Full) ReadTemperature() (float32, error) {
	t, _, err := d.Read()
	return t, err
}

// ReadHumidity runs one measurement and returns the relative humidity in %RH.
func (d *SHT31Full) ReadHumidity() (float32, error) {
	_, rh, err := d.Read()
	return rh, err
}

// ReadRaw runs one measurement and returns the raw 16-bit temperature and humidity words.
func (d *SHT31Full) ReadRaw() (rawTemperature, rawHumidity uint16, err error) {
	return d.singleShot()
}

// SetRepeatability selects the repeatability for later single-shot reads
// (SHT31Repeatability*; measurement time 4 / 6 / 15 ms).
func (d *SHT31Full) SetRepeatability(level string) error {
	if _, ok := sht31Single[level]; !ok {
		return errors.New("sht31: repeatability must be low, medium or high")
	}
	d.repeatability = level
	return nil
}

// SetClockStretching switches single-shot reads to the clock-stretching
// command set (the bus layer must support it); the fixed wait is skipped.
func (d *SHT31Full) SetClockStretching(on bool) { d.stretch = on }

// StartPeriodic starts periodic measurements; read results with Fetch. rate is
// 0.5, 1, 2, 4 or 10 measurements per second (ignored when art is set); art
// selects the accelerated response time mode (4 mps, command 0x2B32).
func (d *SHT31Full) StartPeriodic(rate float64, repeatability string, art bool) error {
	if art {
		return d.command(sht31CmdART)
	}
	row, ok := sht31Periodic[int(math.Round(rate*2))]
	if !ok || math.Abs(rate*2-math.Round(rate*2)) > 1e-9 {
		return errors.New("sht31: rate must be 0.5, 1, 2, 4 or 10")
	}
	code, ok := row[repeatability]
	if !ok {
		return errors.New("sht31: repeatability must be low, medium or high")
	}
	return d.command(code)
}

// Fetch reads the latest periodic result (command 0xE000). ok is false when no
// new data is available (the read header is NACKed); a failed CRC returns
// ErrSHT31CRC.
func (d *SHT31Full) Fetch() (temperature, humidity float32, ok bool, err error) {
	if err = d.command(sht31CmdFetch); err != nil {
		return 0, 0, false, err
	}
	data, rerr := d.conn.Read(6)
	if rerr != nil {
		return 0, 0, false, nil
	}
	rawT, rawRH, err := sht31Decode(data)
	if err != nil {
		return 0, 0, false, err
	}
	t, rh := sht31Convert(rawT, rawRH)
	return t, rh, true, nil
}

// StopPeriodic stops periodic mode (break command 0x3093) and waits 1 ms.
func (d *SHT31Full) StopPeriodic() error {
	if err := d.command(sht31CmdBreak); err != nil {
		return err
	}
	time.Sleep(time.Millisecond)
	return nil
}

// SetHeater switches the on-chip heater (about 5–25 mW) on or off.
func (d *SHT31Full) SetHeater(on bool) error {
	if on {
		return d.command(sht31CmdHeaterOn)
	}
	return d.command(sht31CmdHeaterOff)
}

// ReadStatus reads the status register (command 0xF32D, CRC verified), e.g.
// 0x8010 after reset; bit 13 is the heater status.
func (d *SHT31Full) ReadStatus() (uint16, error) {
	if err := d.command(sht31CmdReadStatus); err != nil {
		return 0, err
	}
	data, err := d.conn.Read(3)
	if err != nil {
		return 0, err
	}
	if SHT31CRC8(data[0:2]) != data[2] {
		return 0, ErrSHT31CRC
	}
	return uint16(data[0])<<8 | uint16(data[1]), nil
}

// ClearStatus clears the status register flags (command 0x3041).
func (d *SHT31Full) ClearStatus() error { return d.command(sht31CmdClearStatus) }

// SoftReset resets the chip (0x30A2), waits 1 ms; periodic mode is left and
// the chip returns to single-shot mode.
func (d *SHT31Full) SoftReset() error {
	if err := d.command(sht31CmdSoftReset); err != nil {
		return err
	}
	time.Sleep(time.Millisecond)
	return nil
}

// DewPoint runs one measurement and computes the dew point in °C (Magnus formula).
func (d *SHT31Full) DewPoint() (float32, error) {
	t, rh, err := d.Read()
	if err != nil {
		return 0, err
	}
	if rh <= 0 {
		rh = 0.01
	}
	gamma := math.Log(float64(rh)/100.0) + (17.62*float64(t))/(243.12+float64(t))
	return float32(243.12 * gamma / (17.62 - gamma)), nil
}
