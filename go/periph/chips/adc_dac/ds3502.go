package adcdac

import (
	"errors"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// DS3502 registers. Two registers exist: 0x00 is the Wiper Register (WR, SRAM)
// and, depending on the MODE bit of CR, also the Initial Value Register (IVR,
// EEPROM); reads always return WR. 0x02 is the Control Register whose bit 7
// selects MODE (1 = writes to 0x00 only change WR).
const (
	ds3502RegWR  uint8 = 0x00
	ds3502RegCR  uint8 = 0x02
	ds3502CRSRAM uint8 = 0x80 // MODE = 1: SRAM only
	ds3502CREEPR uint8 = 0x00 // MODE = 0: writes also program the IVR

	ds3502MaxCode  = 127
	ds3502RTotal   = 10000.0
	ds3502PowerUp  = 3 * time.Millisecond // tD: power-up recall of the IVR
	ds3502PollStep = time.Millisecond
)

// DS3502I2CAddress is the 7-bit I²C address with A1 = A0 = GND (0x28 … 0x2B).
const DS3502I2CAddress uint8 = 0x28

// ErrDS3502Timeout is returned when the EEPROM write does not finish in time.
var ErrDS3502Timeout = errors.New("ds3502: EEPROM write did not finish")

// ErrDS3502Terminal is returned for a terminal other than 'L' or 'H'.
var ErrDS3502Terminal = errors.New("ds3502: terminal must be 'L' or 'H'")

func ds3502ClampCode(code int) uint8 {
	if code < 0 {
		return 0
	}
	if code > ds3502MaxCode {
		return ds3502MaxCode
	}
	return uint8(code)
}

func ds3502Round(x float32) int {
	if x >= 0 {
		return int(x + 0.5)
	}
	return int(x - 0.5)
}

// DS3502Minimal sets and reads back the wiper position (or the unloaded
// output voltage) of a DS3502 7-bit nonvolatile high-voltage digital
// potentiometer (Maxim). The constructor runs the initialization sequence
// (CR = 0x80, i.e. MODE = 1, with one retry after 3 ms; then WR is read), so
// routine wiper writes never wear the EEPROM.
type DS3502Minimal struct {
	conn  connection.RegisterConnection
	vHigh float32
	vLow  float32
	code  uint8
}

// NewDS3502Minimal creates a DS3502Minimal and runs the initialization
// sequence. vHigh / vLow are the voltages applied to RH / RL in volts
// (defaults 1.0 / 0.0 make the voltage functions ratios).
func NewDS3502Minimal(conn connection.RegisterConnection, vHigh, vLow float32) (*DS3502Minimal, error) {
	d := &DS3502Minimal{conn: conn, vHigh: vHigh, vLow: vLow}
	if err := d.writeReg(ds3502RegCR, ds3502CRSRAM); err != nil {
		time.Sleep(ds3502PowerUp) // still in the power-up recall: retry once
		if err := d.writeReg(ds3502RegCR, ds3502CRSRAM); err != nil {
			return nil, err
		}
	}
	if _, err := d.readCode(); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *DS3502Minimal) readReg(reg uint8) (uint8, error) {
	buf, err := d.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (d *DS3502Minimal) writeReg(reg, value uint8) error {
	return d.conn.WriteReg(uint32(reg), []byte{value})
}

func (d *DS3502Minimal) writeCode(code int) error {
	c := ds3502ClampCode(code)
	if err := d.writeReg(ds3502RegWR, c); err != nil {
		return err
	}
	d.code = c
	return nil
}

func (d *DS3502Minimal) readCode() (uint8, error) {
	v, err := d.readReg(ds3502RegWR)
	if err != nil {
		return 0, err
	}
	d.code = v & ds3502MaxCode
	return d.code, nil
}

// SetWiper sets the wiper position (volatile, SRAM only); code is clamped to
// 0–127 (0 = at RL, 127 = at RH).
func (d *DS3502Minimal) SetWiper(code int) error { return d.writeCode(code) }

// GetWiper reads back the wiper position (WR), 0–127.
func (d *DS3502Minimal) GetWiper() (uint8, error) { return d.readCode() }

// SetVoltage sets the unloaded RW output voltage to the nearest reachable
// code (clamped to the range between vLow and vHigh).
func (d *DS3502Minimal) SetVoltage(volts float32) error {
	span := d.vHigh - d.vLow
	if span == 0 {
		return d.writeCode(0)
	}
	return d.writeCode(ds3502Round((volts - d.vLow) / span * ds3502MaxCode))
}

// GetVoltage reads the unloaded RW voltage for the current wiper code,
// vLow + (vHigh − vLow)·code/127.
func (d *DS3502Minimal) GetVoltage() (float32, error) {
	code, err := d.readCode()
	if err != nil {
		return 0, err
	}
	return d.vLow + (d.vHigh-d.vLow)*float32(code)/ds3502MaxCode, nil
}

// DS3502Full extends DS3502Minimal with ratio / resistance helpers,
// increment, EEPROM storage of the power-up value, MODE control and
// acknowledge polling.
type DS3502Full struct {
	*DS3502Minimal
}

// NewDS3502Full creates a DS3502Full with the same initialization as
// NewDS3502Minimal.
func NewDS3502Full(conn connection.RegisterConnection, vHigh, vLow float32) (*DS3502Full, error) {
	m, err := NewDS3502Minimal(conn, vHigh, vLow)
	if err != nil {
		return nil, err
	}
	return &DS3502Full{DS3502Minimal: m}, nil
}

// SetRatio sets the wiper by divider ratio V(RW−RL)/V(RH−RL); the code is
// round(ratio·127), clamped.
func (d *DS3502Full) SetRatio(ratio float32) error {
	return d.writeCode(ds3502Round(ratio * ds3502MaxCode))
}

// GetRatio reads the divider ratio, code/127.
func (d *DS3502Full) GetRatio() (float32, error) {
	code, err := d.readCode()
	if err != nil {
		return 0, err
	}
	return float32(code) / ds3502MaxCode, nil
}

// GetResistance returns the nominal wiper-to-RL ('L') or wiper-to-RH ('H')
// resistance for the current code (RTOTAL = 10 kΩ ±20 %, so absolute values
// are approximate; the wiper resistance is not included).
func (d *DS3502Full) GetResistance(terminal byte) (float32, error) {
	if terminal != 'L' && terminal != 'H' {
		return 0, ErrDS3502Terminal
	}
	code, err := d.readCode()
	if err != nil {
		return 0, err
	}
	if terminal == 'L' {
		return float32(code) / ds3502MaxCode * ds3502RTotal, nil
	}
	return float32(ds3502MaxCode-int(code)) / ds3502MaxCode * ds3502RTotal, nil
}

// Increment moves the wiper relative to its current position (SRAM only) and
// returns the new code, clamped to 0–127.
func (d *DS3502Full) Increment(steps int) (uint8, error) {
	cur, err := d.readCode()
	if err != nil {
		return 0, err
	}
	code := ds3502ClampCode(int(cur) + steps)
	if err := d.writeCode(int(code)); err != nil {
		return 0, err
	}
	return code, nil
}

// StoreWiper writes the Initial Value Register (EEPROM power-up position).
// It sets MODE = 0, writes WR / IVR, waits for the EEPROM write (acknowledge
// polling, ≤ 20 ms) and restores MODE = 1, also on error. The wiper moves to
// code (a negative code stores the current wiper position). Each call counts
// toward the EEPROM endurance (50 000 writes at 70 °C, 200 000 at 25 °C).
func (d *DS3502Full) StoreWiper(code int) error {
	if code < 0 {
		cur, err := d.readCode()
		if err != nil {
			return err
		}
		code = int(cur)
	}
	c := ds3502ClampCode(code)
	if err := d.writeReg(ds3502RegCR, ds3502CREEPR); err != nil {
		return err
	}
	err := d.writeReg(ds3502RegWR, c)
	if err == nil {
		d.code = c
		err = d.WaitReady(30)
	}
	if restoreErr := d.writeReg(ds3502RegCR, ds3502CRSRAM); err == nil {
		err = restoreErr
	}
	return err
}

// SetMode selects what a write to the wiper register updates (advanced;
// StoreWiper handles it). eeprom = true is MODE = 0 (writes also program the
// IVR), false is MODE = 1 (SRAM only).
func (d *DS3502Full) SetMode(eeprom bool) error {
	if eeprom {
		return d.writeReg(ds3502RegCR, ds3502CREEPR)
	}
	return d.writeReg(ds3502RegCR, ds3502CRSRAM)
}

// GetMode reads the MODE bit of the Control Register; true means MODE = 0
// (writes reach the EEPROM).
func (d *DS3502Full) GetMode() (bool, error) {
	cr, err := d.readReg(ds3502RegCR)
	if err != nil {
		return false, err
	}
	return cr&ds3502CRSRAM == 0, nil
}

// WaitReady acknowledge-polls: it repeats a read of CR until the chip ACKs
// (it NACKs while an EEPROM write is in progress), waiting 1 ms between
// attempts; it returns ErrDS3502Timeout after timeoutMs milliseconds.
func (d *DS3502Full) WaitReady(timeoutMs int) error {
	for i := 0; i <= timeoutMs; i++ {
		if _, err := d.readReg(ds3502RegCR); err == nil {
			return nil
		}
		time.Sleep(ds3502PollStep)
	}
	return ErrDS3502Timeout
}

// SetEndpoints changes the RH / RL voltages used by the conversions (no I²C
// traffic).
func (d *DS3502Full) SetEndpoints(vHigh, vLow float32) {
	d.vHigh = vHigh
	d.vLow = vLow
}
