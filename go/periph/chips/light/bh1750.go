package light

import (
	"errors"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// BH1750 opcodes (single byte; the chip has no register pointer).
const (
	bh1750OpPowerDown = 0x00
	bh1750OpPowerOn   = 0x01
	bh1750OpReset     = 0x07
	bh1750OpMTHigh    = 0x40
	bh1750OpMTLow     = 0x60

	bh1750DefaultMTreg = 69
)

// BH1750Mode selects the measurement mode.
type BH1750Mode uint8

// Measurement modes.
const (
	BH1750ModeH  BH1750Mode = 0 // H-resolution, 1 lx steps, 180 ms maximum
	BH1750ModeH2 BH1750Mode = 1 // H-resolution mode 2, 0.5 lx steps, 180 ms maximum
	BH1750ModeL  BH1750Mode = 2 // L-resolution, 4 lx steps, 24 ms maximum
)

var (
	bh1750OpContinuous = [3]byte{0x10, 0x11, 0x13}
	bh1750OpOneTime    = [3]byte{0x20, 0x21, 0x23}
	// maximum measurement time in milliseconds at MTreg = 69, indexed by mode
	bh1750TMaxMs = [3]float64{180, 180, 24}
)

// ErrBH1750MTreg is returned for a measurement-time register value outside 31-254.
var ErrBH1750MTreg = errors.New("bh1750: MTreg must be 31-254")

// ErrBH1750Mode is returned for an invalid measurement mode.
var ErrBH1750Mode = errors.New("bh1750: invalid mode")

// BH1750Minimal is the BH1750FVI ambient light sensor driver (ROHM) — minimal interface.
//
// Each Read starts one one-time H-resolution measurement (opcode 0x20, 1 lx
// steps), waits the datasheet maximum of 180 ms, reads the 16-bit result and
// returns the illuminance in lux (raw / 1.2). The chip powers down by itself
// after the measurement. The chip is command driven, so a plain I²C
// connection is used. The constructor does no bus access; Init powers the
// chip on.
type BH1750Minimal struct {
	conn  connection.Connection
	mtreg uint8
	mode  BH1750Mode
}

// NewBH1750Minimal creates the driver. conn must be a configured I²C
// connection bound to 0x23 or 0x5C.
func NewBH1750Minimal(conn connection.Connection) *BH1750Minimal {
	return &BH1750Minimal{conn: conn, mtreg: bh1750DefaultMTreg, mode: BH1750ModeH}
}

func (d *BH1750Minimal) command(code byte) error {
	return d.conn.Write([]byte{code})
}

// Init powers the chip on (opcode 0x01).
func (d *BH1750Minimal) Init() error {
	return d.command(bh1750OpPowerOn)
}

func (d *BH1750Minimal) wait(mode BH1750Mode) time.Duration {
	ms := bh1750TMaxMs[mode] * float64(d.mtreg) / bh1750DefaultMTreg
	return time.Duration(math.Ceil(ms)) * time.Millisecond
}

func (d *BH1750Minimal) toLux(raw uint16, mode BH1750Mode) float32 {
	lux := float32(raw) / 1.2 * (float32(bh1750DefaultMTreg) / float32(d.mtreg))
	if mode == BH1750ModeH2 {
		return lux / 2
	}
	return lux
}

func (d *BH1750Minimal) readWord() (uint16, error) {
	b, err := d.conn.Read(2)
	if err != nil {
		return 0, err
	}
	return uint16(b[0])<<8 | uint16(b[1]), nil
}

func (d *BH1750Minimal) measureRaw(mode BH1750Mode) (uint16, error) {
	if err := d.command(bh1750OpOneTime[mode]); err != nil {
		return 0, err
	}
	time.Sleep(d.wait(mode))
	return d.readWord()
}

// Read measures the illuminance once and returns lux (one-time measurement in
// the current mode, H-resolution by default).
func (d *BH1750Minimal) Read() (float32, error) {
	raw, err := d.measureRaw(d.mode)
	if err != nil {
		return 0, err
	}
	return d.toLux(raw, d.mode), nil
}

// BH1750Full is the BH1750FVI driver with the complete feature set: raw
// codes, one-time measurements in each mode, continuous measurement,
// power-down/power-on, reset, the measurement-time register (MTreg) and
// sensitivity adjustment. Read uses the mode of the last MeasureOnce or
// StartContinuous call (H-resolution by default).
type BH1750Full struct {
	BH1750Minimal
}

// NewBH1750Full creates the full driver; same argument as NewBH1750Minimal.
func NewBH1750Full(conn connection.Connection) *BH1750Full {
	return &BH1750Full{BH1750Minimal{conn: conn, mtreg: bh1750DefaultMTreg, mode: BH1750ModeH}}
}

func bh1750CheckMode(mode BH1750Mode) error {
	if mode > BH1750ModeL {
		return ErrBH1750Mode
	}
	return nil
}

// ReadRaw runs one one-time measurement in the current mode and returns the raw 16-bit count.
func (d *BH1750Full) ReadRaw() (uint16, error) {
	return d.measureRaw(d.mode)
}

// MeasureOnce runs one one-time measurement in mode and returns lux.
func (d *BH1750Full) MeasureOnce(mode BH1750Mode) (float32, error) {
	if err := bh1750CheckMode(mode); err != nil {
		return 0, err
	}
	d.mode = mode
	raw, err := d.measureRaw(mode)
	if err != nil {
		return 0, err
	}
	return d.toLux(raw, mode), nil
}

// StartContinuous starts continuous measurements; the first result is ready
// after MeasurementTime(mode).
func (d *BH1750Full) StartContinuous(mode BH1750Mode) error {
	if err := bh1750CheckMode(mode); err != nil {
		return err
	}
	d.mode = mode
	return d.command(bh1750OpContinuous[mode])
}

// ReadContinuous reads the latest continuous-mode result without waiting; lux.
func (d *BH1750Full) ReadContinuous() (float32, error) {
	raw, err := d.readWord()
	if err != nil {
		return 0, err
	}
	return d.toLux(raw, d.mode), nil
}

// PowerDown enters power-down (opcode 0x00).
func (d *BH1750Full) PowerDown() error { return d.command(bh1750OpPowerDown) }

// PowerOn leaves power-down and waits for a measurement command (opcode 0x01).
func (d *BH1750Full) PowerOn() error { return d.command(bh1750OpPowerOn) }

// Reset clears the illuminance data register (opcode 0x07; powers on first,
// as reset is ignored in power-down).
func (d *BH1750Full) Reset() error {
	if err := d.command(bh1750OpPowerOn); err != nil {
		return err
	}
	return d.command(bh1750OpReset)
}

// SetMeasurementTime writes the measurement-time register (two opcodes, high
// bits then low bits). mtreg must be 31-254 (default 69); otherwise
// ErrBH1750MTreg is returned and nothing is written.
func (d *BH1750Full) SetMeasurementTime(mtreg uint8) error {
	if mtreg < 31 || mtreg > 254 {
		return ErrBH1750MTreg
	}
	if err := d.command(bh1750OpMTHigh | mtreg>>5); err != nil {
		return err
	}
	if err := d.command(bh1750OpMTLow | mtreg&0x1F); err != nil {
		return err
	}
	d.mtreg = mtreg
	return nil
}

// GetMeasurementTimeRegister returns the last written MTreg (the chip has no
// read-back; 69 after Init).
func (d *BH1750Full) GetMeasurementTimeRegister() uint8 { return d.mtreg }

// SetSensitivity sets the sensitivity relative to the default
// (MTreg = round(69 × factor), factor 0.45-3.68). ErrBH1750MTreg is returned if
// the resulting MTreg is outside 31-254.
func (d *BH1750Full) SetSensitivity(factor float32) error {
	mt := math.Round(float64(bh1750DefaultMTreg) * float64(factor))
	if mt < 31 || mt > 254 {
		return ErrBH1750MTreg
	}
	return d.SetMeasurementTime(uint8(mt))
}

// MeasurementTime returns the maximum measurement time of mode at the current MTreg, in seconds.
func (d *BH1750Full) MeasurementTime(mode BH1750Mode) float32 {
	if mode > BH1750ModeL {
		mode = BH1750ModeH
	}
	return float32(bh1750TMaxMs[mode] / 1000.0 * float64(d.mtreg) / bh1750DefaultMTreg)
}

// LuxPerCount returns the illuminance represented by one raw count at the current MTreg, in lux.
func (d *BH1750Full) LuxPerCount(mode BH1750Mode) float32 {
	if mode > BH1750ModeL {
		mode = BH1750ModeH
	}
	return d.toLux(1, mode)
}
