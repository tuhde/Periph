package adcdac

import (
	"errors"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// AD5252 instruction bytes. The chip has no register map: the instruction
// byte works as a one-byte register pointer (RDAC1 = channel 0, RDAC3 =
// channel 1), so the driver uses a RegisterConnection with a 1-byte register
// width. Quick commands are single-byte writes.
const (
	ad5252EEMEMFlag uint8 = 0x20

	ad5252QNop        uint8 = 0x80
	ad5252QRestore    uint8 = 0x88
	ad5252QStore      uint8 = 0x90
	ad5252QHalve      uint8 = 0x98
	ad5252QDecStep    uint8 = 0xA8
	ad5252QInc6dB     uint8 = 0xC0
	ad5252QIncStep    uint8 = 0xD0
	ad5252QDec6dBAll  uint8 = 0xA0
	ad5252QDecStepAll uint8 = 0xB0
	ad5252QReset      uint8 = 0xB8
	ad5252QInc6dBAll  uint8 = 0xC8
	ad5252QIncStepAll uint8 = 0xD8
)

var (
	ad5252Rdac   = [2]uint8{0x01, 0x03}
	ad5252TolInt = [2]uint8{0x3A, 0x3E}
)

// AD5252I2CAddress is the default 7-bit I²C address (AD1 = AD0 = GND);
// 0x2C–0x2F are possible.
const AD5252I2CAddress uint8 = 0x2C

// Default resistance model: 10 kΩ part, typical wiper resistance.
const (
	AD5252DefaultRAB    float32 = 10000.0
	AD5252DefaultRWiper float32 = 75.0
)

// AD5252Both selects both channels in the quick-command methods.
const AD5252Both uint8 = 0xFF

// ErrAD5252InvalidChannel is returned when a channel is not 0 or 1 (or a user
// EEMEM index is outside 4–15).
var ErrAD5252InvalidChannel = errors.New("AD5252: channel must be 0 or 1 (index 4-15)")

// ErrAD5252Timeout is returned when the chip does not acknowledge again within
// 100 ms after an EEMEM write.
var ErrAD5252Timeout = errors.New("AD5252: EEMEM write did not finish")

func ad5252ClampCode(code int) uint8 {
	if code < 0 {
		return 0
	}
	if code > 255 {
		return 255
	}
	return uint8(code)
}

// AD5252Minimal sets and reads back the wiper position (or wiper-to-B
// resistance) of either channel of an AD5252 dual 256-position digital
// potentiometer with EEMEM (Analog Devices). Resistances follow
// R_WB = D/256·RAB + RW. Wipers power up from EEMEM (midscale until a value
// is stored).
type AD5252Minimal struct {
	conn connection.RegisterConnection
	rAB  float32
	rW   float32
}

// NewAD5252Minimal creates an AD5252Minimal. rAB is the end-to-end resistance
// in Ω (default part: AD5252DefaultRAB) and rWiper the wiper resistance RW in
// Ω (AD5252DefaultRWiper). It performs no I²C traffic — the chip has no
// identity register and needs no initialisation.
func NewAD5252Minimal(conn connection.RegisterConnection, rAB, rWiper float32) (*AD5252Minimal, error) {
	return &AD5252Minimal{conn: conn, rAB: rAB, rW: rWiper}, nil
}

func (d *AD5252Minimal) writeCode(ch uint8, code int) error {
	if ch > 1 {
		return ErrAD5252InvalidChannel
	}
	return d.conn.WriteReg(uint32(ad5252Rdac[ch]), []byte{ad5252ClampCode(code)})
}

func (d *AD5252Minimal) readCode(ch uint8) (uint8, error) {
	if ch > 1 {
		return 0, ErrAD5252InvalidChannel
	}
	b, err := d.conn.ReadReg(uint32(ad5252Rdac[ch]), 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// SetWiper sets the wiper position of channel (0 or 1); code is clamped to 0–255.
func (d *AD5252Minimal) SetWiper(channel uint8, code int) error {
	return d.writeCode(channel, code)
}

// GetWiper reads back the wiper position (0–255): the RDAC instruction byte
// alone, then one byte.
func (d *AD5252Minimal) GetWiper(channel uint8) (uint8, error) {
	return d.readCode(channel)
}

// SetResistance sets the W–B resistance (Ω) to the nearest reachable code.
func (d *AD5252Minimal) SetResistance(channel uint8, ohms float32) error {
	return d.writeCode(channel, int((ohms-d.rW)/d.rAB*256.0+0.5))
}

// GetResistance returns the W–B resistance (Ω) for the current wiper code:
// D/256·RAB + RW.
func (d *AD5252Minimal) GetResistance(channel uint8) (float32, error) {
	c, err := d.readCode(channel)
	if err != nil {
		return 0, err
	}
	return float32(c)/256.0*d.rAB + d.rW, nil
}

// AD5252Full extends AD5252Minimal with ratio and W–A helpers, quick commands
// (±1 step, ±6 dB), EEMEM store/restore/reset, user EEMEM and the factory RAB
// tolerance. Channel arguments of the quick commands accept AD5252Both.
type AD5252Full struct {
	AD5252Minimal
}

// NewAD5252Full creates an AD5252Full; the parameters match NewAD5252Minimal.
func NewAD5252Full(conn connection.RegisterConnection, rAB, rWiper float32) (*AD5252Full, error) {
	return &AD5252Full{AD5252Minimal{conn: conn, rAB: rAB, rW: rWiper}}, nil
}

func (d *AD5252Full) quickByte(b uint8) error {
	return d.conn.Write([]byte{b})
}

func (d *AD5252Full) quick(base, baseAll, channel uint8) error {
	if channel == AD5252Both {
		return d.quickByte(baseAll)
	}
	if channel > 1 {
		return ErrAD5252InvalidChannel
	}
	return d.quickByte(base | ad5252Rdac[channel])
}

// waitReady polls with the NOP quick command until the chip acknowledges
// (it NACKs during the ≈ 26 ms EEMEM write cycle), up to 100 ms.
func (d *AD5252Full) waitReady() error {
	for i := 0; i < 51; i++ {
		if d.quickByte(ad5252QNop) == nil {
			return nil
		}
		time.Sleep(2 * time.Millisecond)
	}
	return ErrAD5252Timeout
}

// SetBoth writes both wipers in one consecutive-write transaction.
func (d *AD5252Full) SetBoth(code0, code1 int) error {
	return d.conn.WriteReg(uint32(ad5252Rdac[0]), []byte{ad5252ClampCode(code0), ad5252ClampCode(code1)})
}

// SetRatio sets the wiper by divider ratio V(W−B)/V(A−B) (0.0–1.0): the code
// is round(ratio × 256) clamped to 255.
func (d *AD5252Full) SetRatio(channel uint8, ratio float32) error {
	return d.writeCode(channel, int(ratio*256.0+0.5))
}

// GetRatio returns the divider ratio code/256.
func (d *AD5252Full) GetRatio(channel uint8) (float32, error) {
	c, err := d.readCode(channel)
	if err != nil {
		return 0, err
	}
	return float32(c) / 256.0, nil
}

// SetResistanceWA sets the W–A resistance (rheostat mode, B open) to the
// nearest code.
func (d *AD5252Full) SetResistanceWA(channel uint8, ohms float32) error {
	steps := int((ohms-d.rW)/d.rAB*256.0 + 0.5)
	return d.writeCode(channel, 256-steps)
}

// GetResistanceWA returns the W–A resistance (Ω): (256 − D)/256·RAB + RW.
func (d *AD5252Full) GetResistanceWA(channel uint8) (float32, error) {
	c, err := d.readCode(channel)
	if err != nil {
		return 0, err
	}
	return float32(256-int(c))/256.0*d.rAB + d.rW, nil
}

// Increment increments the wiper by one step (quick command); channel 0, 1 or AD5252Both.
func (d *AD5252Full) Increment(channel uint8) error {
	return d.quick(ad5252QIncStep, ad5252QIncStepAll, channel)
}

// Decrement decrements the wiper by one step (quick command); channel 0, 1 or AD5252Both.
func (d *AD5252Full) Decrement(channel uint8) error {
	return d.quick(ad5252QDecStep, ad5252QDecStepAll, channel)
}

// Double increases the level by 6 dB — the code doubles (quick command).
func (d *AD5252Full) Double(channel uint8) error {
	return d.quick(ad5252QInc6dB, ad5252QInc6dBAll, channel)
}

// Halve decreases the level by 6 dB — the code halves (quick command).
func (d *AD5252Full) Halve(channel uint8) error {
	return d.quick(ad5252QHalve, ad5252QDec6dBAll, channel)
}

// Store stores the wiper in EEMEM (restored at power-on) and waits for the
// ≈ 26 ms write cycle by acknowledge polling; channel 0, 1 or AD5252Both.
func (d *AD5252Full) Store(channel uint8) error {
	if channel == AD5252Both {
		if err := d.quick(ad5252QStore, 0, 0); err != nil {
			return err
		}
		if err := d.waitReady(); err != nil {
			return err
		}
		channel = 1
	}
	if err := d.quick(ad5252QStore, 0, channel); err != nil {
		return err
	}
	return d.waitReady()
}

// Restore restores the wiper from EEMEM, then sends a NOP; channel 0, 1 or AD5252Both.
func (d *AD5252Full) Restore(channel uint8) error {
	if channel == AD5252Both {
		if err := d.quick(ad5252QRestore, 0, 0); err != nil {
			return err
		}
		channel = 1
	}
	if err := d.quick(ad5252QRestore, 0, channel); err != nil {
		return err
	}
	return d.Nop()
}

// Reset sends the reset quick command (0xB8): both wipers are restored from
// EEMEM; it then waits for the chip to acknowledge again.
func (d *AD5252Full) Reset() error {
	if err := d.quickByte(ad5252QReset); err != nil {
		return err
	}
	return d.waitReady()
}

// ReadEEMEM reads a user EEMEM byte (index 4–15).
func (d *AD5252Full) ReadEEMEM(index uint8) (uint8, error) {
	if index < 4 || index > 15 {
		return 0, ErrAD5252InvalidChannel
	}
	b, err := d.conn.ReadReg(uint32(ad5252EEMEMFlag|index), 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// WriteEEMEM writes a user EEMEM byte (index 4–15) and waits for the write
// cycle by acknowledge polling.
func (d *AD5252Full) WriteEEMEM(index, value uint8) error {
	if index < 4 || index > 15 {
		return ErrAD5252InvalidChannel
	}
	if err := d.conn.WriteReg(uint32(ad5252EEMEMFlag|index), []byte{value}); err != nil {
		return err
	}
	return d.waitReady()
}

// ReadTolerance returns the factory-stored RAB tolerance of channel in percent
// (sign-magnitude integer byte plus decimal byte in units of 1/256 %).
func (d *AD5252Full) ReadTolerance(channel uint8) (float32, error) {
	if channel > 1 {
		return 0, ErrAD5252InvalidChannel
	}
	raw, err := d.conn.ReadReg(uint32(ad5252TolInt[channel]), 2)
	if err != nil {
		return 0, err
	}
	v := float32(raw[0]&0x7F) + float32(raw[1])/256.0
	if raw[0]&0x80 != 0 {
		v = -v
	}
	return v, nil
}

// RABActual returns the actual RAB of channel (Ω): rAB × (1 + tolerance/100).
func (d *AD5252Full) RABActual(channel uint8) (float32, error) {
	tol, err := d.ReadTolerance(channel)
	if err != nil {
		return 0, err
	}
	return d.rAB * (1.0 + tol/100.0), nil
}

// Nop sends the NOP quick command (0x80), returning the chip to idle.
func (d *AD5252Full) Nop() error {
	return d.quickByte(ad5252QNop)
}

// SetRAB changes the resistance parameters used by conversions (no I²C
// traffic); a negative rWiper leaves RW unchanged.
func (d *AD5252Full) SetRAB(rAB, rWiper float32) {
	d.rAB = rAB
	if rWiper >= 0 {
		d.rW = rWiper
	}
}
