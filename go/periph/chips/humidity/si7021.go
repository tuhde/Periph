package humidity

import (
	"errors"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// Si7021 command codes (single byte; the chip has no register pointer).
const (
	si7021CmdRHHold    = 0xE5
	si7021CmdRHPoll    = 0xF5
	si7021CmdTHold     = 0xE3
	si7021CmdTPoll     = 0xF3
	si7021CmdTPrevious = 0xE0
	si7021CmdReset     = 0xFE
	si7021CmdWriteUser = 0xE6
	si7021CmdReadUser  = 0xE7
	si7021PollAttempts = 50
	si7021UserReserved = 0x3A
	si7021UserVDDS     = 0x40
	si7021UserHTRE     = 0x04
	si7021UserResMask  = 0x81
)

// si7021Resolutions holds the (RH bits, temperature bits) pairs indexed by RES1:RES0.
var si7021Resolutions = [4][2]int{{12, 14}, {8, 12}, {10, 13}, {11, 11}}

// Typical conversion times in seconds, indexed by RES1:RES0.
var (
	si7021RHTime = [4]float32{0.010, 0.0026, 0.0037, 0.0058}
	si7021TTime  = [4]float32{0.007, 0.0024, 0.004, 0.0015}
)

// ErrSi7021CRC is returned when the CRC-8 of a measurement result does not match.
var ErrSi7021CRC = errors.New("si7021: CRC mismatch")

// ErrSi7021Timeout is returned when the result is not ready within 50 ms.
var ErrSi7021Timeout = errors.New("si7021: measurement did not complete")

// ErrSi7021Resolution is returned by SetResolution for a pair other than
// (12, 14), (8, 12), (10, 13) or (11, 11).
var ErrSi7021Resolution = errors.New("si7021: resolution must be (12, 14), (8, 12), (10, 13) or (11, 11)")

// Si7021CRC8 computes the Si7021 CRC-8 (polynomial 0x31, init 0x00, no reflection, no final XOR).
func Si7021CRC8(data []byte) byte {
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

func si7021Humidity(code uint16) float32 {
	rh := 125.0*float32(code)/65536.0 - 6.0
	if rh < 0 {
		return 0
	}
	if rh > 100 {
		return 100
	}
	return rh
}

func si7021Temperature(code uint16) float32 {
	return 175.72*float32(code)/65536.0 - 46.85
}

// Si7021Minimal is the Si7021 humidity and temperature sensor driver (Silicon
// Labs) — minimal interface.
//
// Each Read runs one relative-humidity measurement without hold master (the
// read header is polled until the chip ACKs, timeout 50 ms), verifies the CRC
// and then fetches the temperature of that same measurement with command 0xE0.
// The constructor does no bus access; the power-on defaults apply (12-bit RH /
// 14-bit temperature, heater off).
type Si7021Minimal struct {
	conn    connection.Connection
	stretch bool
}

// NewSi7021Minimal creates the driver. conn must be a configured I²C connection
// bound to 0x40.
func NewSi7021Minimal(conn connection.Connection) *Si7021Minimal {
	return &Si7021Minimal{conn: conn}
}

func (d *Si7021Minimal) command(code byte) error {
	return d.conn.Write([]byte{code})
}

// measure runs one measurement command and returns the CRC-verified raw code.
func (d *Si7021Minimal) measure(holdCmd, pollCmd byte) (uint16, error) {
	var data []byte
	var err error
	if d.stretch {
		if err = d.command(holdCmd); err != nil {
			return 0, err
		}
		if data, err = d.conn.Read(3); err != nil {
			return 0, err
		}
	} else {
		if err = d.command(pollCmd); err != nil {
			return 0, err
		}
		for i := 0; i < si7021PollAttempts; i++ {
			data, err = d.conn.Read(3)
			if err == nil {
				break
			}
			time.Sleep(time.Millisecond) // read header NACKed: not ready yet
		}
		if err != nil {
			return 0, ErrSi7021Timeout
		}
	}
	if Si7021CRC8(data[0:2]) != data[2] {
		return 0, ErrSi7021CRC
	}
	return uint16(data[0])<<8 | uint16(data[1]), nil
}

func (d *Si7021Minimal) previousCode() (uint16, error) {
	if err := d.command(si7021CmdTPrevious); err != nil {
		return 0, err
	}
	data, err := d.conn.Read(2)
	if err != nil {
		return 0, err
	}
	return uint16(data[0])<<8 | uint16(data[1]), nil
}

// Read runs one RH measurement and returns the temperature of the same
// conversion in °C and the relative humidity in %RH (0–100). Returns
// ErrSi7021CRC if the RH result fails its CRC and ErrSi7021Timeout if the chip
// does not answer within 50 ms.
func (d *Si7021Minimal) Read() (temperature, humidity float32, err error) {
	rh, err := d.measure(si7021CmdRHHold, si7021CmdRHPoll)
	if err != nil {
		return 0, 0, err
	}
	t, err := d.previousCode()
	if err != nil {
		return 0, 0, err
	}
	return si7021Temperature(t), si7021Humidity(rh), nil
}

// Si7021Full is the Si7021 full interface: resolution, heater, hold master, ID
// and dew point. It embeds Si7021Minimal.
type Si7021Full struct {
	*Si7021Minimal
}

// NewSi7021Full creates the Full driver; conn is as for NewSi7021Minimal.
func NewSi7021Full(conn connection.Connection) *Si7021Full {
	return &Si7021Full{Si7021Minimal: NewSi7021Minimal(conn)}
}

// ReadRawHumidity runs one RH measurement and returns the raw 16-bit code (status bits included).
func (d *Si7021Full) ReadRawHumidity() (uint16, error) {
	return d.measure(si7021CmdRHHold, si7021CmdRHPoll)
}

// ReadRawTemperature runs one temperature measurement and returns the raw 16-bit code.
func (d *Si7021Full) ReadRawTemperature() (uint16, error) {
	return d.measure(si7021CmdTHold, si7021CmdTPoll)
}

// ReadHumidity runs one RH measurement and returns the relative humidity in %RH (0–100).
func (d *Si7021Full) ReadHumidity() (float32, error) {
	code, err := d.ReadRawHumidity()
	if err != nil {
		return 0, err
	}
	return si7021Humidity(code), nil
}

// ReadTemperature runs a fresh temperature measurement (0xF3, or 0xE3 with clock stretching) and returns °C.
func (d *Si7021Full) ReadTemperature() (float32, error) {
	code, err := d.ReadRawTemperature()
	if err != nil {
		return 0, err
	}
	return si7021Temperature(code), nil
}

// ReadTemperaturePrevious returns the temperature of the last RH measurement
// in °C (0xE0, no new conversion, no CRC).
func (d *Si7021Full) ReadTemperaturePrevious() (float32, error) {
	code, err := d.previousCode()
	if err != nil {
		return 0, err
	}
	return si7021Temperature(code), nil
}

// ReadUserRegister reads USER_REG_1 (command 0xE7); 0x3A after reset.
func (d *Si7021Full) ReadUserRegister() (byte, error) {
	data, err := d.conn.WriteRead([]byte{si7021CmdReadUser}, 1)
	if err != nil {
		return 0, err
	}
	return data[0], nil
}

// WriteUserRegister writes USER_REG_1 (command 0xE6); the reserved bits and
// VDDS keep their current value.
func (d *Si7021Full) WriteUserRegister(value byte) error {
	cur, err := d.ReadUserRegister()
	if err != nil {
		return err
	}
	keep := byte(si7021UserReserved | si7021UserVDDS)
	return d.conn.Write([]byte{si7021CmdWriteUser, cur&keep | value&^keep})
}

// SetResolution selects the measurement resolution (read-modify-write of
// RES1:RES0). Allowed (rhBits, temperatureBits) pairs: (12, 14), (8, 12),
// (10, 13), (11, 11); ErrSi7021Resolution otherwise.
func (d *Si7021Full) SetResolution(rhBits, temperatureBits int) error {
	for res, p := range si7021Resolutions {
		if p[0] == rhBits && p[1] == temperatureBits {
			bits := byte((res>>1)<<7 | res&1)
			reg, err := d.ReadUserRegister()
			if err != nil {
				return err
			}
			return d.WriteUserRegister(reg&^si7021UserResMask | bits)
		}
	}
	return ErrSi7021Resolution
}

// GetResolution returns the configured (RH bits, temperature bits).
func (d *Si7021Full) GetResolution() (rhBits, temperatureBits int, err error) {
	reg, err := d.ReadUserRegister()
	if err != nil {
		return 0, 0, err
	}
	p := si7021Resolutions[(reg>>7&1)<<1|reg&1]
	return p[0], p[1], nil
}

// SetHeater switches the on-chip heater (HTRE, about 3.1 mA) on or off.
func (d *Si7021Full) SetHeater(on bool) error {
	reg, err := d.ReadUserRegister()
	if err != nil {
		return err
	}
	if on {
		return d.WriteUserRegister(reg | si7021UserHTRE)
	}
	return d.WriteUserRegister(reg &^ si7021UserHTRE)
}

// IsHeaterOn reports whether the on-chip heater is enabled.
func (d *Si7021Full) IsHeaterOn() (bool, error) {
	reg, err := d.ReadUserRegister()
	return reg&si7021UserHTRE != 0, err
}

// VDDLow reports whether the supply voltage is low (VDDS set, VDD below about 1.9 V).
func (d *Si7021Full) VDDLow() (bool, error) {
	reg, err := d.ReadUserRegister()
	return reg&si7021UserVDDS != 0, err
}

// SetClockStretching uses the hold-master commands (0xE5 / 0xE3) instead of
// polling; the bus layer must support clock stretching.
func (d *Si7021Full) SetClockStretching(on bool) { d.stretch = on }

// SoftReset sends 0xFE and waits 15 ms; the user register returns to 0x3A.
func (d *Si7021Full) SoftReset() error {
	if err := d.command(si7021CmdReset); err != nil {
		return err
	}
	time.Sleep(15 * time.Millisecond)
	return nil
}

// SerialNumber reads the 64-bit electronic serial number (SNA_3..SNA_0, SNB_3..SNB_0).
func (d *Si7021Full) SerialNumber() (uint64, error) {
	a, err := d.conn.WriteRead([]byte{0xFA, 0x0F}, 8)
	if err != nil {
		return 0, err
	}
	b, err := d.conn.WriteRead([]byte{0xFC, 0xC9}, 6)
	if err != nil {
		return 0, err
	}
	parts := [8]byte{a[0], a[2], a[4], a[6], b[0], b[1], b[3], b[4]}
	var sn uint64
	for _, p := range parts {
		sn = sn<<8 | uint64(p)
	}
	return sn, nil
}

// DeviceType reads SNB_3, the device type byte (0x15 = Si7021, 0x14 = Si7020, 0x0D = Si7013).
func (d *Si7021Full) DeviceType() (byte, error) {
	b, err := d.conn.WriteRead([]byte{0xFC, 0xC9}, 6)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// FirmwareRevision reads the firmware revision (command 0x84 0xB8); 0xFF = firmware 1.0.
func (d *Si7021Full) FirmwareRevision() (byte, error) {
	b, err := d.conn.WriteRead([]byte{0x84, 0xB8}, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// ConversionTime returns the typical RH + temperature conversion time at the
// current resolution, in seconds.
func (d *Si7021Full) ConversionTime() (float32, error) {
	reg, err := d.ReadUserRegister()
	if err != nil {
		return 0, err
	}
	res := (reg>>7&1)<<1 | reg&1
	return si7021RHTime[res] + si7021TTime[res], nil
}

// DewPoint runs one measurement and computes the dew point in °C (Magnus formula).
func (d *Si7021Full) DewPoint() (float32, error) {
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
