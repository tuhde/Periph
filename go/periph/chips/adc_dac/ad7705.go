// Package adcdac contains drivers for analog-to-digital and digital-to-analog
// converters (ADCs and DACs).
package adcdac

import (
	"fmt"

	"github.com/tuhde/Periph/go/periph/connection"
)

// Master clock frequencies the AD7705 supports.
const (
	MCLK1MHz      uint32 = 1000000
	MCLK2MHz      uint32 = 2000000
	MCLK2_4576MHz uint32 = 2457600
	MCLK4_9152MHz uint32 = 4915200
)

// PGA gain settings exposed by Configure().
const (
	GAIN1   uint8 = 0
	GAIN2   uint8 = 1
	GAIN4   uint8 = 2
	GAIN8   uint8 = 3
	GAIN16  uint8 = 4
	GAIN32  uint8 = 5
	GAIN64  uint8 = 6
	GAIN128 uint8 = 7
)

const (
	regCOMM   uint8 = 0x00
	regSETUP  uint8 = 0x10
	regCLOCK  uint8 = 0x20
	regDATA   uint8 = 0x30
	regOFFSET uint8 = 0x60
	regGAIN   uint8 = 0x70
)

const (
	rwWRITE uint8 = 0x00
	rwREAD  uint8 = 0x08
)

const (
	ch1 uint8 = 0x00
	ch2 uint8 = 0x01
)

const (
	modeNormal   uint8 = 0x00
	modeSelfCal  uint8 = 0x40
	modeZeroSys  uint8 = 0x80
	modeFullSys  uint8 = 0xC0
)

var gainBits = [8]uint8{0x00, 0x08, 0x10, 0x18, 0x20, 0x28, 0x30, 0x38}
var gainToIdx = map[uint8]uint8{1: 0, 2: 1, 4: 2, 8: 3, 16: 4, 32: 5, 64: 6, 128: 7}

const (
	bipolar    uint8 = 0x00
	unipolar   uint8 = 0x04
	unbuffered uint8 = 0x00
	buffered   uint8 = 0x02
	fsyncRun   uint8 = 0x00
	stbyRun    uint8 = 0x00
	stbySleep  uint8 = 0x04
	drdyMask   uint8 = 0x80
)

var fsRates1MHz   = [4]uint16{20, 25, 100, 200}
var fsRates2_4MHz = [4]uint16{50, 60, 250, 500}

func commByte(reg uint8, read bool, channel uint8) uint8 {
	b := reg
	if read {
		b |= rwREAD
	} else {
		b |= rwWRITE
	}
	b |= channel & 0x03
	return b
}

func writeRegChannel(conn connection.Connection, reg uint8, value uint32, channel uint8, nBytes uint8) error {
	buf := make([]byte, 1+nBytes)
	buf[0] = commByte(reg, false, channel)
	for i := int(nBytes) - 1; i >= 0; i-- {
		buf[1+(int(nBytes)-1-i)] = uint8((value >> (8 * uint(i))) & 0xFF)
	}
	return conn.Write(buf)
}

func readRegChannel(conn connection.Connection, reg uint8, channel uint8, nBytes uint8) (uint32, error) {
	comm := []byte{commByte(reg, true, channel)}
	raw, err := conn.WriteRead(comm, int(nBytes))
	if err != nil {
		return 0, err
	}
	var value uint32
	for i := uint8(0); i < nBytes; i++ {
		value = (value << 8) | uint32(raw[i])
	}
	return value, nil
}

func waitDRDY(conn connection.Connection) error {
	for {
		comm := []byte{commByte(regCOMM, true, ch1)}
		raw, err := conn.WriteRead(comm, 1)
		if err != nil {
			return err
		}
		if raw[0]&drdyMask == 0 {
			return nil
		}
	}
}

func configureClock(conn connection.Connection, mclkHz uint32, outputRateHz uint16) error {
	clkBit := uint8(0x00)
	if mclkHz >= MCLK2_4576MHz {
		clkBit = 0x04
	}
	clkDivBit := uint8(0x00)
	if mclkHz == MCLK2MHz || mclkHz == MCLK4_9152MHz {
		clkDivBit = 0x08
	}
	rates := fsRates1MHz
	if mclkHz >= MCLK2_4576MHz {
		rates = fsRates2_4MHz
	}
	var fsBits uint8
	for i, r := range rates {
		if r == outputRateHz {
			fsBits = uint8(i)
			break
		}
	}
	return writeRegChannel(conn, regCLOCK, uint32(clkDivBit|clkBit|fsBits), ch1, 1)
}

func codeToVoltage(code uint16, gain uint8, bipolarFlag bool, vref float64) float64 {
	if bipolarFlag {
		return (float64(int32(code)-32768) / 32768.0) * (vref / float64(gain))
	}
	return (float64(code) / 65536.0) * (vref / float64(gain))
}

// AD7705Minimal is the AD7705 2-channel, 16-bit sigma-delta ADC — minimal
// interface: reads calibrated voltage from Channel 1 with sensible defaults.
//
// Default configuration baked in at construction: gain 1, bipolar, unbuffered,
// 50 Hz output rate on a 2.4576/4.9152 MHz clock or 20 Hz on 1/2 MHz, with
// Channel 1 self-calibrated once. DRDY is polled over SPI by inspecting bit 7
// of the Communication Register, matching the datasheet's 3-wire microcontroller
// interface technique (no dedicated DRDY GPIO required).
type AD7705Minimal struct {
	conn     connection.Connection
	vref     float64
	mclkHz   uint32
	gain     uint8
	bipolar  bool
	buffered bool
}

// NewAD7705Minimal creates and initialises the AD7705.
//
// vref is the reference voltage in V (REF IN(+) − REF IN(−)). mclkHz must be
// one of MCLK1MHz, MCLK2MHz, MCLK2_4576MHz, MCLK4_9152MHz.
func NewAD7705Minimal(conn connection.Connection, vref float64, mclkHz uint32) (*AD7705Minimal, error) {
	c := &AD7705Minimal{
		conn:     conn,
		vref:     vref,
		mclkHz:   mclkHz,
		gain:     1,
		bipolar:  true,
		buffered: false,
	}
	if err := c.Init(); err != nil {
		return nil, err
	}
	return c, nil
}

// Init re-runs the initialisation sequence (Clock Register, self-calibrate Channel 1).
func (c *AD7705Minimal) Init() error {
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
func (c *AD7705Minimal) ReadRaw() (uint16, error) {
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
func (c *AD7705Minimal) ReadVoltage() (float64, error) {
	code, err := c.ReadRaw()
	if err != nil {
		return 0, err
	}
	return codeToVoltage(code, c.gain, c.bipolar, c.vref), nil
}

// AD7705Full is the AD7705 full driver — adds per-channel configuration,
// calibration, and power control.
//
// Embeds AD7705Minimal via struct embedding so its methods are promoted onto
// AD7705Full automatically — no delegate methods to write by hand.
type AD7705Full struct {
	AD7705Minimal
}

// NewAD7705Full creates and initialises the AD7705.
func NewAD7705Full(conn connection.Connection, vref float64, mclkHz uint32) (*AD7705Full, error) {
	min, err := NewAD7705Minimal(conn, vref, mclkHz)
	if err != nil {
		return nil, err
	}
	return &AD7705Full{AD7705Minimal: *min}, nil
}

// Configure writes the Setup and Clock Registers for the given channel.
// Does not calibrate — call SelfCalibrate() (or one of the system-calibration
// methods) afterward.
func (c *AD7705Full) Configure(channel uint8, gain uint8, bipolarFlag bool, buffered bool, outputRateHz uint16) error {
	ch := ch1
	switch channel {
	case 1:
		ch = ch1
	case 2:
		ch = ch2
	default:
		return fmt.Errorf("ad7705: channel must be 1 or 2")
	}
	if _, ok := gainToIdx[gain]; !ok {
		return fmt.Errorf("ad7705: gain must be one of 1, 2, 4, 8, 16, 32, 64, 128")
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
		return fmt.Errorf("ad7705: output_rate_hz must be one of %v Hz", rates)
	}

	if err := configureClock(c.conn, c.mclkHz, outputRateHz); err != nil {
		return err
	}

	var bu uint8 = bipolar
	if !bipolarFlag {
		bu = unipolar
	}
	var bufBit uint8 = unbuffered
	if buffered {
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
		c.buffered = buffered
	}
	return nil
}

// ReadRawChannel blocks until DRDY, then reads the raw 16-bit code for the channel.
func (c *AD7705Full) ReadRawChannel(channel uint8) (uint16, error) {
	ch := ch1
	switch channel {
	case 1:
		ch = ch1
	case 2:
		ch = ch2
	default:
		return 0, fmt.Errorf("ad7705: channel must be 1 or 2")
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
func (c *AD7705Full) ReadVoltageChannel(channel uint8) (float64, error) {
	code, err := c.ReadRawChannel(channel)
	if err != nil {
		return 0, err
	}
	return codeToVoltage(code, c.gain, c.bipolar, c.vref), nil
}

// SelfCalibrate runs an internal self-calibration on the channel.
func (c *AD7705Full) SelfCalibrate(channel uint8) error {
	return c.runCalibration(channel, modeSelfCal)
}

// SystemCalibrateZero runs a zero-scale system calibration. The caller must
// present the zero-scale voltage at AIN before calling and hold it stable
// until this returns.
func (c *AD7705Full) SystemCalibrateZero(channel uint8) error {
	return c.runCalibration(channel, modeZeroSys)
}

// SystemCalibrateFull runs a full-scale system calibration. The caller must
// present the full-scale voltage at AIN before calling and hold it stable
// until this returns.
func (c *AD7705Full) SystemCalibrateFull(channel uint8) error {
	return c.runCalibration(channel, modeFullSys)
}

func (c *AD7705Full) runCalibration(channel uint8, mode uint8) error {
	ch := ch1
	switch channel {
	case 1:
		ch = ch1
	case 2:
		ch = ch2
	default:
		return fmt.Errorf("ad7705: channel must be 1 or 2")
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
func (c *AD7705Full) GetOffsetCalibration(channel uint8) (uint32, error) {
	ch := ch1
	if channel == 2 {
		ch = ch2
	} else if channel != 1 {
		return 0, fmt.Errorf("ad7705: channel must be 1 or 2")
	}
	return readRegChannel(c.conn, regOFFSET, ch, 3)
}

// SetOffsetCalibration writes a 24-bit Zero-Scale Calibration Register for the channel.
func (c *AD7705Full) SetOffsetCalibration(value uint32, channel uint8) error {
	ch := ch1
	if channel == 2 {
		ch = ch2
	} else if channel != 1 {
		return fmt.Errorf("ad7705: channel must be 1 or 2")
	}
	return writeRegChannel(c.conn, regOFFSET, value&0xFFFFFF, ch, 3)
}

// GetGainCalibration reads the 24-bit Full-Scale Calibration Register for the channel.
func (c *AD7705Full) GetGainCalibration(channel uint8) (uint32, error) {
	ch := ch1
	if channel == 2 {
		ch = ch2
	} else if channel != 1 {
		return 0, fmt.Errorf("ad7705: channel must be 1 or 2")
	}
	return readRegChannel(c.conn, regGAIN, ch, 3)
}

// SetGainCalibration writes a 24-bit Full-Scale Calibration Register for the channel.
func (c *AD7705Full) SetGainCalibration(value uint32, channel uint8) error {
	ch := ch1
	if channel == 2 {
		ch = ch2
	} else if channel != 1 {
		return fmt.Errorf("ad7705: channel must be 1 or 2")
	}
	return writeRegChannel(c.conn, regGAIN, value&0xFFFFFF, ch, 3)
}

// Standby enters standby/power-down (~10 µA). Registers are retained.
func (c *AD7705Full) Standby() error {
	comm := []byte{commByte(regCOMM, false, ch1) | stbySleep}
	return c.conn.Write(comm)
}

// Wakeup exits standby and blocks until a fresh conversion is available.
func (c *AD7705Full) Wakeup() error {
	comm := []byte{commByte(regCOMM, false, ch1) | stbyRun}
	if err := c.conn.Write(comm); err != nil {
		return err
	}
	return waitDRDY(c.conn)
}
