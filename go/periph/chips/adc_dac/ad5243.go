package adcdac

import (
	"errors"

	"github.com/tuhde/Periph/go/periph/connection"
)

// AD5243 instruction byte bits. The chip has no register map: a write is
// [instruction, data], where the instruction byte carries the channel select
// (A0) and the shutdown flag (SD); it is passed as the "register" of the
// RegisterConnection.
const (
	ad5243InstrA0  uint8 = 0x80
	ad5243InstrSD  uint8 = 0x40
	ad5243Midscale uint8 = 0x80
)

// AD5243I2CAddress is the fixed 7-bit I²C address (no address pins).
const AD5243I2CAddress uint8 = 0x2F

// Default resistance model: 10 kΩ part, typical wiper resistance at 5 V.
const (
	AD5243DefaultRAB    float32 = 10000.0
	AD5243DefaultRWiper float32 = 160.0
)

// ErrAD5243InvalidChannel is returned when a channel is not 0 or 1.
var ErrAD5243InvalidChannel = errors.New("AD5243: channel must be 0 or 1")

func ad5243ClampCode(code int) uint8 {
	if code < 0 {
		return 0
	}
	if code > 255 {
		return 255
	}
	return uint8(code)
}

// AD5243Minimal sets and reads back the wiper position (or wiper-to-B
// resistance) of either channel of an AD5243 dual 256-position digital
// potentiometer (Analog Devices). Resistances follow
// R_WB = D/256·RAB + 2·RW. Wiper values are volatile (power-up midscale).
type AD5243Minimal struct {
	conn connection.RegisterConnection
	rAB  float32
	rW   float32
	code [2]uint8
	sd   [2]bool
}

// NewAD5243Minimal creates an AD5243Minimal. rAB is the end-to-end resistance
// in Ω (default part: AD5243DefaultRAB) and rWiper the wiper resistance RW in
// Ω (AD5243DefaultRWiper). It performs no I²C traffic — the chip has no
// identity register and needs no initialisation.
func NewAD5243Minimal(conn connection.RegisterConnection, rAB, rWiper float32) (*AD5243Minimal, error) {
	return &AD5243Minimal{
		conn: conn, rAB: rAB, rW: rWiper,
		code: [2]uint8{ad5243Midscale, ad5243Midscale},
	}, nil
}

func (d *AD5243Minimal) instr(ch uint8) (uint8, error) {
	if ch > 1 {
		return 0, ErrAD5243InvalidChannel
	}
	v := uint8(0)
	if ch == 1 {
		v |= ad5243InstrA0
	}
	if d.sd[ch] {
		v |= ad5243InstrSD
	}
	return v, nil
}

func (d *AD5243Minimal) writeCode(ch uint8, code int) error {
	instr, err := d.instr(ch)
	if err != nil {
		return err
	}
	c := ad5243ClampCode(code)
	if err := d.conn.WriteReg(uint32(instr), []byte{c}); err != nil {
		return err
	}
	d.code[ch] = c
	return nil
}

func (d *AD5243Minimal) readCode(ch uint8) (uint8, error) {
	instr, err := d.instr(ch)
	if err != nil {
		return 0, err
	}
	b, err := d.conn.ReadReg(uint32(instr), 1)
	if err != nil {
		return 0, err
	}
	d.code[ch] = b[0]
	return b[0], nil
}

// SetWiper sets the wiper position of channel (0 or 1); code is clamped to
// 0-255.
func (d *AD5243Minimal) SetWiper(channel uint8, code int) error {
	return d.writeCode(channel, code)
}

// GetWiper reads back the wiper position (0-255): the channel's instruction
// byte is sent alone, then one byte is read.
func (d *AD5243Minimal) GetWiper(channel uint8) (uint8, error) {
	return d.readCode(channel)
}

// SetResistance sets the W–B resistance (Ω) to the nearest reachable code.
func (d *AD5243Minimal) SetResistance(channel uint8, ohms float32) error {
	return d.writeCode(channel, int((ohms-2*d.rW)/d.rAB*256+0.5))
}

// GetResistance returns the W–B resistance (Ω) for the current wiper code:
// D/256·RAB + 2·RW.
func (d *AD5243Minimal) GetResistance(channel uint8) (float32, error) {
	c, err := d.readCode(channel)
	if err != nil {
		return 0, err
	}
	return float32(c)/256*d.rAB + 2*d.rW, nil
}

// AD5243Full extends AD5243Minimal with ratio and W–A helpers,
// increment/decrement, per-channel shutdown and reset. The shutdown state is
// write-only on the chip, so the driver tracks it.
type AD5243Full struct {
	AD5243Minimal
}

// NewAD5243Full creates an AD5243Full; same parameters as NewAD5243Minimal.
func NewAD5243Full(conn connection.RegisterConnection, rAB, rWiper float32) (*AD5243Full, error) {
	m, err := NewAD5243Minimal(conn, rAB, rWiper)
	if err != nil {
		return nil, err
	}
	return &AD5243Full{AD5243Minimal: *m}, nil
}

// SetBoth writes both wipers (two transactions).
func (d *AD5243Full) SetBoth(code1, code2 int) error {
	if err := d.writeCode(0, code1); err != nil {
		return err
	}
	return d.writeCode(1, code2)
}

// SetRatio sets the wiper by divider ratio V(W−B)/V(A−B) (0.0-1.0): the code
// is round(ratio × 256) clamped to 255.
func (d *AD5243Full) SetRatio(channel uint8, ratio float32) error {
	return d.writeCode(channel, int(ratio*256+0.5))
}

// GetRatio returns the divider ratio code/256.
func (d *AD5243Full) GetRatio(channel uint8) (float32, error) {
	c, err := d.readCode(channel)
	if err != nil {
		return 0, err
	}
	return float32(c) / 256, nil
}

// SetResistanceWA sets the W–A resistance (rheostat mode, B open) to the
// nearest code.
func (d *AD5243Full) SetResistanceWA(channel uint8, ohms float32) error {
	steps := int((ohms-2*d.rW)/d.rAB*256 + 0.5)
	return d.writeCode(channel, 256-steps)
}

// GetResistanceWA returns the W–A resistance (Ω): (256 − D)/256·RAB + 2·RW.
func (d *AD5243Full) GetResistanceWA(channel uint8) (float32, error) {
	c, err := d.readCode(channel)
	if err != nil {
		return 0, err
	}
	return float32(256-int(c))/256*d.rAB + 2*d.rW, nil
}

// Increment moves the wiper by steps (negative to decrement): it reads the
// code, adds, clamps to 0-255, writes and returns the new code.
func (d *AD5243Full) Increment(channel uint8, steps int) (uint8, error) {
	c, err := d.readCode(channel)
	if err != nil {
		return 0, err
	}
	n := ad5243ClampCode(int(c) + steps)
	if err := d.writeCode(channel, int(n)); err != nil {
		return 0, err
	}
	return n, nil
}

// Shutdown shuts a channel down: terminal A opens and the wiper shorts to B;
// the RDAC contents are kept (the tracked code is rewritten with SD = 1).
func (d *AD5243Full) Shutdown(channel uint8) error {
	if channel > 1 {
		return ErrAD5243InvalidChannel
	}
	d.sd[channel] = true
	return d.writeCode(channel, int(d.code[channel]))
}

// Resume leaves shutdown (SD = 0), rewriting the tracked code.
func (d *AD5243Full) Resume(channel uint8) error {
	if channel > 1 {
		return ErrAD5243InvalidChannel
	}
	d.sd[channel] = false
	return d.writeCode(channel, int(d.code[channel]))
}

// IsShutdown returns the driver-tracked shutdown state (the chip cannot
// report it).
func (d *AD5243Full) IsShutdown(channel uint8) (bool, error) {
	if channel > 1 {
		return false, ErrAD5243InvalidChannel
	}
	return d.sd[channel], nil
}

// Reset restores the power-up preset: both wipers 0x80, SD = 0. The chip has
// no reset pin or command.
func (d *AD5243Full) Reset() error {
	d.sd = [2]bool{}
	if err := d.writeCode(0, int(ad5243Midscale)); err != nil {
		return err
	}
	return d.writeCode(1, int(ad5243Midscale))
}

// SetRAB changes the resistance parameters used by conversions (no I²C
// traffic). rWiper < 0 leaves RW unchanged.
func (d *AD5243Full) SetRAB(rAB, rWiper float32) {
	d.rAB = rAB
	if rWiper >= 0 {
		d.rW = rWiper
	}
}
