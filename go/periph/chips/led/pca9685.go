package led

import (
	"errors"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// PCA9685 register map constants.
const (
	pca9685RegMode1      uint32 = 0x00
	pca9685RegMode2      uint32 = 0x01
	pca9685RegSubadr1    uint32 = 0x02
	pca9685RegAllcallAdr uint32 = 0x05
	pca9685RegLed0       uint32 = 0x06
	pca9685RegAllLed     uint32 = 0xFA
	pca9685RegPreScale   uint32 = 0xFE

	pca9685Restart byte = 0x80
	pca9685ExtClk  byte = 0x40
	pca9685AI      byte = 0x20
	pca9685Sleep   byte = 0x10
)

// Pca9685Channels is the number of PWM channels.
const Pca9685Channels = 16

// Output state while OE = 1 (MODE2.OUTNE) for SetOutputMode.
const (
	// Pca9685OffLow drives LEDn low while OE = 1.
	Pca9685OffLow uint8 = 0
	// Pca9685OffHighOrHighZ drives LEDn high (totem-pole) or high-Z (open-drain) while OE = 1.
	Pca9685OffHighOrHighZ uint8 = 1
	// Pca9685OffHighZ puts LEDn in high-Z while OE = 1.
	Pca9685OffHighZ uint8 = 2
)

// ErrPca9685Range is returned when an argument is outside its documented range.
var ErrPca9685Range = errors.New("pca9685: argument out of range")

func pca9685DutyBytes(duty float64) ([]byte, error) {
	if !(duty >= 0 && duty <= 1) {
		return nil, ErrPca9685Range
	}
	off := int(math.Floor(duty*4096 + 0.5))
	if off <= 0 {
		return []byte{0, 0, 0, 0x10}, nil
	}
	if off >= 4096 {
		return []byte{0, 0x10, 0, 0}, nil
	}
	return []byte{0, 0, byte(off & 0xFF), byte(off >> 8)}, nil
}

func pca9685PwmBytes(on, off uint16) ([]byte, error) {
	if on > 4095 || off > 4095 || on == off {
		return nil, ErrPca9685Range
	}
	return []byte{byte(on & 0xFF), byte(on >> 8), byte(off & 0xFF), byte(off >> 8)}, nil
}

// Pca9685Minimal is the PCA9685 16-channel, 12-bit PWM LED/servo controller — minimal interface.
//
// Dims LEDs / drives servos on 16 channels with one call each. The I²C address is
// 0x40–0x6F or 0x71–0x77 (A0–A5 pins; 0x70 is the All Call address).
//
// Defaults baked in at construction: the chip is put to sleep, PRE_SCALE is set
// for 1 kHz (5), then it is woken with auto-increment on and ALLCALL off and the
// 1 ms oscillator start is waited for. Outputs are totem-pole, not inverted, and
// change on the I²C STOP; all channels keep their power-on state (off).
type Pca9685Minimal struct {
	conn connection.RegisterConnection
}

// NewPca9685Minimal creates the driver and runs the initialization sequence.
//
// c must be a configured I²C register connection bound to the chip's 7-bit address.
func NewPca9685Minimal(c connection.RegisterConnection) (*Pca9685Minimal, error) {
	d := &Pca9685Minimal{conn: c}
	if err := c.WriteReg(pca9685RegMode1, []byte{pca9685AI | pca9685Sleep}); err != nil {
		return nil, err
	}
	if err := c.WriteReg(pca9685RegPreScale, []byte{5}); err != nil {
		return nil, err
	}
	if err := c.WriteReg(pca9685RegMode1, []byte{pca9685AI}); err != nil {
		return nil, err
	}
	time.Sleep(time.Millisecond)
	return d, nil
}

// SetDuty sets the duty cycle of one channel (ON = 0, OFF = round(duty × 4096)).
//
// 0.0 writes full OFF and 1.0 writes full ON, so no glitch pulses occur.
// channel is 0–15, duty is 0.0–1.0; otherwise ErrPca9685Range is returned.
func (d *Pca9685Minimal) SetDuty(channel uint8, duty float64) error {
	if channel > 15 {
		return ErrPca9685Range
	}
	b, err := pca9685DutyBytes(duty)
	if err != nil {
		return err
	}
	return d.conn.WriteReg(pca9685RegLed0+4*uint32(channel), b)
}

// Pca9685Full is the PCA9685 full interface — phase-shifted PWM, frequency, servo
// pulses, modes and addresses.
type Pca9685Full struct {
	Pca9685Minimal
}

// NewPca9685Full creates the full driver and runs the initialization sequence.
func NewPca9685Full(c connection.RegisterConnection) (*Pca9685Full, error) {
	m, err := NewPca9685Minimal(c)
	if err != nil {
		return nil, err
	}
	return &Pca9685Full{Pca9685Minimal: *m}, nil
}

func (d *Pca9685Full) mode1() (byte, error) {
	b, err := d.conn.ReadReg(pca9685RegMode1, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *Pca9685Full) writeBytes(reg uint32, data ...byte) error {
	return d.conn.WriteReg(reg, data)
}

// SetPwm writes the ON and OFF counts (0–4095, must differ) of one channel in one
// transaction (phase-shifted PWM).
func (d *Pca9685Full) SetPwm(channel uint8, on, off uint16) error {
	if channel > 15 {
		return ErrPca9685Range
	}
	b, err := pca9685PwmBytes(on, off)
	if err != nil {
		return err
	}
	return d.conn.WriteReg(pca9685RegLed0+4*uint32(channel), b)
}

// GetPwm reads the ON and OFF counts of one channel (without the full-ON/OFF flags).
func (d *Pca9685Full) GetPwm(channel uint8) (on, off uint16, err error) {
	if channel > 15 {
		return 0, 0, ErrPca9685Range
	}
	b, err := d.conn.ReadReg(pca9685RegLed0+4*uint32(channel), 4)
	if err != nil {
		return 0, 0, err
	}
	return uint16(b[1]&0x0F)<<8 | uint16(b[0]), uint16(b[3]&0x0F)<<8 | uint16(b[2]), nil
}

// SetFullOn forces a channel permanently high (full-ON bit set, full-OFF bit cleared).
func (d *Pca9685Full) SetFullOn(channel uint8) error {
	if channel > 15 {
		return ErrPca9685Range
	}
	return d.writeBytes(pca9685RegLed0+4*uint32(channel), 0, 0x10, 0, 0)
}

// SetFullOff forces a channel permanently low (full-OFF bit set; it wins over full ON).
func (d *Pca9685Full) SetFullOff(channel uint8) error {
	if channel > 15 {
		return ErrPca9685Range
	}
	return d.writeBytes(pca9685RegLed0+4*uint32(channel), 0, 0, 0, 0x10)
}

// SetAllDuty sets the same duty cycle (0.0–1.0) on all 16 channels (ALL_LED registers).
func (d *Pca9685Full) SetAllDuty(duty float64) error {
	b, err := pca9685DutyBytes(duty)
	if err != nil {
		return err
	}
	return d.conn.WriteReg(pca9685RegAllLed, b)
}

// SetAllPwm sets the same ON and OFF counts (0–4095, must differ) on all 16 channels.
func (d *Pca9685Full) SetAllPwm(on, off uint16) error {
	b, err := pca9685PwmBytes(on, off)
	if err != nil {
		return err
	}
	return d.conn.WriteReg(pca9685RegAllLed, b)
}

// SetFrequency sets the PWM frequency: PRE_SCALE = round(oscHz / (4096 × hz)) − 1,
// clamped to 3–255. The chip is put to sleep, PRE_SCALE is written, the chip is
// woken, 500 µs are waited and running channels are restarted. oscHz is 25e6
// for the internal oscillator (or the EXTCLK frequency).
func (d *Pca9685Full) SetFrequency(hz, oscHz float64) error {
	if !(hz > 0) || !(oscHz > 0) {
		return ErrPca9685Range
	}
	p := int(math.Floor(oscHz/(4096*hz)+0.5)) - 1
	if p < 3 {
		p = 3
	}
	if p > 255 {
		p = 255
	}
	old, err := d.mode1()
	if err != nil {
		return err
	}
	awake := old &^ (pca9685Restart | pca9685Sleep)
	if err := d.writeBytes(pca9685RegMode1, (old&0x7F)|pca9685Sleep); err != nil {
		return err
	}
	if err := d.writeBytes(pca9685RegPreScale, byte(p)); err != nil {
		return err
	}
	if err := d.writeBytes(pca9685RegMode1, awake); err != nil {
		return err
	}
	time.Sleep(time.Millisecond)
	if old&pca9685Restart != 0 {
		return d.writeBytes(pca9685RegMode1, awake|pca9685Restart)
	}
	return nil
}

// GetFrequency reads PRE_SCALE and returns oscHz / (4096 × (PRE_SCALE + 1)) in Hz.
func (d *Pca9685Full) GetFrequency(oscHz float64) (float64, error) {
	b, err := d.conn.ReadReg(pca9685RegPreScale, 1)
	if err != nil {
		return 0, err
	}
	return oscHz / (4096 * float64(int(b[0])+1)), nil
}

// SetPulseWidth sets a pulse width in seconds (servo/ESC mode):
// OFF = round(seconds × frequency × 4096), using the PRE_SCALE value currently
// programmed (25 MHz oscillator).
func (d *Pca9685Full) SetPulseWidth(channel uint8, seconds float64) error {
	if channel > 15 || !(seconds >= 0) {
		return ErrPca9685Range
	}
	hz, err := d.GetFrequency(25000000)
	if err != nil {
		return err
	}
	return d.SetDuty(channel, math.Min(1, seconds*hz))
}

// Sleep enters low-power mode (MODE1.SLEEP = 1, oscillator off, outputs stop).
func (d *Pca9685Full) Sleep() error {
	old, err := d.mode1()
	if err != nil {
		return err
	}
	return d.writeBytes(pca9685RegMode1, (old&0x7F)|pca9685Sleep)
}

// Wake leaves low-power mode, waits 500 µs and restarts channels that were running.
func (d *Pca9685Full) Wake() error {
	old, err := d.mode1()
	if err != nil {
		return err
	}
	if err := d.writeBytes(pca9685RegMode1, old&^(pca9685Restart|pca9685Sleep)); err != nil {
		return err
	}
	time.Sleep(time.Millisecond)
	if old&pca9685Restart != 0 {
		return d.writeBytes(pca9685RegMode1, (old&^pca9685Sleep)|pca9685Restart)
	}
	return nil
}

// Restart runs the datasheet restart sequence (read MODE1, clear SLEEP, wait 500 µs, write RESTART).
func (d *Pca9685Full) Restart() error {
	old, err := d.mode1()
	if err != nil {
		return err
	}
	if err := d.writeBytes(pca9685RegMode1, old&^(pca9685Restart|pca9685Sleep)); err != nil {
		return err
	}
	time.Sleep(time.Millisecond)
	return d.writeBytes(pca9685RegMode1, (old&^pca9685Sleep)|pca9685Restart)
}

// SetOutputMode configures MODE2: open-drain or totem-pole outputs, inversion
// and the output state while OE = 1 (Pca9685OffLow, Pca9685OffHighOrHighZ or Pca9685OffHighZ).
// The update-on-ACK bit is preserved.
func (d *Pca9685Full) SetOutputMode(openDrain, invert bool, offState uint8) error {
	if offState > 2 {
		return ErrPca9685Range
	}
	m := offState
	if invert {
		m |= 0x10
	}
	if !openDrain {
		m |= 0x04
	}
	b, err := d.conn.ReadReg(pca9685RegMode2, 1)
	if err != nil {
		return err
	}
	return d.writeBytes(pca9685RegMode2, (b[0]&0x08)|m)
}

// SetUpdateOnAck selects when outputs change: on ACK (true, MODE2.OCH = 1) or on STOP (false, default).
func (d *Pca9685Full) SetUpdateOnAck(enabled bool) error {
	b, err := d.conn.ReadReg(pca9685RegMode2, 1)
	if err != nil {
		return err
	}
	v := b[0] &^ 0x08
	if enabled {
		v |= 0x08
	}
	return d.writeBytes(pca9685RegMode2, v)
}

// UseExternalClock switches to the EXTCLK pin as clock. The switch is sticky until a
// power cycle / software reset; afterwards pass the external clock frequency as oscHz
// to SetFrequency / GetFrequency.
func (d *Pca9685Full) UseExternalClock() error {
	old, err := d.mode1()
	if err != nil {
		return err
	}
	sleeping := (old & 0x7F) | pca9685Sleep
	for _, v := range []byte{sleeping, sleeping | pca9685ExtClk, (sleeping | pca9685ExtClk) &^ pca9685Sleep} {
		if err := d.writeBytes(pca9685RegMode1, v); err != nil {
			return err
		}
	}
	time.Sleep(time.Millisecond)
	return nil
}

// SetAllcall enables or disables the LED All Call address and sets its 7-bit value (default 0x70).
func (d *Pca9685Full) SetAllcall(enabled bool, address uint8) error {
	if address > 0x7F {
		return ErrPca9685Range
	}
	if err := d.writeBytes(pca9685RegAllcallAdr, address<<1); err != nil {
		return err
	}
	old, err := d.mode1()
	if err != nil {
		return err
	}
	v := old & 0x7E
	if enabled {
		v |= 1
	}
	return d.writeBytes(pca9685RegMode1, v)
}

// SetSubaddress programs and enables or disables one of the three LED Sub Call
// addresses (index 1–3, 7-bit address).
func (d *Pca9685Full) SetSubaddress(index, address uint8, enabled bool) error {
	if index < 1 || index > 3 || address > 0x7F {
		return ErrPca9685Range
	}
	if err := d.writeBytes(pca9685RegSubadr1+uint32(index)-1, address<<1); err != nil {
		return err
	}
	old, err := d.mode1()
	if err != nil {
		return err
	}
	mask := byte(1) << (4 - index)
	v := old & 0x7F
	if enabled {
		v |= mask
	} else {
		v &^= mask
	}
	return d.writeBytes(pca9685RegMode1, v)
}

// Reset returns the chip registers to their power-on values by writing the documented
// defaults (MODE1 = 0x11, MODE2 = 0x04, PRE_SCALE = 0x1E, outputs off). It does not issue
// the general-call software reset; see Pca9685SoftwareReset.
func (d *Pca9685Full) Reset() error {
	steps := []struct {
		reg  uint32
		data []byte
	}{
		{pca9685RegMode1, []byte{0x10}},
		{pca9685RegPreScale, []byte{0x1E}},
		{pca9685RegMode2, []byte{0x04}},
		{pca9685RegAllLed, []byte{0, 0, 0, 0x10}},
		{pca9685RegSubadr1, []byte{0xE2, 0xE4, 0xE8, 0xE0}},
		{pca9685RegMode1, []byte{0x11}},
	}
	for _, s := range steps {
		if err := d.conn.WriteReg(s.reg, s.data); err != nil {
			return err
		}
	}
	return nil
}

// Pca9685SoftwareReset sends the Software Reset call (0x06) over a connection bound
// to the general call address 0x00. It resets every PCA9685 on the bus; wait at least
// 500 µs before using the chips again.
func Pca9685SoftwareReset(generalCall connection.Connection) error {
	return generalCall.Write([]byte{0x06})
}

// ReadMode reads the raw mode registers (MODE1, MODE2).
func (d *Pca9685Full) ReadMode() (mode1, mode2 uint8, err error) {
	b, err := d.conn.ReadReg(pca9685RegMode1, 2)
	if err != nil {
		return 0, 0, err
	}
	return b[0], b[1], nil
}
