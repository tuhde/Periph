package adcdac

import (
	"github.com/tuhde/Periph/go/periph/connection"
)

// CAT5171 instruction byte bits. The chip has no register map: a write is
// [instruction, data], where the instruction byte carries the midscale-reset
// flag (RS) and the shutdown flag (SD); a read is a bare one-byte read
// returning the wiper register. The driver therefore uses the plain
// Connection Write / Read calls.
const (
	cat5171InstrRS  uint8 = 0x40
	cat5171InstrSD  uint8 = 0x20
	cat5171Midscale uint8 = 0x80
)

// CAT5171I2CAddress is the 7-bit I²C address with AD0 = GND.
const CAT5171I2CAddress uint8 = 0x2C

// CAT5171I2CAddressAD0High is the 7-bit I²C address with AD0 = VDD.
const CAT5171I2CAddressAD0High uint8 = 0x2D

// Default resistance model: 100 kΩ part, typical wiper resistance at 5 V.
const (
	CAT5171DefaultRAB    float32 = 100000.0
	CAT5171DefaultRWiper float32 = 50.0
)

func cat5171ClampCode(code int) uint8 {
	if code < 0 {
		return 0
	}
	if code > 255 {
		return 255
	}
	return uint8(code)
}

// CAT5171Minimal sets and reads back the wiper position (or wiper-to-B
// resistance) of a CAT5171 256-position digital potentiometer (ON
// Semiconductor). Resistances follow R_WB = D/256·RAB + RW. The wiper is
// volatile (power-up midscale).
type CAT5171Minimal struct {
	conn connection.Connection
	rAB  float32
	rW   float32
	code uint8
	sd   bool
}

// NewCAT5171Minimal creates a CAT5171Minimal. rAB is the end-to-end
// resistance in Ω (default part: CAT5171DefaultRAB) and rWiper the wiper
// resistance RW in Ω (CAT5171DefaultRWiper). It performs no I²C traffic — the
// chip has no identity register and needs no initialisation.
func NewCAT5171Minimal(conn connection.Connection, rAB, rWiper float32) (*CAT5171Minimal, error) {
	return &CAT5171Minimal{conn: conn, rAB: rAB, rW: rWiper, code: cat5171Midscale}, nil
}

func (d *CAT5171Minimal) writeInstr(instr, code uint8) error {
	return d.conn.Write([]byte{instr, code})
}

func (d *CAT5171Minimal) writeCode(code int) error {
	c := cat5171ClampCode(code)
	instr := uint8(0)
	if d.sd {
		instr = cat5171InstrSD
	}
	if err := d.writeInstr(instr, c); err != nil {
		return err
	}
	d.code = c
	return nil
}

func (d *CAT5171Minimal) readCode() (uint8, error) {
	buf, err := d.conn.Read(1)
	if err != nil {
		return 0, err
	}
	d.code = buf[0]
	return buf[0], nil
}

// SetWiper sets the wiper position; code is clamped to 0-255.
func (d *CAT5171Minimal) SetWiper(code int) error {
	return d.writeCode(code)
}

// GetWiper reads back the wiper position (0-255) with a single read
// transaction.
func (d *CAT5171Minimal) GetWiper() (uint8, error) {
	return d.readCode()
}

// SetResistance sets the W–B resistance (Ω) to the nearest reachable code.
func (d *CAT5171Minimal) SetResistance(ohms float32) error {
	return d.writeCode(int((ohms-d.rW)/d.rAB*256.0 + 0.5))
}

// GetResistance returns the W–B resistance (Ω) for the current wiper code:
// D/256·RAB + RW.
func (d *CAT5171Minimal) GetResistance() (float32, error) {
	c, err := d.readCode()
	if err != nil {
		return 0, err
	}
	return float32(c)/256.0*d.rAB + d.rW, nil
}

// CAT5171Full extends CAT5171Minimal with ratio and W–A helpers,
// increment/decrement, midscale, shutdown and reset. The shutdown state is
// write-only on the chip, so the driver tracks it.
type CAT5171Full struct {
	CAT5171Minimal
}

// NewCAT5171Full creates a CAT5171Full; same parameters as NewCAT5171Minimal.
func NewCAT5171Full(conn connection.Connection, rAB, rWiper float32) (*CAT5171Full, error) {
	m, _ := NewCAT5171Minimal(conn, rAB, rWiper)
	return &CAT5171Full{CAT5171Minimal: *m}, nil
}

// SetRatio sets the wiper by divider ratio V(W−B)/V(A−B) (0.0-1.0): the code
// is round(ratio × 256) clamped to 255.
func (d *CAT5171Full) SetRatio(ratio float32) error {
	return d.writeCode(int(ratio*256.0 + 0.5))
}

// GetRatio returns the divider ratio code/256.
func (d *CAT5171Full) GetRatio() (float32, error) {
	c, err := d.readCode()
	if err != nil {
		return 0, err
	}
	return float32(c) / 256.0, nil
}

// SetResistanceWA sets the W–A resistance (rheostat mode, B open) to the
// nearest code.
func (d *CAT5171Full) SetResistanceWA(ohms float32) error {
	steps := int((ohms-d.rW)/d.rAB*256.0 + 0.5)
	return d.writeCode(256 - steps)
}

// GetResistanceWA returns the W–A resistance (Ω): (256 − D)/256·RAB + RW.
func (d *CAT5171Full) GetResistanceWA() (float32, error) {
	c, err := d.readCode()
	if err != nil {
		return 0, err
	}
	return float32(256-int(c))/256.0*d.rAB + d.rW, nil
}

// Increment moves the wiper by steps (negative to decrement): it reads the
// code, adds, clamps to 0-255, writes and returns the new code.
func (d *CAT5171Full) Increment(steps int) (uint8, error) {
	c, err := d.readCode()
	if err != nil {
		return 0, err
	}
	n := cat5171ClampCode(int(c) + steps)
	if err := d.writeCode(int(n)); err != nil {
		return 0, err
	}
	return n, nil
}

// Midscale moves the wiper to midscale (0x80) with the RS instruction bit; SD
// is kept if the device is shut down.
func (d *CAT5171Full) Midscale() error {
	instr := cat5171InstrRS
	if d.sd {
		instr |= cat5171InstrSD
	}
	if err := d.writeInstr(instr, cat5171Midscale); err != nil {
		return err
	}
	d.code = cat5171Midscale
	return nil
}

// Shutdown shuts the device down: terminal A opens and the wiper shorts to B;
// the wiper register is kept (the tracked code is rewritten with SD = 1).
func (d *CAT5171Full) Shutdown() error {
	d.sd = true
	return d.writeInstr(cat5171InstrSD, d.code)
}

// Resume leaves shutdown (SD = 0), rewriting the tracked code.
func (d *CAT5171Full) Resume() error {
	d.sd = false
	return d.writeInstr(0, d.code)
}

// IsShutdown returns the driver-tracked shutdown state (the chip cannot
// report it).
func (d *CAT5171Full) IsShutdown() bool { return d.sd }

// Reset performs a midscale reset (RS = 1, SD = 0): the wiper goes to 0x80
// and the shutdown state is cleared. The chip has no reset pin.
func (d *CAT5171Full) Reset() error {
	d.sd = false
	if err := d.writeInstr(cat5171InstrRS, cat5171Midscale); err != nil {
		return err
	}
	d.code = cat5171Midscale
	return nil
}

// SetRAB changes the resistance parameters used by conversions (no I²C
// traffic). rWiper < 0 leaves RW unchanged.
func (d *CAT5171Full) SetRAB(rAB, rWiper float32) {
	d.rAB = rAB
	if rWiper >= 0 {
		d.rW = rWiper
	}
}
