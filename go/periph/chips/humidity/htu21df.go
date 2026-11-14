package humidity

import (
	"errors"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// HTU21D-F command codes (single byte; the chip has no register pointer).
const (
	htu21dfCmdTHold     = 0xE3
	htu21dfCmdRHHold    = 0xE5
	htu21dfCmdTPoll     = 0xF3
	htu21dfCmdRHPoll    = 0xF5
	htu21dfCmdWriteUser = 0xE6
	htu21dfCmdReadUser  = 0xE7
	htu21dfCmdReset     = 0xFE
	htu21dfPollAttempts = 100
	htu21dfUserHeater   = 0x04
	htu21dfUserVDDLow   = 0x40
	htu21dfUserResMask  = 0x81
)

// ErrHTU21DFCRC is returned when the CRC-8 of a measurement result does not match twice in a row.
var ErrHTU21DFCRC = errors.New("htu21df: CRC mismatch")

// ErrHTU21DFTimeout is returned when the result is not ready within 100 ms.
var ErrHTU21DFTimeout = errors.New("htu21df: measurement did not complete")

// ErrHTU21DFResolution is returned by SetResolution for a value outside 0-3.
var ErrHTU21DFResolution = errors.New("htu21df: resolution must be 0-3")

// HTU21DFCRC8 computes the HTU21D-F CRC-8 (polynomial 0x131, init 0x00, no final XOR).
func HTU21DFCRC8(data []byte) byte {
	crc := byte(0x00)
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

func htu21dfHumidity(code uint16) float32 {
	return -6.0 + 125.0*float32(code)/65536.0
}

func htu21dfTemperature(code uint16) float32 {
	return -46.85 + 175.72*float32(code)/65536.0
}

// HTU21DFMinimal is the HTU21D-F humidity and temperature sensor driver
// (Measurement Specialties) — minimal interface.
//
// Each Read triggers one temperature (0xF3) and one humidity (0xF5)
// measurement without hold master, polls the NACKed read header until the chip
// ACKs (timeout 100 ms) and verifies the CRC of both results (one retry on a
// mismatch). The constructor does no bus access; Init performs the soft reset.
// Power-on defaults apply: 12-bit RH / 14-bit temperature, heater off.
type HTU21DFMinimal struct {
	conn connection.Connection
	hold bool
}

// NewHTU21DFMinimal creates the driver. conn must be a configured I²C
// connection bound to 0x40.
func NewHTU21DFMinimal(conn connection.Connection) *HTU21DFMinimal {
	return &HTU21DFMinimal{conn: conn}
}

func (d *HTU21DFMinimal) command(code byte) error {
	return d.conn.Write([]byte{code})
}

// Init waits 15 ms for power-up, sends the soft reset (0xFE) and waits 15 ms.
func (d *HTU21DFMinimal) Init() error {
	time.Sleep(15 * time.Millisecond)
	if err := d.command(htu21dfCmdReset); err != nil {
		return err
	}
	time.Sleep(15 * time.Millisecond)
	return nil
}

func (d *HTU21DFMinimal) measureOnce(holdCmd, pollCmd byte) ([]byte, error) {
	if d.hold {
		if err := d.command(holdCmd); err != nil {
			return nil, err
		}
		return d.conn.Read(3)
	}
	if err := d.command(pollCmd); err != nil {
		return nil, err
	}
	for i := 0; i < htu21dfPollAttempts; i++ {
		data, err := d.conn.Read(3)
		if err == nil {
			return data, nil
		}
		time.Sleep(time.Millisecond) // read header NACKed: not done yet
	}
	return nil, ErrHTU21DFTimeout
}

// measure runs one measurement and returns the CRC-verified raw code with the status bits cleared.
func (d *HTU21DFMinimal) measure(holdCmd, pollCmd byte) (uint16, error) {
	for attempt := 0; attempt < 2; attempt++ {
		data, err := d.measureOnce(holdCmd, pollCmd)
		if err != nil {
			return 0, err
		}
		if HTU21DFCRC8(data[0:2]) == data[2] {
			return (uint16(data[0])<<8 | uint16(data[1])) & 0xFFFC, nil
		}
	}
	return 0, ErrHTU21DFCRC
}

// Read measures temperature (°C) and relative humidity (%RH, not clamped).
func (d *HTU21DFMinimal) Read() (temperature, humidity float32, err error) {
	t, err := d.measure(htu21dfCmdTHold, htu21dfCmdTPoll)
	if err != nil {
		return 0, 0, err
	}
	rh, err := d.measure(htu21dfCmdRHHold, htu21dfCmdRHPoll)
	if err != nil {
		return 0, 0, err
	}
	return htu21dfTemperature(t), htu21dfHumidity(rh), nil
}

// HTU21DFFull is the HTU21D-F driver with the complete feature set: single
// values, raw codes, resolution, heater, end-of-battery flag, user register,
// soft reset, hold-master mode, temperature-compensated humidity and dew point.
type HTU21DFFull struct {
	HTU21DFMinimal
}

// NewHTU21DFFull creates the full driver; same argument as NewHTU21DFMinimal.
func NewHTU21DFFull(conn connection.Connection) *HTU21DFFull {
	return &HTU21DFFull{HTU21DFMinimal{conn: conn}}
}

// ReadTemperature runs one temperature measurement and returns °C.
func (d *HTU21DFFull) ReadTemperature() (float32, error) {
	code, err := d.measure(htu21dfCmdTHold, htu21dfCmdTPoll)
	if err != nil {
		return 0, err
	}
	return htu21dfTemperature(code), nil
}

// ReadHumidity runs one humidity measurement and returns %RH (not clamped).
func (d *HTU21DFFull) ReadHumidity() (float32, error) {
	code, err := d.measure(htu21dfCmdRHHold, htu21dfCmdRHPoll)
	if err != nil {
		return 0, err
	}
	return htu21dfHumidity(code), nil
}

// ReadHumidityCompensated measures and corrects the humidity for temperature
// (-0.15 %RH/°C relative to 25 °C).
func (d *HTU21DFFull) ReadHumidityCompensated() (float32, error) {
	t, rh, err := d.Read()
	if err != nil {
		return 0, err
	}
	return rh + (25.0-t)*(-0.15), nil
}

// ReadRaw measures and returns the raw 16-bit (temperature, humidity) codes with the status bits cleared.
func (d *HTU21DFFull) ReadRaw() (temperature, humidity uint16, err error) {
	if temperature, err = d.measure(htu21dfCmdTHold, htu21dfCmdTPoll); err != nil {
		return 0, 0, err
	}
	if humidity, err = d.measure(htu21dfCmdRHHold, htu21dfCmdRHPoll); err != nil {
		return 0, 0, err
	}
	return temperature, humidity, nil
}

// ReadUserRegister reads the user register (command 0xE7); 0x02 after reset.
func (d *HTU21DFFull) ReadUserRegister() (byte, error) {
	data, err := d.conn.WriteRead([]byte{htu21dfCmdReadUser}, 1)
	if err != nil {
		return 0, err
	}
	return data[0], nil
}

func (d *HTU21DFFull) writeUser(value byte) error {
	return d.conn.Write([]byte{htu21dfCmdWriteUser, value})
}

// SetResolution selects the resolution (read-modify-write of the RES bits):
// 0 = 12-bit RH / 14-bit T, 1 = 8/12, 2 = 10/13, 3 = 11/11.
func (d *HTU21DFFull) SetResolution(res int) error {
	if res < 0 || res > 3 {
		return ErrHTU21DFResolution
	}
	reg, err := d.ReadUserRegister()
	if err != nil {
		return err
	}
	bits := byte(((res >> 1) << 7) | (res & 1))
	return d.writeUser(reg&^htu21dfUserResMask | bits)
}

// GetResolution returns the resolution setting (0 = 12/14 bit, 1 = 8/12, 2 = 10/13, 3 = 11/11).
func (d *HTU21DFFull) GetResolution() (int, error) {
	reg, err := d.ReadUserRegister()
	if err != nil {
		return 0, err
	}
	return int(((reg>>7)&1)<<1 | reg&1), nil
}

// SetHeater switches the on-chip heater on or off (read-modify-write).
func (d *HTU21DFFull) SetHeater(on bool) error {
	reg, err := d.ReadUserRegister()
	if err != nil {
		return err
	}
	if on {
		reg |= htu21dfUserHeater
	} else {
		reg &^= htu21dfUserHeater
	}
	return d.writeUser(reg)
}

// Heater reports whether the on-chip heater is enabled.
func (d *HTU21DFFull) Heater() (bool, error) {
	reg, err := d.ReadUserRegister()
	return reg&htu21dfUserHeater != 0, err
}

// BatteryLow reports the end-of-battery flag (VDD below about 2.25 V).
func (d *HTU21DFFull) BatteryLow() (bool, error) {
	reg, err := d.ReadUserRegister()
	return reg&htu21dfUserVDDLow != 0, err
}

// SoftReset sends 0xFE and waits 15 ms; the heater bit survives.
func (d *HTU21DFFull) SoftReset() error {
	if err := d.command(htu21dfCmdReset); err != nil {
		return err
	}
	time.Sleep(15 * time.Millisecond)
	return nil
}

// SetHoldMaster uses the hold-master commands (0xE3 / 0xE5) instead of polling;
// the bus layer must support clock stretching.
func (d *HTU21DFFull) SetHoldMaster(on bool) { d.hold = on }

// DewPoint runs one measurement and returns the dew point in °C.
func (d *HTU21DFFull) DewPoint() (float32, error) {
	t, rh, err := d.Read()
	if err != nil {
		return 0, err
	}
	if rh <= 0 {
		rh = 0.01
	}
	pp := math.Pow(10, 8.1332-1762.39/(float64(t)+235.66))
	return float32(-1762.39/(math.Log10(float64(rh)*pp/100.0)-8.1332) - 235.66), nil
}
