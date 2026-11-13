package adcdac

import (
	"errors"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// MCP3422 configuration byte: RDY C1 C0 O/C S1 S0 G1 G0. The chip has no
// register map: a write is the configuration byte and a read returns the
// result bytes followed by the configuration byte, so the driver uses the plain
// Connection Write / Read calls.
const (
	mcp3422CfgRDY  uint8   = 0x80
	mcp3422CfgCont uint8   = 0x10
	mcp3422VRef    float32 = 2.048
)

// MCP3422I2CAddress is the 7-bit I²C address of the standard part (order code
// A0); the order-code variants use 0x68 … 0x6F.
const MCP3422I2CAddress uint8 = 0x68

// Errors returned by the MCP3422 driver.
var (
	// ErrMCP3422Timeout means a conversion did not finish within twice its nominal time plus 10 ms.
	ErrMCP3422Timeout = errors.New("mcp3422: conversion did not finish")
	// ErrMCP3422Resolution is returned for a resolution other than 12, 14, 16 or 18.
	ErrMCP3422Resolution = errors.New("mcp3422: resolution must be 12, 14, 16 or 18")
	// ErrMCP3422Gain is returned for a gain other than 1, 2, 4 or 8.
	ErrMCP3422Gain = errors.New("mcp3422: gain must be 1, 2, 4 or 8")
	// ErrMCP3422NotContinuous is returned by the continuous reads outside continuous mode.
	ErrMCP3422NotContinuous = errors.New("mcp3422: not in continuous mode")
)

// mcp3422Wait waits between conversion steps; tests replace it with a no-op.
var mcp3422Wait = time.Sleep

func mcp3422S(bits int) uint8 {
	switch bits {
	case 12:
		return 0
	case 14:
		return 1
	case 16:
		return 2
	}
	return 3
}

func mcp3422G(gain int) uint8 {
	switch gain {
	case 1:
		return 0
	case 2:
		return 1
	case 4:
		return 2
	}
	return 3
}

func mcp3422ConvTime(bits int) time.Duration {
	switch bits {
	case 12:
		return time.Second / 240
	case 14:
		return time.Second / 60
	case 16:
		return time.Second / 15
	}
	return time.Second * 4 / 15 // 1 / 3.75 s
}

// MCP3422Minimal reads the two differential inputs of a MCP3422 2-channel
// 18-bit delta-sigma ADC (Microchip) with one one-shot conversion per call.
// Defaults: one-shot mode (lowest power), 16 bit / 15 SPS (LSB 62.5 µV), gain
// x1 (±2.048 V full scale). The constructor writes that configuration with
// RDY = 0, so nothing is started and the chip goes to standby.
type MCP3422Minimal struct {
	conn       connection.Connection
	bits       int
	gain       int
	channel    uint8
	continuous bool
	discard    bool
}

// NewMCP3422Minimal creates a MCP3422Minimal and writes the default
// configuration.
func NewMCP3422Minimal(conn connection.Connection) (*MCP3422Minimal, error) {
	d := &MCP3422Minimal{conn: conn, bits: 16, gain: 1}
	if err := conn.Write([]byte{d.config(false)}); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *MCP3422Minimal) config(rdy bool) uint8 {
	c := d.channel<<5 | mcp3422S(d.bits)<<2 | mcp3422G(d.gain)
	if rdy {
		c |= mcp3422CfgRDY
	}
	if d.continuous {
		c |= mcp3422CfgCont
	}
	return c
}

func (d *MCP3422Minimal) nbytes() int {
	if d.bits == 18 {
		return 3
	}
	return 2
}

func (d *MCP3422Minimal) decode(data []byte) int32 {
	var raw int64
	for _, b := range data[:d.nbytes()] {
		raw = raw<<8 | int64(b)
	}
	span := int64(1) << uint(d.bits)
	raw &= span - 1
	if raw >= span/2 {
		raw -= span
	}
	return int32(raw)
}

func (d *MCP3422Minimal) waitReady() (int32, error) {
	conv := mcp3422ConvTime(d.bits)
	polls := int((2*conv+10*time.Millisecond)/(5*time.Millisecond)) + 1
	n := d.nbytes()
	for i := 0; i < polls; i++ {
		data, err := d.conn.Read(n + 1)
		if err != nil {
			return 0, err
		}
		if data[n]&mcp3422CfgRDY == 0 {
			return d.decode(data), nil
		}
		mcp3422Wait(5 * time.Millisecond)
	}
	return 0, ErrMCP3422Timeout
}

func (d *MCP3422Minimal) convert(channel int) (int32, error) {
	if channel < 1 {
		d.channel = 0
	} else {
		d.channel = 1
	}
	d.continuous = false
	d.discard = false
	if err := d.conn.Write([]byte{d.config(true)}); err != nil {
		return 0, err
	}
	mcp3422Wait(mcp3422ConvTime(d.bits) * 11 / 10)
	return d.waitReady()
}

// ReadRaw runs a one-shot conversion on channel 0 (CH1) or 1 (CH2); values
// outside 0–1 are clamped. It returns the signed code
// (−2^(N−1) … 2^(N−1) − 1, N = resolution bits).
func (d *MCP3422Minimal) ReadRaw(channel int) (int32, error) { return d.convert(channel) }

// Lsb returns the size of one code before the gain, 2·2.048/2^bits in volts.
func (d *MCP3422Minimal) Lsb() float32 {
	return 2 * mcp3422VRef / float32(uint32(1)<<uint(d.bits))
}

// ReadVoltage runs a one-shot conversion on a channel and returns the
// differential voltage CHn+ − CHn− in volts (raw·lsb/gain).
func (d *MCP3422Minimal) ReadVoltage(channel int) (float32, error) {
	raw, err := d.convert(channel)
	if err != nil {
		return 0, err
	}
	return float32(raw) * d.Lsb() / float32(d.gain), nil
}

// ReadAll converts both channels, one conversion each, and returns [CH1, CH2].
func (d *MCP3422Minimal) ReadAll() ([2]float32, error) {
	var out [2]float32
	for ch := 0; ch < 2; ch++ {
		v, err := d.ReadVoltage(ch)
		if err != nil {
			return out, err
		}
		out[ch] = v
	}
	return out, nil
}

// MCP3422Full extends MCP3422Minimal with resolution, gain and channel
// selection, continuous conversion, status reads, scans and general calls. The
// driver tracks resolution, gain, channel and mode.
type MCP3422Full struct {
	*MCP3422Minimal
}

// NewMCP3422Full creates a MCP3422Full with the same initialization as
// NewMCP3422Minimal.
func NewMCP3422Full(conn connection.Connection) (*MCP3422Full, error) {
	m, err := NewMCP3422Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &MCP3422Full{MCP3422Minimal: m}, nil
}

// apply rewrites the configuration in continuous mode and marks the next
// sample for discarding.
func (d *MCP3422Full) apply() error {
	if d.continuous {
		if err := d.conn.Write([]byte{d.config(false)}); err != nil {
			return err
		}
		d.discard = true
	}
	return nil
}

// SetResolution selects 12 (240 SPS), 14 (60 SPS), 16 (15 SPS) or 18 (3.75 SPS)
// bit; any other value returns ErrMCP3422Resolution.
func (d *MCP3422Full) SetResolution(bits int) error {
	if bits != 12 && bits != 14 && bits != 16 && bits != 18 {
		return ErrMCP3422Resolution
	}
	d.bits = bits
	return d.apply()
}

// GetResolution returns the selected resolution: 12, 14, 16 or 18.
func (d *MCP3422Full) GetResolution() int { return d.bits }

// SetGain selects the PGA gain 1, 2, 4 or 8; any other value returns
// ErrMCP3422Gain.
func (d *MCP3422Full) SetGain(gain int) error {
	if gain != 1 && gain != 2 && gain != 4 && gain != 8 {
		return ErrMCP3422Gain
	}
	d.gain = gain
	return d.apply()
}

// GetGain returns the selected PGA gain.
func (d *MCP3422Full) GetGain() int { return d.gain }

// SetChannel selects the channel used by continuous conversion (0 or 1).
func (d *MCP3422Full) SetChannel(channel int) error {
	if channel < 1 {
		d.channel = 0
	} else {
		d.channel = 1
	}
	return d.apply()
}

// StartContinuous switches to continuous conversion at the current rate and
// gain; a channel of 0 or 1 selects it first, a negative value keeps the
// current channel.
func (d *MCP3422Full) StartContinuous(channel int) error {
	if channel >= 0 {
		if channel < 1 {
			d.channel = 0
		} else {
			d.channel = 1
		}
	}
	d.continuous = true
	d.discard = true
	return d.conn.Write([]byte{d.config(false)})
}

// StopContinuous returns to one-shot mode / standby (writes O/C = 0, RDY = 0).
func (d *MCP3422Full) StopContinuous() error {
	d.continuous = false
	d.discard = false
	return d.conn.Write([]byte{d.config(false)})
}

// IsContinuous reports whether the driver is in continuous mode.
func (d *MCP3422Full) IsContinuous() bool { return d.continuous }

// ReadContinuousRaw waits for a fresh result in continuous mode and returns the
// signed code. The first sample after a configuration change is discarded.
func (d *MCP3422Full) ReadContinuousRaw() (int32, error) {
	if !d.continuous {
		return 0, ErrMCP3422NotContinuous
	}
	if d.discard {
		if _, err := d.waitReady(); err != nil {
			return 0, err
		}
		d.discard = false
	}
	return d.waitReady()
}

// ReadContinuous is the voltage form of ReadContinuousRaw, in volts.
func (d *MCP3422Full) ReadContinuous() (float32, error) {
	raw, err := d.ReadContinuousRaw()
	if err != nil {
		return 0, err
	}
	return float32(raw) * d.Lsb() / float32(d.gain), nil
}

// ReadConfig reads the configuration byte (the last byte of a read); bit 7 is
// the live RDY flag.
func (d *MCP3422Full) ReadConfig() (uint8, error) {
	data, err := d.conn.Read(d.nbytes() + 1)
	if err != nil {
		return 0, err
	}
	return data[d.nbytes()], nil
}

// IsReady reports whether a fresh result is available (RDY = 0).
func (d *MCP3422Full) IsReady() (bool, error) {
	cfg, err := d.ReadConfig()
	if err != nil {
		return false, err
	}
	return cfg&mcp3422CfgRDY == 0, nil
}

// ReadScan runs one one-shot conversion per entry, in order, and returns the
// voltages in volts.
func (d *MCP3422Full) ReadScan(channels []int) ([]float32, error) {
	out := make([]float32, 0, len(channels))
	for _, ch := range channels {
		v, err := d.ReadVoltage(ch)
		if err != nil {
			return out, err
		}
		out = append(out, v)
	}
	return out, nil
}

// GeneralCallReset sends the general call reset (0x00 0x06): power-on defaults
// on the chip (continuous, 12 bit, gain x1, CH1); the driver state follows.
// Needs a Connection that transmits to the general-call address.
func (d *MCP3422Full) GeneralCallReset() error {
	if err := d.conn.Write([]byte{0x00, 0x06}); err != nil {
		return err
	}
	d.bits, d.gain, d.channel = 12, 1, 0
	d.continuous, d.discard = true, true
	return nil
}

// GeneralCallConvert sends the general call conversion (0x00 0x08): a one-shot
// conversion on every device on the bus; read the result with ReadPendingRaw.
func (d *MCP3422Full) GeneralCallConvert() error {
	if err := d.conn.Write([]byte{0x00, 0x08}); err != nil {
		return err
	}
	d.continuous = false
	return nil
}

// ReadPendingRaw waits for RDY = 0 and returns the code of a conversion started
// elsewhere.
func (d *MCP3422Full) ReadPendingRaw() (int32, error) { return d.waitReady() }

// FullScale returns the full-scale differential input range at the current
// gain, 2.048/gain in volts.
func (d *MCP3422Full) FullScale() float32 { return mcp3422VRef / float32(d.gain) }
