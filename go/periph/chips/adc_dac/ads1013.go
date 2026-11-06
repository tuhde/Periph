package adcdac

import (
	"errors"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ADS1013 register pointers.
const (
	ads1013RegConversion uint8 = 0x00
	ads1013RegConfig     uint8 = 0x01
)

// ADS1013 Config reset value and bit masks.
const (
	ads1013ConfigReset uint16 = 0x8583

	ads1013CfgOS         uint16 = 0x8000
	ads1013CfgModeSingle uint16 = 0x0100
	// Reserved bits written at their reset values: 000010 in 14:9, 00011 in 4:0.
	ads1013CfgReserved uint16 = 0x0403
)

// ADS1013I2CAddress is the default 7-bit I²C address (ADDR = GND). The ADDR
// strap selects any address in 0x48-0x4B.
const ADS1013I2CAddress uint8 = 0x48

// ADS1013 data-rate codes (DR[2:0]).
const (
	ADS1013DR128  uint8 = 0
	ADS1013DR250  uint8 = 1
	ADS1013DR490  uint8 = 2
	ADS1013DR920  uint8 = 3
	ADS1013DR1600 uint8 = 4 // default
	ADS1013DR2400 uint8 = 5
	ADS1013DR3300 uint8 = 6
)

// ErrADS1013InvalidArgument is returned for an argument outside its
// documented range. No bus transaction is made.
var ErrADS1013InvalidArgument = errors.New("ADS1013: invalid argument")

var ads1013SPS = [7]uint16{128, 250, 490, 920, 1600, 2400, 3300}

const ads1013LSB float32 = 0.001

// ADS1013Minimal is the minimal ADS1013 interface: single-shot reads of
// AIN0 − AIN1.
//
// ADS1013 12-bit I²C ΔΣ ADC with a fixed ±2.048 V range (Texas
// Instruments): no PGA, multiplexer, comparator or ALERT/RDY pin. Registers
// are 16-bit, big-endian, addressed through a persistent Address Pointer.
// Defaults: 1600 SPS, single-shot. The whole Config word is written for
// every conversion.
type ADS1013Minimal struct {
	conn       connection.RegisterConnection
	dr         uint8
	continuous bool
}

// NewADS1013Minimal creates an ADS1013Minimal. The chip has no ID register,
// so no bus transaction is made.
func NewADS1013Minimal(conn connection.RegisterConnection) (*ADS1013Minimal, error) {
	return &ADS1013Minimal{conn: conn, dr: ADS1013DR1600}, nil
}

func (d *ADS1013Minimal) readReg(reg uint8) (uint16, error) {
	b, err := d.conn.ReadReg(uint32(reg), 2)
	if err != nil {
		return 0, err
	}
	return uint16(b[0])<<8 | uint16(b[1]), nil
}

func (d *ADS1013Minimal) writeReg(reg uint8, value uint16) error {
	return d.conn.WriteReg(uint32(reg), []byte{byte(value >> 8), byte(value)})
}

func (d *ADS1013Minimal) configWord(os, continuous bool) uint16 {
	word := ads1013CfgReserved | uint16(d.dr)<<5
	if os {
		word |= ads1013CfgOS
	}
	if !continuous {
		word |= ads1013CfgModeSingle
	}
	return word
}

func (d *ADS1013Minimal) readConversion() (int16, error) {
	raw, err := d.readReg(ads1013RegConversion)
	if err != nil {
		return 0, err
	}
	return int16(raw) >> 4, nil
}

// ReadRaw runs one single-shot conversion of AIN0 − AIN1 and returns the
// 12-bit two's-complement code (-2048…2047). It writes the whole Config word
// with OS = 1, polls OS until the chip is idle again, then reads CONVERSION.
func (d *ADS1013Minimal) ReadRaw() (int16, error) {
	if err := d.writeReg(ads1013RegConfig, d.configWord(true, false)); err != nil {
		return 0, err
	}
	d.continuous = false
	for i := 0; i < 100; i++ {
		cfg, err := d.readReg(ads1013RegConfig)
		if err != nil {
			return 0, err
		}
		if cfg&ads1013CfgOS != 0 {
			break
		}
		time.Sleep(time.Millisecond)
	}
	return d.readConversion()
}

// ReadVoltage runs one single-shot conversion and returns AIN0 − AIN1 in
// volts (raw × 0.001).
func (d *ADS1013Minimal) ReadVoltage() (float32, error) {
	raw, err := d.ReadRaw()
	if err != nil {
		return 0, err
	}
	return float32(raw) * ads1013LSB, nil
}

// ADS1013Full extends ADS1013Minimal with data-rate selection and
// continuous conversion.
type ADS1013Full struct {
	*ADS1013Minimal
}

// NewADS1013Full creates an ADS1013Full; no bus transaction is made.
func NewADS1013Full(conn connection.RegisterConnection) (*ADS1013Full, error) {
	m, err := NewADS1013Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &ADS1013Full{ADS1013Minimal: m}, nil
}

func (d *ADS1013Full) writeConfig() error {
	return d.writeReg(ads1013RegConfig, d.configWord(false, d.continuous))
}

// FullScale returns the fixed full-scale range: always 2.048 (±V).
func (d *ADS1013Full) FullScale() float32 { return 2.048 }

// LSB returns the size of one conversion code: always 0.001 V.
func (d *ADS1013Full) LSB() float32 { return ads1013LSB }

// SetDataRate selects the conversion data rate (ADS1013DR*). A value above 6
// (7 aliases 3300 SPS) returns ErrADS1013InvalidArgument.
func (d *ADS1013Full) SetDataRate(dr uint8) error {
	if dr > ADS1013DR3300 {
		return ErrADS1013InvalidArgument
	}
	d.dr = dr
	if d.continuous {
		return d.writeConfig()
	}
	return nil
}

// GetDataRate returns the configured DR code (0-6).
func (d *ADS1013Full) GetDataRate() uint8 { return d.dr }

// DataRateSPS returns the samples per second of the current setting.
func (d *ADS1013Full) DataRateSPS() uint16 { return ads1013SPS[d.dr] }

// StartContinuous starts continuous conversion (MODE = 0) at the current
// data rate.
func (d *ADS1013Full) StartContinuous() error {
	d.continuous = true
	return d.writeConfig()
}

// ReadContinuousRaw reads the most recent completed result without touching
// Config (valid at least 1/DR after StartContinuous).
func (d *ADS1013Full) ReadContinuousRaw() (int16, error) { return d.readConversion() }

// ReadContinuous reads the most recent completed result in volts: raw × LSB().
func (d *ADS1013Full) ReadContinuous() (float32, error) {
	raw, err := d.readConversion()
	if err != nil {
		return 0, err
	}
	return float32(raw) * ads1013LSB, nil
}

// StopContinuous stops continuous conversion (MODE = 1, single-shot /
// power-down).
func (d *ADS1013Full) StopContinuous() error {
	d.continuous = false
	return d.writeConfig()
}

// IsConverting reports whether a single-shot conversion is running (OS = 0;
// only meaningful in single-shot mode).
func (d *ADS1013Full) IsConverting() (bool, error) {
	cfg, err := d.readReg(ads1013RegConfig)
	return cfg&ads1013CfgOS == 0, err
}

// ReadConfig reads the raw 16-bit CONFIG register.
func (d *ADS1013Full) ReadConfig() (uint16, error) { return d.readReg(ads1013RegConfig) }

// Reset writes CONFIG = 0x8583 back and clears the cached settings. It is
// not an I²C general-call reset.
func (d *ADS1013Full) Reset() error {
	if err := d.writeReg(ads1013RegConfig, ads1013ConfigReset); err != nil {
		return err
	}
	d.dr, d.continuous = ADS1013DR1600, false
	return nil
}
