package adcdac

import (
	"fmt"
	"math"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ad5248SD is the shutdown bit (SD) of the instruction byte; bit 7 (A0) selects the channel.
const ad5248SD uint8 = 0x40

// AD5248Minimal is the dual 256-position I²C digital rheostat driver — minimal interface.
//
// The chip has no register map: the instruction byte (channel select A0 in bit 7,
// shutdown SD in bit 6) plays the role of the register address, so the driver calls
// ReadReg/WriteReg with the instruction byte. Defaults: RAB = 10 kΩ, RW = 160 Ω; both
// channels assumed active. Construction performs no I²C traffic.
type AD5248Minimal struct {
	connection connection.RegisterConnection
	rAB        float64
	rWiper     float64
	shutdown   [2]bool
}

// NewAD5248Minimal creates an AD5248Minimal; no I²C traffic.
//
// connection must be bound to an address in 0x2C–0x2F; rAB is the end-to-end
// resistance in ohms (2500, 10000, 50000 or 100000) and rWiper the wiper
// resistance in ohms (typically 160).
func NewAD5248Minimal(t connection.RegisterConnection, rAB, rWiper float64) *AD5248Minimal {
	return &AD5248Minimal{connection: t, rAB: rAB, rWiper: rWiper}
}

func ad5248CheckChannel(channel int) error {
	if channel != 0 && channel != 1 {
		return fmt.Errorf("ad5248: channel must be 0 or 1, got %d", channel)
	}
	return nil
}

func (d *AD5248Minimal) instruction(channel int) uint32 {
	instr := uint8(channel) << 7
	if d.shutdown[channel] {
		instr |= ad5248SD
	}
	return uint32(instr)
}

// SetWiper sets the wiper position of a channel (0 or 1); code is clamped to 0–255.
// On a shut-down channel the RDAC is updated and the channel stays in shutdown.
func (d *AD5248Minimal) SetWiper(channel, code int) error {
	if err := ad5248CheckChannel(channel); err != nil {
		return err
	}
	if code < 0 {
		code = 0
	}
	if code > 255 {
		code = 255
	}
	return d.connection.WriteReg(d.instruction(channel), []byte{byte(code)})
}

// GetWiper reads the wiper position (0–255) of a channel by sending the
// instruction byte alone, then reading one byte.
func (d *AD5248Minimal) GetWiper(channel int) (uint8, error) {
	if err := ad5248CheckChannel(channel); err != nil {
		return 0, err
	}
	buf, err := d.connection.ReadReg(d.instruction(channel), 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

// SetResistance sets the W–B resistance (ohms) to the nearest reachable value.
func (d *AD5248Minimal) SetResistance(channel int, ohms float64) error {
	if err := ad5248CheckChannel(channel); err != nil {
		return err
	}
	code := math.Round((ohms - 2*d.rWiper) / d.rAB * 256)
	if code < 0 {
		code = 0
	}
	if code > 255 {
		code = 255
	}
	return d.SetWiper(channel, int(code))
}

// GetResistance returns the W–B resistance in ohms: code/256*RAB + 2*RW.
func (d *AD5248Minimal) GetResistance(channel int) (float64, error) {
	code, err := d.GetWiper(channel)
	if err != nil {
		return 0, err
	}
	return float64(code)/256*d.rAB + 2*d.rWiper, nil
}

// AD5248Full extends AD5248Minimal with ratio, increment, shutdown and reset.
type AD5248Full struct {
	AD5248Minimal
}

// NewAD5248Full creates an AD5248Full; same arguments as NewAD5248Minimal.
func NewAD5248Full(t connection.RegisterConnection, rAB, rWiper float64) *AD5248Full {
	return &AD5248Full{AD5248Minimal: *NewAD5248Minimal(t, rAB, rWiper)}
}

// SetBoth writes both wipers (two transactions).
func (d *AD5248Full) SetBoth(code1, code2 int) error {
	if err := d.SetWiper(0, code1); err != nil {
		return err
	}
	return d.SetWiper(1, code2)
}

// SetRatio sets the wiper to round(ratio×256) clamped to 255 (ratio 0.0–1.0).
// Only meaningful if terminal A is wired externally; the AD5248 does not pin it
// out, so prefer SetResistance.
func (d *AD5248Full) SetRatio(channel int, ratio float64) error {
	ratio = math.Max(0, math.Min(1, ratio))
	code := int(math.Round(ratio * 256))
	if code > 255 {
		code = 255
	}
	return d.SetWiper(channel, code)
}

// GetRatio returns the wiper position as a ratio: code/256.
func (d *AD5248Full) GetRatio(channel int) (float64, error) {
	code, err := d.GetWiper(channel)
	if err != nil {
		return 0, err
	}
	return float64(code) / 256, nil
}

// Increment moves the wiper by steps (negative to decrement) and returns the new
// code, clamped to 0–255.
func (d *AD5248Full) Increment(channel, steps int) (uint8, error) {
	cur, err := d.GetWiper(channel)
	if err != nil {
		return 0, err
	}
	code := int(cur) + steps
	if code < 0 {
		code = 0
	}
	if code > 255 {
		code = 255
	}
	if err := d.SetWiper(channel, code); err != nil {
		return 0, err
	}
	return uint8(code), nil
}

// Shutdown puts a channel in shutdown (terminal A open, wiper shorted to B); the
// RDAC is kept.
func (d *AD5248Full) Shutdown(channel int) error {
	code, err := d.GetWiper(channel)
	if err != nil {
		return err
	}
	d.shutdown[channel] = true
	return d.connection.WriteReg(d.instruction(channel), []byte{code})
}

// Resume leaves shutdown; the RDAC contents are restored to the wiper.
func (d *AD5248Full) Resume(channel int) error {
	code, err := d.GetWiper(channel)
	if err != nil {
		return err
	}
	d.shutdown[channel] = false
	return d.connection.WriteReg(d.instruction(channel), []byte{code})
}

// IsShutdown returns the driver-tracked shutdown state (the chip cannot be read back).
func (d *AD5248Full) IsShutdown(channel int) (bool, error) {
	if err := ad5248CheckChannel(channel); err != nil {
		return false, err
	}
	return d.shutdown[channel], nil
}

// Reset writes midscale (0x80) to both channels with SD = 0 (power-up preset).
func (d *AD5248Full) Reset() error {
	d.shutdown = [2]bool{}
	if err := d.SetWiper(0, 0x80); err != nil {
		return err
	}
	return d.SetWiper(1, 0x80)
}

// SetRab changes the resistance model used by the conversions (no I²C traffic).
// A negative rWiper keeps the current wiper resistance.
func (d *AD5248Full) SetRab(rAB, rWiper float64) {
	d.rAB = rAB
	if rWiper >= 0 {
		d.rWiper = rWiper
	}
}
