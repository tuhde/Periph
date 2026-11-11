package adcdac

import (
	"errors"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ADS7828I2CAddress is the default 7-bit I²C address (A1 = A0 = GND). The A1/A0
// straps select any address in 0x48-0x4B.
const ADS7828I2CAddress uint8 = 0x48

// ADS7828 power-down modes (PD1:PD0 of the command byte).
const (
	ADS7828PowerDownBetween uint8 = 0 // power down between conversions (internal reference off)
	ADS7828RefOffADCOn      uint8 = 1 // internal reference off, ADC on
	ADS7828RefOnADCOff      uint8 = 2 // internal reference on, ADC off
	ADS7828RefOnADCOn       uint8 = 3 // internal reference on, ADC on
)

// ErrADS7828InvalidPowerMode is returned by SetPowerMode when pd is outside 0-3
// or the internal reference is selected and pd has PD1 = 0 (that would turn
// the reference off).
var ErrADS7828InvalidPowerMode = errors.New("ads7828: invalid power mode for the selected reference")

const (
	ads7828InternalVref   float32 = 2.5
	ads7828CmdRefOn       uint8   = 0x08 // lone command: SD=0, C=0, PD=10
	ads7828CmdPowerDown   uint8   = 0x00 // lone command: SD=0, C=0, PD=00
	ads7828RefSettleDelay         = time.Millisecond
)

// ads7828SingleEndedCode returns C2:C0 for single-ended channel n — the
// encoding is interleaved.
func ads7828SingleEndedCode(channel uint8) uint8 {
	return ((channel & 1) << 2) | (channel >> 1)
}

// ADS7828Minimal reads the eight single-ended inputs (CHn against COM) of an
// ADS7828 12-bit, 8-channel ADC (TI / Burr-Brown) as raw codes or voltages.
//
// The chip has no registers: one command byte selects the input mode, the
// channel and the power-down state and starts a conversion; the 12-bit result
// is read back as two bytes (ReadReg(command, 2) is exactly the datasheet's
// write-command / repeated-START / read sequence). The driver defaults to the
// internal 2.5 V reference (LSB 610 µV), kept on between conversions.
type ADS7828Minimal struct {
	conn     connection.RegisterConnection
	internal bool
	refOn    bool
	vref     float32
	pd       uint8
}

// NewADS7828Minimal creates an ADS7828Minimal. vref is 0 for the internal
// 2.5 V reference (REFIN/REFOUT decoupled with 0.1 µF, nothing else driving
// it) or the external reference voltage in volts (0.05 V … VDD) with the
// internal reference off. It performs no I²C traffic; Init (or the first
// conversion) enables the internal reference.
func NewADS7828Minimal(conn connection.RegisterConnection, vref float32) (*ADS7828Minimal, error) {
	d := &ADS7828Minimal{conn: conn}
	d.selectReference(vref)
	return d, nil
}

func (d *ADS7828Minimal) selectReference(vref float32) {
	if vref <= 0 {
		d.internal, d.vref, d.pd = true, ads7828InternalVref, ADS7828RefOnADCOn
	} else {
		d.internal, d.vref, d.pd = false, vref, ADS7828PowerDownBetween
	}
	d.refOn = false
}

func (d *ADS7828Minimal) enableReference() error {
	if err := d.conn.Write([]byte{ads7828CmdRefOn}); err != nil {
		return err
	}
	time.Sleep(ads7828RefSettleDelay)
	d.refOn = true
	return nil
}

// Init enables the internal reference now (no-op with an external one): it
// sends the lone command 0x08 and waits 1 ms for the reference to settle.
// Optional — the first conversion does this automatically.
func (d *ADS7828Minimal) Init() error {
	if d.internal {
		return d.enableReference()
	}
	return nil
}

func (d *ADS7828Minimal) convert(singleEnded bool, code uint8) (uint16, error) {
	if d.internal && !d.refOn {
		if err := d.enableReference(); err != nil {
			return 0, err
		}
	}
	var sd uint8
	if singleEnded {
		sd = 1
	}
	command := sd<<7 | code<<4 | d.pd<<2
	buf, err := d.conn.ReadReg(uint32(command), 2)
	if err != nil {
		return 0, err
	}
	return uint16(buf[0]&0x0F)<<8 | uint16(buf[1]), nil
}

// ReadRaw reads a single-ended channel (CHn against COM) as a 12-bit code
// (0-4095). channel is clamped to 0-7.
func (d *ADS7828Minimal) ReadRaw(channel int) (uint16, error) {
	return d.convert(true, ads7828SingleEndedCode(uint8(ads7828Clamp(channel, 0, 7))))
}

// ReadVoltage reads a single-ended channel as a voltage in volts
// (raw / 4096 × vref). channel is clamped to 0-7.
func (d *ADS7828Minimal) ReadVoltage(channel int) (float32, error) {
	raw, err := d.ReadRaw(channel)
	if err != nil {
		return 0, err
	}
	return float32(raw) / 4096 * d.vref, nil
}

// ReadAll reads all eight single-ended channels, one conversion each, and
// returns the voltages of CH0…CH7 in volts.
func (d *ADS7828Minimal) ReadAll() ([8]float32, error) {
	var out [8]float32
	for ch := 0; ch < 8; ch++ {
		v, err := d.ReadVoltage(ch)
		if err != nil {
			return out, err
		}
		out[ch] = v
	}
	return out, nil
}

func ads7828Clamp(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

// ADS7828Full extends ADS7828Minimal with differential reads (both
// polarities), a signed differential read, run-time reference switching,
// power-down control and multi-channel scans.
type ADS7828Full struct {
	*ADS7828Minimal
}

// NewADS7828Full creates an ADS7828Full. See NewADS7828Minimal for vref.
func NewADS7828Full(conn connection.RegisterConnection, vref float32) (*ADS7828Full, error) {
	m, err := NewADS7828Minimal(conn, vref)
	if err != nil {
		return nil, err
	}
	return &ADS7828Full{m}, nil
}

// ReadDifferentialRaw reads a differential pair as a 12-bit code. pair is
// 0-3 (CH0/CH1 … CH6/CH7, clamped); when inverted the positive input is
// CH(2·pair+1). A negative differential voltage reads 0.
func (d *ADS7828Full) ReadDifferentialRaw(pair int, inverted bool) (uint16, error) {
	p := uint8(ads7828Clamp(pair, 0, 3))
	if inverted {
		p |= 4
	}
	return d.convert(false, p)
}

// ReadDifferential reads a differential pair as a voltage in volts (≥ 0).
func (d *ADS7828Full) ReadDifferential(pair int, inverted bool) (float32, error) {
	raw, err := d.ReadDifferentialRaw(pair, inverted)
	if err != nil {
		return 0, err
	}
	return float32(raw) / 4096 * d.vref, nil
}

// ReadSignedDifferential reads V(CH2p) − V(CH2p+1) with sign, using two
// conversions (normal and inverted polarity). Range −vref … +vref.
func (d *ADS7828Full) ReadSignedDifferential(pair int) (float32, error) {
	normal, err := d.ReadDifferentialRaw(pair, false)
	if err != nil {
		return 0, err
	}
	inverted, err := d.ReadDifferentialRaw(pair, true)
	if err != nil {
		return 0, err
	}
	return float32(int(normal)-int(inverted)) / 4096 * d.vref, nil
}

// SetReference switches between the internal reference (vref = 0, re-runs the
// turn-on and 1 ms settle wait) and an external one (vref in volts, PD1 = 0
// from then on).
func (d *ADS7828Full) SetReference(vref float32) error {
	d.selectReference(vref)
	return d.Init()
}

// GetReference returns the reference voltage used for scaling (2.5 for the
// internal reference).
func (d *ADS7828Full) GetReference() float32 { return d.vref }

// UsesInternalReference reports whether the internal reference is selected.
func (d *ADS7828Full) UsesInternalReference() bool { return d.internal }

// SetPowerMode sets the PD bits used in all following conversion commands
// (ADS7828PowerDownBetween … ADS7828RefOnADCOn). With the internal reference
// selected, values with PD1 = 0 are rejected with ErrADS7828InvalidPowerMode.
func (d *ADS7828Full) SetPowerMode(pd uint8) error {
	if pd > 3 || (d.internal && pd&2 == 0) {
		return ErrADS7828InvalidPowerMode
	}
	d.pd = pd
	return nil
}

// PowerDown powers the chip down completely (≈ 400 nA) with a lone command
// (PD = 00). With the internal reference this turns it off; the next read
// re-runs the turn-on and settle wait automatically.
func (d *ADS7828Full) PowerDown() error {
	if err := d.conn.Write([]byte{ads7828CmdPowerDown}); err != nil {
		return err
	}
	d.refOn = false
	return nil
}

// ReadScan reads the given single-ended channels in order as raw codes, one
// command/read pair each.
func (d *ADS7828Full) ReadScan(channels []int) ([]uint16, error) {
	out := make([]uint16, 0, len(channels))
	for _, ch := range channels {
		raw, err := d.ReadRaw(ch)
		if err != nil {
			return out, err
		}
		out = append(out, raw)
	}
	return out, nil
}

// LSB returns the size of one code step: vref / 4096 in volts (610 µV at 2.5 V).
func (d *ADS7828Full) LSB() float32 { return d.vref / 4096 }
