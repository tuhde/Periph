package humidity

import (
	"errors"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// HS30xx NVM programming-mode commands and registers.
const (
	hs30xxRegRHRead   = 0x06
	hs30xxRegRHWrite  = 0x46
	hs30xxRegTRead    = 0x11
	hs30xxRegTWrite   = 0x51
	hs30xxRegIDHigh   = 0x1E
	hs30xxRegIDLow    = 0x1F
	hs30xxNVMOK       = 0x81
	hs30xxResMask     = 0x0C00
	hs30xxFullScale   = 16383.0
	hs30xxDefaultWait = 40 * time.Millisecond
)

var (
	hs30xxEnterProg = []byte{0xA0, 0x00, 0x00}
	hs30xxExitProg  = []byte{0x80, 0x00, 0x00}
	hs30xxBits      = [4]int{8, 10, 12, 14}
)

// ErrHS30xxStale is returned when the data are still flagged stale after one retry.
var ErrHS30xxStale = errors.New("hs30xx: stale data")

// ErrHS30xxNVM is returned when an NVM read does not answer with status 0x81.
var ErrHS30xxNVM = errors.New("hs30xx: NVM read failed")

// ErrHS30xxPowerCycle is returned by the NVM methods when no power-cycle function is given.
var ErrHS30xxPowerCycle = errors.New("hs30xx: NVM access needs a power-cycle function")

// ErrHS30xxArgument is returned for an unsupported resolution or a non-positive wait.
var ErrHS30xxArgument = errors.New("hs30xx: invalid argument")

func hs30xxRawHumidity(d []byte) uint16    { return uint16(d[0]&0x3F)<<8 | uint16(d[1]) }
func hs30xxRawTemperature(d []byte) uint16 { return uint16(d[2])<<6 | uint16(d[3]>>2) }
func hs30xxHumidity(raw uint16) float32    { return float32(raw) / hs30xxFullScale * 100.0 }
func hs30xxTemperature(raw uint16) float32 { return float32(raw)/hs30xxFullScale*165.0 - 40.0 }

// HS30xxMinimal is the Renesas HS30xx (HS3001, HS3002, HS3003) humidity and
// temperature sensor driver — minimal interface.
//
// The three parts share one protocol and differ only in accuracy class and
// housing, so HS3001Minimal, HS3002Minimal and HS3003Minimal are aliases.
// Each Read sends a measurement request (an address-only write), waits 40 ms,
// fetches four bytes and converts them; stale data are fetched once more after
// 10 ms. The constructor does no bus access: bytes written within 10 ms of
// power-up could be taken as NVM programming commands.
type HS30xxMinimal struct {
	conn connection.Connection
	wait time.Duration
}

// NewHS30xxMinimal creates the driver. conn must be a configured I²C
// connection bound to 0x44.
func NewHS30xxMinimal(conn connection.Connection) *HS30xxMinimal {
	return &HS30xxMinimal{conn: conn, wait: hs30xxDefaultWait}
}

// fetchRaw sends the measurement request, waits and reads n bytes without a stale check.
func (d *HS30xxMinimal) fetchRaw(n int) ([]byte, error) {
	if err := d.conn.Write([]byte{}); err != nil {
		return nil, err
	}
	time.Sleep(d.wait)
	return d.conn.Read(n)
}

// fetch is fetchRaw plus one retry after 10 ms if the status bits say stale.
func (d *HS30xxMinimal) fetch(n int) ([]byte, error) {
	data, err := d.fetchRaw(n)
	if err != nil {
		return nil, err
	}
	if data[0]>>6 != 0 {
		time.Sleep(10 * time.Millisecond)
		if data, err = d.conn.Read(n); err != nil {
			return nil, err
		}
		if data[0]>>6 != 0 {
			return nil, ErrHS30xxStale
		}
	}
	return data, nil
}

// Read measures temperature (°C, -40 to 125) and relative humidity (%RH, 0 to 100).
func (d *HS30xxMinimal) Read() (temperature, humidity float32, err error) {
	data, err := d.fetch(4)
	if err != nil {
		return 0, 0, err
	}
	return hs30xxTemperature(hs30xxRawTemperature(data)), hs30xxHumidity(hs30xxRawHumidity(data)), nil
}

// HS30xxFull is the HS30xx driver with the complete feature set: single
// values, raw values, wait override, NVM resolution and ID, and dew point.
//
// The NVM methods need the sensor's programming mode, which can only be entered
// within 10 ms after power-up. They therefore take a powerCycle function that
// cuts and restores the sensor supply; with nil they return ErrHS30xxPowerCycle.
type HS30xxFull struct {
	HS30xxMinimal
}

// NewHS30xxFull creates the full driver; same argument as NewHS30xxMinimal.
func NewHS30xxFull(conn connection.Connection) *HS30xxFull {
	return &HS30xxFull{*NewHS30xxMinimal(conn)}
}

// HS3001Minimal, HS3002Minimal and HS3003Minimal are the same driver as HS30xxMinimal.
type (
	HS3001Minimal = HS30xxMinimal
	HS3002Minimal = HS30xxMinimal
	HS3003Minimal = HS30xxMinimal
	// HS3001Full, HS3002Full and HS3003Full are the same driver as HS30xxFull.
	HS3001Full = HS30xxFull
	HS3002Full = HS30xxFull
	HS3003Full = HS30xxFull
)

// NewHS3001Minimal, NewHS3002Minimal and NewHS3003Minimal alias NewHS30xxMinimal.
var (
	NewHS3001Minimal = NewHS30xxMinimal
	NewHS3002Minimal = NewHS30xxMinimal
	NewHS3003Minimal = NewHS30xxMinimal
	NewHS3001Full    = NewHS30xxFull
	NewHS3002Full    = NewHS30xxFull
	NewHS3003Full    = NewHS30xxFull
)

// ReadTemperature measures temperature only (four-byte fetch) in °C.
func (d *HS30xxFull) ReadTemperature() (float32, error) {
	data, err := d.fetch(4)
	if err != nil {
		return 0, err
	}
	return hs30xxTemperature(hs30xxRawTemperature(data)), nil
}

// ReadHumidity measures humidity only (two-byte fetch) in %RH.
func (d *HS30xxFull) ReadHumidity() (float32, error) {
	data, err := d.fetch(2)
	if err != nil {
		return 0, err
	}
	return hs30xxHumidity(hs30xxRawHumidity(data)), nil
}

// ReadRaw measures and returns the raw 14-bit values and the status field
// (0 = valid, 1 = stale) without a stale check.
func (d *HS30xxFull) ReadRaw() (humidity, temperature uint16, status byte, err error) {
	data, err := d.fetchRaw(4)
	if err != nil {
		return 0, 0, 0, err
	}
	return hs30xxRawHumidity(data), hs30xxRawTemperature(data), data[0] >> 6, nil
}

// SetMeasurementWait overrides the wait between measurement request and data
// fetch (default 40 ms). Lower resolutions convert faster: 0.1 ms + RH_time/2 +
// T_time/2 (datasheet).
func (d *HS30xxFull) SetMeasurementWait(wait time.Duration) error {
	if wait <= 0 {
		return ErrHS30xxArgument
	}
	d.wait = wait
	return nil
}

// EnterProgrammingMode sends 0xA0 0x00 0x00 and waits 120 µs; only valid within 10 ms after power-up.
func (d *HS30xxFull) EnterProgrammingMode() error {
	if err := d.conn.Write(hs30xxEnterProg); err != nil {
		return err
	}
	time.Sleep(time.Millisecond)
	return nil
}

// ExitProgrammingMode sends 0x80 0x00 0x00 and returns to measurement operation.
func (d *HS30xxFull) ExitProgrammingMode() error {
	return d.conn.Write(hs30xxExitProg)
}

// ReadNVM reads one 16-bit NVM register (programming mode only); it fails with
// ErrHS30xxNVM unless the response status byte is 0x81.
func (d *HS30xxFull) ReadNVM(reg byte) (uint16, error) {
	if err := d.conn.Write([]byte{reg, 0x00, 0x00}); err != nil {
		return 0, err
	}
	time.Sleep(time.Millisecond)
	data, err := d.conn.Read(3)
	if err != nil {
		return 0, err
	}
	if data[0] != hs30xxNVMOK {
		return 0, ErrHS30xxNVM
	}
	return uint16(data[1])<<8 | uint16(data[2]), nil
}

func (d *HS30xxFull) writeNVM(reg byte, value uint16) error {
	if err := d.conn.Write([]byte{reg, byte(value >> 8), byte(value)}); err != nil {
		return err
	}
	time.Sleep(14 * time.Millisecond)
	return nil
}

// inProgramming power-cycles the sensor, enters programming mode, runs body and always leaves it again.
func (d *HS30xxFull) inProgramming(powerCycle func(), body func() error) error {
	if powerCycle == nil {
		return ErrHS30xxPowerCycle
	}
	powerCycle()
	if err := d.EnterProgrammingMode(); err != nil {
		return err
	}
	err := body()
	if exitErr := d.ExitProgrammingMode(); err == nil {
		err = exitErr
	}
	return err
}

// GetResolution reads the NVM resolution settings (humidity and temperature
// bits, each 8, 10, 12 or 14) after power-cycling the sensor.
func (d *HS30xxFull) GetResolution(powerCycle func()) (humidityBits, temperatureBits int, err error) {
	err = d.inProgramming(powerCycle, func() error {
		rh, e := d.ReadNVM(hs30xxRegRHRead)
		if e != nil {
			return e
		}
		t, e := d.ReadNVM(hs30xxRegTRead)
		if e != nil {
			return e
		}
		humidityBits, temperatureBits = hs30xxBits[(rh>>10)&3], hs30xxBits[(t>>10)&3]
		return nil
	})
	return humidityBits, temperatureBits, err
}

func hs30xxBitsCode(bits int) (uint16, bool) {
	for i, b := range hs30xxBits {
		if b == bits {
			return uint16(i), true
		}
	}
	return 0, false
}

// SetResolution writes the NVM resolution settings after power-cycling the
// sensor; 0 leaves a channel unchanged, other values must be 8, 10, 12 or 14.
// Each register is read, bits 11:10 replaced and the whole word written back,
// then the driver waits 14 ms for the NVM commit.
func (d *HS30xxFull) SetResolution(humidityBits, temperatureBits int, powerCycle func()) error {
	type channel struct {
		bits       int
		code       uint16
		rreg, wreg byte
	}
	channels := []channel{{humidityBits, 0, hs30xxRegRHRead, hs30xxRegRHWrite}, {temperatureBits, 0, hs30xxRegTRead, hs30xxRegTWrite}}
	for i := range channels {
		if channels[i].bits == 0 {
			continue
		}
		code, ok := hs30xxBitsCode(channels[i].bits)
		if !ok {
			return ErrHS30xxArgument
		}
		channels[i].code = code
	}
	return d.inProgramming(powerCycle, func() error {
		for _, c := range channels {
			if c.bits == 0 {
				continue
			}
			value, err := d.ReadNVM(c.rreg)
			if err != nil {
				return err
			}
			if err := d.writeNVM(c.wreg, value&^hs30xxResMask|c.code<<10); err != nil {
				return err
			}
		}
		return nil
	})
}

// ReadID reads the 32-bit sensor ID from NVM after power-cycling the sensor.
func (d *HS30xxFull) ReadID(powerCycle func()) (id uint32, err error) {
	err = d.inProgramming(powerCycle, func() error {
		high, e := d.ReadNVM(hs30xxRegIDHigh)
		if e != nil {
			return e
		}
		low, e := d.ReadNVM(hs30xxRegIDLow)
		if e != nil {
			return e
		}
		id = uint32(high)<<16 | uint32(low)
		return nil
	})
	return id, err
}

// DewPoint measures once and returns the dew point in °C (Magnus formula);
// -273.15 if humidity is 0 %RH.
func (d *HS30xxFull) DewPoint() (float32, error) {
	t, rh, err := d.Read()
	if err != nil {
		return 0, err
	}
	if rh <= 0 {
		return -273.15, nil
	}
	gamma := math.Log(float64(rh)/100.0) + 17.62*float64(t)/(243.12+float64(t))
	return float32(243.12 * gamma / (17.62 - gamma)), nil
}
