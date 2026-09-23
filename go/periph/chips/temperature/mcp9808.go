// Package temperature contains drivers for standalone temperature sensor
// chips (MCP9808, TMP117, etc.).
package temperature

import (
	"errors"
	"fmt"
	"math"
	"sync"

	"github.com/tuhde/Periph/go/periph/connection"
)

// MCP9808 register pointers.
const (
	mcp9808RegConfig     uint8 = 0x01
	mcp9808RegTUpper     uint8 = 0x02
	mcp9808RegTLower     uint8 = 0x03
	mcp9808RegTCrit      uint8 = 0x04
	mcp9808RegTA         uint8 = 0x05
	mcp9808RegMfrID      uint8 = 0x06
	mcp9808RegDeviceID   uint8 = 0x07
	mcp9808RegResolution uint8 = 0x08
)

// CONFIG (0x01) bits.
const (
	mcp9808CfgTHystShift uint16 = 9
	mcp9808CfgTHystMask  uint16 = 0x0600
	mcp9808CfgShdn       uint16 = 0x0100
	mcp9808CfgCritLock   uint16 = 0x0080
	mcp9808CfgWinLock    uint16 = 0x0040
	mcp9808CfgIntClear   uint16 = 0x0020
	mcp9808CfgAlertStat  uint16 = 0x0010
	mcp9808CfgAlertCnt   uint16 = 0x0008
	mcp9808CfgAlertSel   uint16 = 0x0004
	mcp9808CfgAlertPol   uint16 = 0x0002
	mcp9808CfgAlertMod   uint16 = 0x0001
	mcp9808CfgLocks      uint16 = 0x00C0
	// Writable bits: all but the unimplemented 15:11, the read-only
	// ALERT_STAT, and the self-clearing INT_CLEAR (set only on purpose).
	mcp9808CfgWriteMask uint16 = 0x07CF
)

// MCP9808I2CAddress is the default 7-bit I²C address (A0 = A1 = A2 = GND).
// The A0/A1/A2 straps select any address in 0x18-0x1F.
const MCP9808I2CAddress uint8 = 0x18

// MCP9808ManufacturerID and MCP9808DeviceID are the identity values checked
// at construction (DEVICE_ID is the upper byte of DEVICE_ID_REV).
const (
	MCP9808ManufacturerID uint16 = 0x0054
	MCP9808DeviceID       uint8  = 0x04
)

// Interrupt sources — the three live boundary-status bits of TA (15:13).
const (
	MCP9808SourceLower    uint8 = 0x01 // TA < TLOWER
	MCP9808SourceUpper    uint8 = 0x02 // TA > TUPPER
	MCP9808SourceCritical uint8 = 0x04 // TA ≥ TCRIT
)

// MCP9808AlertMode selects which boundaries drive the Alert output (ALERT_SEL).
type MCP9808AlertMode uint8

// MCP9808AlertMode values.
const (
	MCP9808AlertAll          MCP9808AlertMode = 0 // TUPPER, TLOWER and TCRIT
	MCP9808AlertCriticalOnly MCP9808AlertMode = 1 // TCRIT only
)

// MCP9808AlertOutput selects the Alert output behavior (ALERT_MOD).
type MCP9808AlertOutput uint8

// MCP9808AlertOutput values.
const (
	MCP9808AlertComparator MCP9808AlertOutput = 0 // follows the boundary state
	MCP9808AlertInterrupt  MCP9808AlertOutput = 1 // latches until ClearInterrupt
)

// MCP9808AlertPolarity selects the Alert output polarity (ALERT_POL).
type MCP9808AlertPolarity uint8

// MCP9808AlertPolarity values.
const (
	MCP9808AlertActiveLow  MCP9808AlertPolarity = 0 // needs an external pull-up (POR default)
	MCP9808AlertActiveHigh MCP9808AlertPolarity = 1
)

// Errors returned by the MCP9808 driver.
var (
	// ErrMCP9808NotFound is returned by the constructors when
	// MANUFACTURER_ID or DEVICE_ID does not match.
	ErrMCP9808NotFound = errors.New("MCP9808: not found (MANUFACTURER_ID/DEVICE_ID mismatch)")
	// ErrMCP9808InvalidResolution is returned by SetResolution for a step
	// other than 0.5, 0.25, 0.125 or 0.0625 °C.
	ErrMCP9808InvalidResolution = errors.New("MCP9808: resolution must be 0.5, 0.25, 0.125 or 0.0625")
	// ErrMCP9808InvalidHysteresis is returned by SetHysteresis for a value
	// other than 0, 1.5, 3.0 or 6.0 °C.
	ErrMCP9808InvalidHysteresis = errors.New("MCP9808: hysteresis must be 0, 1.5, 3.0 or 6.0")
	// ErrMCP9808Locked is returned by ConfigureAlert while CRIT_LOCK or
	// WIN_LOCK freezes the Alert configuration until power-on reset.
	ErrMCP9808Locked = errors.New("MCP9808: Alert configuration is locked until power-on reset")
)

var (
	mcp9808Resolutions = [4]float32{0.5, 0.25, 0.125, 0.0625}
	mcp9808Hystereses  = [4]float32{0, 1.5, 3.0, 6.0}
)

func mcp9808DecodeTemperature(raw16 uint16) float32 {
	raw := int16(raw16 & 0x1FFF)
	if raw&0x1000 != 0 {
		raw -= 0x2000
	}
	return float32(raw) / 16
}

func mcp9808DecodeLimit(raw16 uint16) float32 {
	value := int16((raw16 >> 2) & 0x3FF)
	if raw16&0x1000 != 0 {
		value -= 1024
	}
	return float32(value) / 4
}

// mcp9808EncodeLimit rounds half away from zero to 0.25 °C steps, clamps to
// the 11-bit two's-complement range (-256.0 to 255.75 °C) and places the
// value in bits 12:2.
func mcp9808EncodeLimit(celsius float32) uint16 {
	var quarters int32
	if celsius >= 0 {
		quarters = int32(celsius*4 + 0.5)
	} else {
		quarters = -int32(-celsius*4 + 0.5)
	}
	if quarters < -1024 {
		quarters = -1024
	}
	if quarters > 1023 {
		quarters = 1023
	}
	return uint16(quarters&0x7FF) << 2
}

func mcp9808IndexOf(table [4]float32, value float32) int {
	for i, s := range table {
		if math.Abs(float64(value-s)) < 1e-4 {
			return i
		}
	}
	return -1
}

// MCP9808Minimal is the minimal MCP9808 interface: read the ambient
// temperature.
//
// MCP9808 ±0.5 °C maximum accuracy digital temperature sensor (Microchip):
// a band-gap sensor with a delta-sigma ADC, read over I²C. Registers are
// 16-bit, big-endian, addressed through a non-incrementing Register Pointer.
type MCP9808Minimal struct {
	conn connection.Connection
}

// NewMCP9808Minimal creates an MCP9808Minimal and confirms the chip's
// identity (MANUFACTURER_ID 0x0054, DEVICE_ID byte 0x04; the revision byte
// is ignored), returning ErrMCP9808NotFound on a mismatch. No register
// writes are made — the POR default (continuous conversion at 0.0625 °C,
// Alert output disabled) already serves the primary use case.
func NewMCP9808Minimal(conn connection.Connection) (*MCP9808Minimal, error) {
	d := &MCP9808Minimal{conn: conn}
	mfr, err := d.readReg(mcp9808RegMfrID)
	if err != nil {
		return nil, fmt.Errorf("MCP9808: device not responding: %w", err)
	}
	dev, err := d.readReg(mcp9808RegDeviceID)
	if err != nil {
		return nil, fmt.Errorf("MCP9808: device not responding: %w", err)
	}
	if mfr != MCP9808ManufacturerID || uint8(dev>>8) != MCP9808DeviceID {
		return nil, ErrMCP9808NotFound
	}
	return d, nil
}

func (d *MCP9808Minimal) readReg(reg uint8) (uint16, error) {
	b, err := d.conn.WriteRead([]byte{reg}, 2)
	if err != nil {
		return 0, err
	}
	return uint16(b[0])<<8 | uint16(b[1]), nil
}

func (d *MCP9808Minimal) writeReg(reg uint8, value uint16) error {
	return d.conn.Write([]byte{reg, byte(value >> 8), byte(value)})
}

// ReadTemperature reads the ambient temperature in °C. It masks off TA's
// three boundary-status bits and decodes the 13-bit two's-complement value
// (0.0625 °C per LSB).
func (d *MCP9808Minimal) ReadTemperature() (float32, error) {
	raw, err := d.readReg(mcp9808RegTA)
	if err != nil {
		return 0, err
	}
	return mcp9808DecodeTemperature(raw), nil
}

// MCP9808Full extends MCP9808Minimal with resolution control, Shutdown mode,
// the TUPPER/TLOWER/TCRIT boundaries, hysteresis, the one-way register
// locks, and the Level-2 Alert/interrupt API.
type MCP9808Full struct {
	*MCP9808Minimal

	mu          sync.Mutex
	callback    func(uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
	lastStatus  uint8
}

// NewMCP9808Full creates an MCP9808Full; same identity check as
// NewMCP9808Minimal.
func NewMCP9808Full(conn connection.Connection) (*MCP9808Full, error) {
	m, err := NewMCP9808Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &MCP9808Full{MCP9808Minimal: m}, nil
}

func (d *MCP9808Full) readConfig() (uint16, error) {
	c, err := d.readReg(mcp9808RegConfig)
	return c & mcp9808CfgWriteMask, err
}

func (d *MCP9808Full) writeConfig(value uint16) error {
	return d.writeReg(mcp9808RegConfig, value&mcp9808CfgWriteMask)
}

func (d *MCP9808Full) updateConfig(set, clear uint16) error {
	c, err := d.readConfig()
	if err != nil {
		return err
	}
	return d.writeConfig((c &^ clear) | set)
}

func (d *MCP9808Full) configBit(bit uint16) (bool, error) {
	c, err := d.readReg(mcp9808RegConfig)
	return c&bit != 0, err
}

// SetResolution sets the measurement resolution: 0.5, 0.25, 0.125 or
// 0.0625 °C. Finer steps take longer to convert (30, 65, 130, 250 ms
// typical). Any other value returns ErrMCP9808InvalidResolution without a
// bus transaction.
func (d *MCP9808Full) SetResolution(celsius float32) error {
	code := mcp9808IndexOf(mcp9808Resolutions, celsius)
	if code < 0 {
		return ErrMCP9808InvalidResolution
	}
	return d.conn.Write([]byte{mcp9808RegResolution, byte(code)})
}

// GetResolution reads the measurement resolution step in °C.
func (d *MCP9808Full) GetResolution() (float32, error) {
	b, err := d.conn.WriteRead([]byte{mcp9808RegResolution}, 1)
	if err != nil {
		return 0, err
	}
	return mcp9808Resolutions[b[0]&0x03], nil
}

// Shutdown enters Shutdown (low-power) mode; TA holds its last value. It is
// a no-op while either lock bit is set (the chip ignores SHDN=1 then).
func (d *MCP9808Full) Shutdown() error {
	c, err := d.readConfig()
	if err != nil {
		return err
	}
	if c&mcp9808CfgLocks != 0 {
		return nil
	}
	return d.writeConfig(c | mcp9808CfgShdn)
}

// Wake leaves Shutdown mode and resumes continuous conversion.
func (d *MCP9808Full) Wake() error {
	return d.updateConfig(0, mcp9808CfgShdn)
}

// IsShutdown reports whether the sensor is in Shutdown mode (SHDN set).
func (d *MCP9808Full) IsShutdown() (bool, error) {
	return d.configBit(mcp9808CfgShdn)
}

func (d *MCP9808Full) getLimit(reg uint8) (float32, error) {
	raw, err := d.readReg(reg)
	if err != nil {
		return 0, err
	}
	return mcp9808DecodeLimit(raw), nil
}

// GetUpperLimit reads the TUPPER boundary in °C (0.25 °C steps).
func (d *MCP9808Full) GetUpperLimit() (float32, error) { return d.getLimit(mcp9808RegTUpper) }

// SetUpperLimit writes the TUPPER boundary in °C, rounded to the nearest
// 0.25 °C and clamped to -256.0..255.75 °C (ignored by the chip while
// WIN_LOCK is set).
func (d *MCP9808Full) SetUpperLimit(celsius float32) error {
	return d.writeReg(mcp9808RegTUpper, mcp9808EncodeLimit(celsius))
}

// GetLowerLimit reads the TLOWER boundary in °C (0.25 °C steps).
func (d *MCP9808Full) GetLowerLimit() (float32, error) { return d.getLimit(mcp9808RegTLower) }

// SetLowerLimit writes the TLOWER boundary in °C, rounded to the nearest
// 0.25 °C and clamped to -256.0..255.75 °C (ignored by the chip while
// WIN_LOCK is set).
func (d *MCP9808Full) SetLowerLimit(celsius float32) error {
	return d.writeReg(mcp9808RegTLower, mcp9808EncodeLimit(celsius))
}

// GetCriticalLimit reads the TCRIT boundary in °C (0.25 °C steps).
func (d *MCP9808Full) GetCriticalLimit() (float32, error) { return d.getLimit(mcp9808RegTCrit) }

// SetCriticalLimit writes the TCRIT boundary in °C, rounded to the nearest
// 0.25 °C and clamped to -256.0..255.75 °C (ignored by the chip while
// CRIT_LOCK is set).
func (d *MCP9808Full) SetCriticalLimit(celsius float32) error {
	return d.writeReg(mcp9808RegTCrit, mcp9808EncodeLimit(celsius))
}

// SetHysteresis sets the boundary hysteresis: 0, 1.5, 3.0 or 6.0 °C,
// applied to the cooling edge only (ignored by the chip while either lock
// bit is set). Any other value returns ErrMCP9808InvalidHysteresis without
// a bus transaction.
func (d *MCP9808Full) SetHysteresis(celsius float32) error {
	code := mcp9808IndexOf(mcp9808Hystereses, celsius)
	if code < 0 {
		return ErrMCP9808InvalidHysteresis
	}
	return d.updateConfig(uint16(code)<<mcp9808CfgTHystShift, mcp9808CfgTHystMask)
}

// GetHysteresis reads the boundary hysteresis in °C.
func (d *MCP9808Full) GetHysteresis() (float32, error) {
	c, err := d.readReg(mcp9808RegConfig)
	if err != nil {
		return 0, err
	}
	return mcp9808Hystereses[(c&mcp9808CfgTHystMask)>>mcp9808CfgTHystShift], nil
}

// LockCriticalLimit locks TCRIT (and ALERT_SEL/POL/MOD). Irreversible
// except by power-on reset.
func (d *MCP9808Full) LockCriticalLimit() error { return d.updateConfig(mcp9808CfgCritLock, 0) }

// LockWindowLimits locks TUPPER/TLOWER (and ALERT_SEL/POL/MOD).
// Irreversible except by power-on reset.
func (d *MCP9808Full) LockWindowLimits() error { return d.updateConfig(mcp9808CfgWinLock, 0) }

// IsCriticalLimitLocked reports whether CRIT_LOCK is set.
func (d *MCP9808Full) IsCriticalLimitLocked() (bool, error) {
	return d.configBit(mcp9808CfgCritLock)
}

// IsWindowLimitsLocked reports whether WIN_LOCK is set.
func (d *MCP9808Full) IsWindowLimitsLocked() (bool, error) {
	return d.configBit(mcp9808CfgWinLock)
}

// ConfigureAlert sets the Alert output's source, mode and polarity
// together. It returns ErrMCP9808Locked without writing if either lock bit
// is set.
func (d *MCP9808Full) ConfigureAlert(mode MCP9808AlertMode, output MCP9808AlertOutput, polarity MCP9808AlertPolarity) error {
	c, err := d.readConfig()
	if err != nil {
		return err
	}
	if c&mcp9808CfgLocks != 0 {
		return ErrMCP9808Locked
	}
	c &^= mcp9808CfgAlertSel | mcp9808CfgAlertPol | mcp9808CfgAlertMod
	if mode == MCP9808AlertCriticalOnly {
		c |= mcp9808CfgAlertSel
	}
	if polarity == MCP9808AlertActiveHigh {
		c |= mcp9808CfgAlertPol
	}
	if output == MCP9808AlertInterrupt {
		c |= mcp9808CfgAlertMod
	}
	return d.writeConfig(c)
}

// EnableAlert enables the Alert output (ALERT_CNT = 1).
func (d *MCP9808Full) EnableAlert() error { return d.updateConfig(mcp9808CfgAlertCnt, 0) }

// DisableAlert disables the Alert output (ALERT_CNT = 0).
func (d *MCP9808Full) DisableAlert() error { return d.updateConfig(0, mcp9808CfgAlertCnt) }

// IsAlertAsserted reports whether the Alert output is currently asserted
// (ALERT_STAT).
func (d *MCP9808Full) IsAlertAsserted() (bool, error) {
	return d.configBit(mcp9808CfgAlertStat)
}

// ClearInterrupt clears an asserted interrupt-mode Alert output
// (INT_CLEAR = 1). It has no effect in comparator mode.
func (d *MCP9808Full) ClearInterrupt() error {
	c, err := d.readConfig()
	if err != nil {
		return err
	}
	return d.writeReg(mcp9808RegConfig, c|mcp9808CfgIntClear)
}

// PollInterrupt reads TA's live boundary-status bits — a mask of
// MCP9808SourceLower / MCP9808SourceUpper / MCP9808SourceCritical. Nothing
// is cleared: the bits are a live comparison, always current.
func (d *MCP9808Full) PollInterrupt() (uint8, error) {
	raw, err := d.readReg(mcp9808RegTA)
	if err != nil {
		return 0, err
	}
	return uint8(raw>>13) & 0x07, nil
}

// OnInterrupt subscribes callback to Alert events; it receives the
// PollInterrupt mask. With the connection's IntPin wired, it fires on every
// Alert edge — falling for active-low, rising for active-high, following
// the configured ALERT_POL (call ConfigureAlert first). Otherwise a polling
// goroutine calls back whenever the status mask changes. The Alert output
// must be enabled (EnableAlert) for a pin to see edges.
func (d *MCP9808Full) OnInterrupt(callback func(status uint8)) error {
	d.mu.Lock()
	if d.unsubscribe != nil {
		d.unsubscribe()
		d.unsubscribe = nil
	}
	if d.pollPin != nil {
		_ = d.pollPin.Close()
		d.pollPin = nil
	}
	d.callback = callback
	d.mu.Unlock()

	trigger := connection.Falling
	pin := d.conn.IntPin()
	if pin == nil {
		status, err := d.PollInterrupt()
		if err != nil {
			return err
		}
		poll := connection.NewDefaultPollingInputPin()
		d.mu.Lock()
		d.lastStatus = status
		d.pollPin = poll
		d.mu.Unlock()
		pin = poll
	} else {
		activeHigh, err := d.configBit(mcp9808CfgAlertPol)
		if err != nil {
			return err
		}
		if activeHigh {
			trigger = connection.Rising
		}
	}
	unsub := pin.OnEdge(trigger, func() { d.handleEdge() })
	d.mu.Lock()
	d.unsubscribe = unsub
	d.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes and stops delivery.
func (d *MCP9808Full) OffInterrupt() error {
	d.mu.Lock()
	unsub := d.unsubscribe
	d.unsubscribe = nil
	d.callback = nil
	poll := d.pollPin
	d.pollPin = nil
	d.mu.Unlock()

	if unsub != nil {
		unsub()
	}
	if poll != nil {
		return poll.Close()
	}
	return nil
}

func (d *MCP9808Full) handleEdge() {
	status, err := d.PollInterrupt()
	if err != nil {
		return
	}
	d.mu.Lock()
	cb := d.callback
	// A real Alert edge is always an event; the polling fallback ticks
	// continuously, so it only reports changes of the status mask.
	fire := d.pollPin == nil || status != d.lastStatus
	d.lastStatus = status
	d.mu.Unlock()
	if fire && cb != nil {
		cb(status)
	}
}
