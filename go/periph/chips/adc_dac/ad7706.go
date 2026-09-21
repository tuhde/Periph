// Package adcdac contains drivers for analog-to-digital and digital-to-analog
// converters (ADCs and DACs).
package adcdac

import (
	"fmt"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// AD7706 shares its master clock frequencies, PGA gain settings, register
// map, and register-level helpers with the AD7705 (same package, ad7705.go)
// — the two chips are protocol-identical except for channel count. Only the
// third channel's select constant and its channel-validation helper are
// AD7706-specific.
const ch3 uint8 = 0x03

func channelConst(channel uint8) (uint8, error) {
	switch channel {
	case 1:
		return ch1, nil
	case 2:
		return ch2, nil
	case 3:
		return ch3, nil
	}
	return 0, fmt.Errorf("ad7706: channel must be 1, 2, or 3")
}

// AD7706Minimal is the AD7706 3-channel, 16-bit sigma-delta ADC — minimal
// interface: reads calibrated voltage from Channel 1 (AIN1 relative to COMMON)
// with sensible defaults.
//
// Default configuration baked in at construction: gain 1, bipolar, unbuffered,
// 50 Hz output rate on a 2.4576/4.9152 MHz clock or 20 Hz on 1/2 MHz, with
// Channel 1 self-calibrated once. DRDY is polled over SPI by inspecting bit 7
// of the Communication Register, matching the datasheet's 3-wire microcontroller
// interface technique (no dedicated DRDY GPIO required).
type AD7706Minimal struct {
	conn     connection.Connection
	vref     float64
	mclkHz   uint32
	gain     uint8
	bipolar  bool
	buffered bool
	resetPin connection.OutputPin // nil if RESET is not wired
}

// NewAD7706Minimal creates and initialises the AD7706.
//
// vref is the reference voltage in V (REF IN(+) − REF IN(−)). mclkHz must be
// one of MCLK1MHz, MCLK2MHz, MCLK2_4576MHz, MCLK4_9152MHz. resetPin may be
// nil if RESET is not wired; when supplied, a hardware reset pulse is issued
// before configuration.
func NewAD7706Minimal(conn connection.Connection, vref float64, mclkHz uint32, resetPin connection.OutputPin) (*AD7706Minimal, error) {
	c := &AD7706Minimal{
		conn:     conn,
		vref:     vref,
		mclkHz:   mclkHz,
		gain:     1,
		bipolar:  true,
		buffered: false,
		resetPin: resetPin,
	}
	if err := c.Init(); err != nil {
		return nil, err
	}
	return c, nil
}

// hardwareReset pulses RESET low for >=200 ns then high again.
func (c *AD7706Minimal) hardwareReset() error {
	if err := c.resetPin.Set(false); err != nil {
		return err
	}
	time.Sleep(200 * time.Nanosecond)
	return c.resetPin.Set(true)
}

// Init re-runs the initialisation sequence (optional hardware reset, Clock
// Register, self-calibrate Channel 1).
func (c *AD7706Minimal) Init() error {
	if c.resetPin != nil {
		if err := c.hardwareReset(); err != nil {
			return err
		}
	}
	defaultRate := fsRates1MHz[0]
	if c.mclkHz >= MCLK2_4576MHz {
		defaultRate = fsRates2_4MHz[0]
	}
	if err := configureClock(c.conn, c.mclkHz, defaultRate); err != nil {
		return err
	}
	setup := modeSelfCal | gainBits[0] | bipolar | unbuffered | fsyncRun
	if err := writeRegChannel(c.conn, regSETUP, uint32(setup), ch1, 1); err != nil {
		return err
	}
	return waitDRDY(c.conn)
}

// ReadRaw blocks until DRDY, then reads and returns the raw 16-bit Data
// Register code on Channel 1.
func (c *AD7706Minimal) ReadRaw() (uint16, error) {
	if err := waitDRDY(c.conn); err != nil {
		return 0, err
	}
	v, err := readRegChannel(c.conn, regDATA, ch1, 2)
	if err != nil {
		return 0, err
	}
	return uint16(v), nil
}

// ReadVoltage blocks until DRDY, then returns the input voltage on Channel 1 in V.
func (c *AD7706Minimal) ReadVoltage() (float64, error) {
	code, err := c.ReadRaw()
	if err != nil {
		return 0, err
	}
	return codeToVoltage(code, c.gain, c.bipolar, c.vref), nil
}

// AD7706Full is the AD7706 full driver — adds per-channel configuration,
// calibration, and power control.
//
// Embeds AD7706Minimal via struct embedding so its methods are promoted onto
// AD7706Full automatically — no delegate methods to write by hand.
type AD7706Full struct {
	AD7706Minimal
}

// NewAD7706Full creates and initialises the AD7706. resetPin may be nil if
// RESET is not wired.
func NewAD7706Full(conn connection.Connection, vref float64, mclkHz uint32, resetPin connection.OutputPin) (*AD7706Full, error) {
	min, err := NewAD7706Minimal(conn, vref, mclkHz, resetPin)
	if err != nil {
		return nil, err
	}
	return &AD7706Full{AD7706Minimal: *min}, nil
}

// Configure writes the Setup and Clock Registers for the given channel.
// Does not calibrate — call SelfCalibrate() (or one of the system-calibration
// methods) afterward.
func (c *AD7706Full) Configure(channel uint8, gain uint8, bipolarFlag bool, bufferedFlag bool, outputRateHz uint16) error {
	ch, err := channelConst(channel)
	if err != nil {
		return err
	}
	if _, ok := gainToIdx[gain]; !ok {
		return fmt.Errorf("ad7706: gain must be one of 1, 2, 4, 8, 16, 32, 64, 128")
	}
	rates := fsRates1MHz
	if c.mclkHz >= MCLK2_4576MHz {
		rates = fsRates2_4MHz
	}
	rateOK := false
	for _, r := range rates {
		if r == outputRateHz {
			rateOK = true
			break
		}
	}
	if !rateOK {
		return fmt.Errorf("ad7706: output_rate_hz must be one of %v Hz", rates)
	}

	if err := configureClock(c.conn, c.mclkHz, outputRateHz); err != nil {
		return err
	}

	var bu uint8 = bipolar
	if !bipolarFlag {
		bu = unipolar
	}
	var bufBit uint8 = unbuffered
	if bufferedFlag {
		bufBit = buffered
	}
	gainIdx := gainToIdx[gain]
	setup := modeNormal | gainBits[gainIdx] | bu | bufBit | fsyncRun
	if err := writeRegChannel(c.conn, regSETUP, uint32(setup), ch, 1); err != nil {
		return err
	}
	if channel == 1 {
		c.gain = gain
		c.bipolar = bipolarFlag
		c.buffered = bufferedFlag
	}
	return nil
}

// ReadRawChannel blocks until DRDY, then reads the raw 16-bit code for the channel.
func (c *AD7706Full) ReadRawChannel(channel uint8) (uint16, error) {
	ch, err := channelConst(channel)
	if err != nil {
		return 0, err
	}
	if err := waitDRDY(c.conn); err != nil {
		return 0, err
	}
	v, err := readRegChannel(c.conn, regDATA, ch, 2)
	if err != nil {
		return 0, err
	}
	return uint16(v), nil
}

// ReadVoltageChannel blocks until DRDY, then returns the input voltage on the channel in V.
func (c *AD7706Full) ReadVoltageChannel(channel uint8) (float64, error) {
	code, err := c.ReadRawChannel(channel)
	if err != nil {
		return 0, err
	}
	return codeToVoltage(code, c.gain, c.bipolar, c.vref), nil
}

// SelfCalibrate runs an internal self-calibration on the channel.
func (c *AD7706Full) SelfCalibrate(channel uint8) error {
	return c.runCalibration(channel, modeSelfCal)
}

// SystemCalibrateZero runs a zero-scale system calibration. The caller must
// present the zero-scale voltage at AIN before calling and hold it stable
// until this returns.
func (c *AD7706Full) SystemCalibrateZero(channel uint8) error {
	return c.runCalibration(channel, modeZeroSys)
}

// SystemCalibrateFull runs a full-scale system calibration. The caller must
// present the full-scale voltage at AIN before calling and hold it stable
// until this returns.
func (c *AD7706Full) SystemCalibrateFull(channel uint8) error {
	return c.runCalibration(channel, modeFullSys)
}

func (c *AD7706Full) runCalibration(channel uint8, mode uint8) error {
	ch, err := channelConst(channel)
	if err != nil {
		return err
	}
	var bu uint8 = bipolar
	if !c.bipolar {
		bu = unipolar
	}
	var bufBit uint8 = unbuffered
	if c.buffered {
		bufBit = buffered
	}
	gainIdx := gainToIdx[c.gain]
	setup := mode | gainBits[gainIdx] | bu | bufBit | fsyncRun
	if err := writeRegChannel(c.conn, regSETUP, uint32(setup), ch, 1); err != nil {
		return err
	}
	return waitDRDY(c.conn)
}

// GetOffsetCalibration reads the 24-bit Zero-Scale Calibration Register for the channel.
func (c *AD7706Full) GetOffsetCalibration(channel uint8) (uint32, error) {
	ch, err := channelConst(channel)
	if err != nil {
		return 0, err
	}
	return readRegChannel(c.conn, regOFFSET, ch, 3)
}

// SetOffsetCalibration writes a 24-bit Zero-Scale Calibration Register for the channel.
func (c *AD7706Full) SetOffsetCalibration(value uint32, channel uint8) error {
	ch, err := channelConst(channel)
	if err != nil {
		return err
	}
	return writeRegChannel(c.conn, regOFFSET, value&0xFFFFFF, ch, 3)
}

// GetGainCalibration reads the 24-bit Full-Scale Calibration Register for the channel.
func (c *AD7706Full) GetGainCalibration(channel uint8) (uint32, error) {
	ch, err := channelConst(channel)
	if err != nil {
		return 0, err
	}
	return readRegChannel(c.conn, regGAIN, ch, 3)
}

// SetGainCalibration writes a 24-bit Full-Scale Calibration Register for the channel.
func (c *AD7706Full) SetGainCalibration(value uint32, channel uint8) error {
	ch, err := channelConst(channel)
	if err != nil {
		return err
	}
	return writeRegChannel(c.conn, regGAIN, value&0xFFFFFF, ch, 3)
}

// Standby enters standby/power-down (~10 µA). Registers are retained.
func (c *AD7706Full) Standby() error {
	comm := []byte{commByte(regCOMM, false, ch1) | stbySleep}
	return c.conn.Write(comm)
}

// Wakeup exits standby and blocks until a fresh conversion is available.
func (c *AD7706Full) Wakeup() error {
	comm := []byte{commByte(regCOMM, false, ch1) | stbyRun}
	if err := c.conn.Write(comm); err != nil {
		return err
	}
	return waitDRDY(c.conn)
}

// Reset pulses the hardware RESET line. Requires a resetPin to have been
// supplied at construction. All registers return to power-on defaults —
// re-run Configure and a calibration afterward.
func (c *AD7706Full) Reset() error {
	if c.resetPin == nil {
		return fmt.Errorf("ad7706: reset requires resetPin to have been supplied at construction")
	}
	return c.hardwareReset()
}
