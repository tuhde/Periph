package temperature

import (
	"errors"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// TMP102 register pointers.
const (
	tmp102RegTemperature uint8 = 0x00
	tmp102RegConfig      uint8 = 0x01
	tmp102RegTLow        uint8 = 0x02
	tmp102RegTHigh       uint8 = 0x03
)

// CONFIGURATION (0x01) bits.
const (
	tmp102CfgOS       uint16 = 0x8000
	tmp102CfgFShift   uint16 = 11
	tmp102CfgFMask    uint16 = 0x1800
	tmp102CfgPol      uint16 = 0x0400
	tmp102CfgTM       uint16 = 0x0200
	tmp102CfgSD       uint16 = 0x0100
	tmp102CfgCRShift  uint16 = 6
	tmp102CfgCRMask   uint16 = 0x00C0
	tmp102CfgAL       uint16 = 0x0020
	tmp102CfgEM       uint16 = 0x0010
	tmp102CfgWritable uint16 = 0x9FD0
	tmp102CfgReserved uint16 = 0x6000 // R1:R0, read-only, always 11
)

const (
	tmp102LsbC     = 0.0625
	tmp102LimitMin = -55.0
	tmp102LimitMax = 150.0
)

var (
	tmp102Rates  = [4]float32{0.25, 1.0, 4.0, 8.0}
	tmp102Faults = [4]uint8{1, 2, 4, 6}
)

// TMP102I2CAddress is the default 7-bit I²C address (ADD0 = GND). The ADD0
// strap selects any address in 0x48-0x4B.
const TMP102I2CAddress uint8 = 0x48

// Errors returned by the TMP102 driver.
var (
	// ErrTMP102InvalidRate is returned by SetConversionRate for a rate other
	// than 0.25, 1, 4 or 8 Hz.
	ErrTMP102InvalidRate = errors.New("TMP102: conversion rate must be 0.25, 1, 4 or 8 Hz")
	// ErrTMP102InvalidFaultQueue is returned by SetFaultQueue for a count
	// other than 1, 2, 4 or 6.
	ErrTMP102InvalidFaultQueue = errors.New("TMP102: fault queue must be 1, 2, 4 or 6")
	// ErrTMP102Timeout is returned by OneShot when the conversion does not
	// finish within 30 ms.
	ErrTMP102Timeout = errors.New("TMP102: one-shot conversion timed out")
)

func tmp102Signed(value uint16, bits uint) int16 {
	sign := int32(1) << (bits - 1)
	return int16((int32(value) ^ sign) - sign)
}

// tmp102DecodeRaw decodes a TEMPERATURE register; bit 0 = 1 marks the 13-bit format.
func tmp102DecodeRaw(raw16 uint16) int16 {
	if raw16&0x0001 != 0 {
		return tmp102Signed(raw16>>3, 13)
	}
	return tmp102Signed(raw16>>4, 12)
}

func tmp102EncodeLimit(celsius float32, extended bool) uint16 {
	if celsius < tmp102LimitMin {
		celsius = tmp102LimitMin
	}
	if celsius > tmp102LimitMax {
		celsius = tmp102LimitMax
	}
	steps := celsius / tmp102LsbC
	var counts int32
	if steps >= 0 {
		counts = int32(steps + 0.5)
	} else {
		counts = -int32(-steps + 0.5)
	}
	if extended {
		return uint16((counts << 3) & 0xFFF8)
	}
	if counts > 2047 {
		counts = 2047
	}
	return uint16((counts << 4) & 0xFFF0)
}

// TMP102Minimal is the minimal TMP102 interface: read the temperature.
//
// TMP102 12-bit digital temperature sensor with ALERT output (Texas
// Instruments): 0.0625 °C per LSB, ±0.5 °C typical, read over an
// I²C/SMBus-compatible bus. Registers are 16-bit, big-endian, addressed
// through a persistent Register Pointer.
type TMP102Minimal struct {
	conn connection.RegisterConnection
}

// NewTMP102Minimal creates a TMP102Minimal. It makes no bus access — the
// power-on default (continuous conversion at 4 Hz, comparator mode, 12-bit)
// already serves the primary use case.
func NewTMP102Minimal(conn connection.RegisterConnection) *TMP102Minimal {
	return &TMP102Minimal{conn: conn}
}

func (d *TMP102Minimal) readReg(reg uint8) (uint16, error) {
	b, err := d.conn.ReadReg(uint32(reg), 2)
	if err != nil {
		return 0, err
	}
	return uint16(b[0])<<8 | uint16(b[1]), nil
}

func (d *TMP102Minimal) writeReg(reg uint8, value uint16) error {
	return d.conn.WriteReg(uint32(reg), []byte{byte(value >> 8), byte(value)})
}

// ReadTemperature reads the temperature in °C, decoding the 12-bit
// (0.0625 °C per LSB) or, in extended mode, 13-bit two's-complement value;
// the format is taken from bit 0 of the result.
func (d *TMP102Minimal) ReadTemperature() (float32, error) {
	raw, err := d.readReg(tmp102RegTemperature)
	if err != nil {
		return 0, err
	}
	return float32(tmp102DecodeRaw(raw)) * tmp102LsbC, nil
}

// TMP102Full extends TMP102Minimal with conversion rate, extended mode, the
// T_LOW/T_HIGH limits, fault queue, polarity, thermostat mode,
// shutdown/one-shot, alert status, general-call reset and the ALERT
// interrupt API.
type TMP102Full struct {
	*TMP102Minimal

	mu          sync.Mutex
	callback    func(float32)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewTMP102Full creates a TMP102Full; makes no bus access.
func NewTMP102Full(conn connection.RegisterConnection) *TMP102Full {
	return &TMP102Full{TMP102Minimal: NewTMP102Minimal(conn)}
}

// ReadRaw reads the raw signed temperature count (temperature = count × 0.0625 °C).
func (d *TMP102Full) ReadRaw() (int16, error) {
	raw, err := d.readReg(tmp102RegTemperature)
	if err != nil {
		return 0, err
	}
	return tmp102DecodeRaw(raw), nil
}

// ReadConfig reads the 16-bit CONFIGURATION register (reset value 0x60A0).
func (d *TMP102Full) ReadConfig() (uint16, error) { return d.readReg(tmp102RegConfig) }

// WriteConfig writes the 16-bit CONFIGURATION register; read-only bits are
// masked out.
func (d *TMP102Full) WriteConfig(value uint16) error {
	return d.writeReg(tmp102RegConfig, (value&tmp102CfgWritable)|tmp102CfgReserved)
}

func (d *TMP102Full) updateConfig(clear, set uint16) error {
	c, err := d.ReadConfig()
	if err != nil {
		return err
	}
	return d.WriteConfig((c &^ clear) | set)
}

// SetConversionRate sets the conversion rate: 0.25, 1, 4 or 8 Hz.
func (d *TMP102Full) SetConversionRate(hz float32) error {
	for i, r := range tmp102Rates {
		if r == hz {
			return d.updateConfig(tmp102CfgCRMask, uint16(i)<<tmp102CfgCRShift)
		}
	}
	return ErrTMP102InvalidRate
}

// SetExtendedMode enables or disables the 13-bit extended mode (EM bit).
func (d *TMP102Full) SetExtendedMode(on bool) error {
	var set uint16
	if on {
		set = tmp102CfgEM
	}
	return d.updateConfig(tmp102CfgEM, set)
}

func (d *TMP102Full) setLimit(reg uint8, celsius float32) error {
	c, err := d.ReadConfig()
	if err != nil {
		return err
	}
	return d.writeReg(reg, tmp102EncodeLimit(celsius, c&tmp102CfgEM != 0))
}

func (d *TMP102Full) getLimit(reg uint8) (float32, error) {
	c, err := d.ReadConfig()
	if err != nil {
		return 0, err
	}
	raw, err := d.readReg(reg)
	if err != nil {
		return 0, err
	}
	if c&tmp102CfgEM != 0 {
		return float32(tmp102Signed(raw>>3, 13)) * tmp102LsbC, nil
	}
	return float32(tmp102Signed(raw>>4, 12)) * tmp102LsbC, nil
}

// SetTHigh sets the T_HIGH limit (reset 80 °C), rounded to 0.0625 °C and
// clamped to -55...+150 °C (12-bit mode: up to +127.9375 °C).
func (d *TMP102Full) SetTHigh(celsius float32) error { return d.setLimit(tmp102RegTHigh, celsius) }

// GetTHigh reads the T_HIGH limit in °C.
func (d *TMP102Full) GetTHigh() (float32, error) { return d.getLimit(tmp102RegTHigh) }

// SetTLow sets the T_LOW limit (reset 75 °C); same coding as SetTHigh.
func (d *TMP102Full) SetTLow(celsius float32) error { return d.setLimit(tmp102RegTLow, celsius) }

// GetTLow reads the T_LOW limit in °C.
func (d *TMP102Full) GetTLow() (float32, error) { return d.getLimit(tmp102RegTLow) }

// SetFaultQueue sets the number of consecutive faults that trigger ALERT:
// 1, 2, 4 or 6.
func (d *TMP102Full) SetFaultQueue(count uint8) error {
	for i, c := range tmp102Faults {
		if c == count {
			return d.updateConfig(tmp102CfgFMask, uint16(i)<<tmp102CfgFShift)
		}
	}
	return ErrTMP102InvalidFaultQueue
}

// SetPolarity sets the ALERT polarity (POL): true = active high.
func (d *TMP102Full) SetPolarity(activeHigh bool) error {
	var set uint16
	if activeHigh {
		set = tmp102CfgPol
	}
	return d.updateConfig(tmp102CfgPol, set)
}

// SetThermostatMode selects the thermostat mode (TM): true = interrupt,
// false = comparator.
func (d *TMP102Full) SetThermostatMode(interrupt bool) error {
	var set uint16
	if interrupt {
		set = tmp102CfgTM
	}
	return d.updateConfig(tmp102CfgTM, set)
}

// Shutdown shuts the device down after the current conversion (SD = 1).
func (d *TMP102Full) Shutdown() error { return d.updateConfig(0, tmp102CfgSD) }

// Wake resumes continuous conversion (SD = 0).
func (d *TMP102Full) Wake() error { return d.updateConfig(tmp102CfgSD, 0) }

// OneShot takes a single conversion and returns the temperature in °C. It
// puts the device in shutdown if needed, sets OS = 1, polls OS until the
// conversion is done (30 ms timeout, ErrTMP102Timeout) and restores the
// previous mode.
func (d *TMP102Full) OneShot() (float32, error) {
	config, err := d.ReadConfig()
	if err != nil {
		return 0, err
	}
	wasRunning := config&tmp102CfgSD == 0
	if wasRunning {
		config |= tmp102CfgSD
		if err := d.WriteConfig(config); err != nil {
			return 0, err
		}
	}
	if err := d.WriteConfig(config | tmp102CfgOS); err != nil {
		return 0, err
	}
	done := false
	for i := 0; i < 30; i++ {
		time.Sleep(time.Millisecond)
		c, err := d.ReadConfig()
		if err != nil {
			return 0, err
		}
		if c&tmp102CfgOS != 0 {
			done = true
			break
		}
	}
	t, err := d.ReadTemperature()
	if err != nil {
		return 0, err
	}
	if wasRunning {
		if err := d.Wake(); err != nil {
			return 0, err
		}
	}
	if !done {
		return 0, ErrTMP102Timeout
	}
	return t, nil
}

// AlertState reports whether ALERT is currently active (the AL bit corrected
// for POL).
func (d *TMP102Full) AlertState() (bool, error) {
	c, err := d.ReadConfig()
	if err != nil {
		return false, err
	}
	al := c&tmp102CfgAL != 0
	// AL reads 1 while inactive with POL = 0 (inverted); POL = 1 flips it.
	if c&tmp102CfgPol != 0 {
		return al, nil
	}
	return !al, nil
}

// Reset sends the general-call reset (0x00 0x06), resetting every
// general-call device on the bus to power-up values. It needs a connection
// that transmits the bytes to the general-call address.
func (d *TMP102Full) Reset() error { return d.conn.Write([]byte{0x00, 0x06}) }

// PollInterrupt reads the alert state and clears an interrupt-mode alert
// (reads CONFIGURATION, then TEMPERATURE). It reports true while alerting.
func (d *TMP102Full) PollInterrupt() (bool, error) {
	alerting, err := d.AlertState()
	if err != nil {
		return false, err
	}
	if _, err := d.readReg(tmp102RegTemperature); err != nil {
		return false, err
	}
	return alerting, nil
}

// OnInterrupt subscribes callback to ALERT events; it receives the
// temperature in °C. With the connection's IntPin wired, it fires on every
// active ALERT edge (falling for active-low, rising for active-high,
// following POL). Otherwise a 1 s polling goroutine calls back while
// PollInterrupt reports an alert.
func (d *TMP102Full) OnInterrupt(callback func(temperatureC float32)) error {
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
		poll := connection.NewPollingInputPin(time.Second)
		d.mu.Lock()
		d.pollPin = poll
		d.mu.Unlock()
		pin = poll
	} else {
		c, err := d.ReadConfig()
		if err != nil {
			return err
		}
		if c&tmp102CfgPol != 0 {
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
func (d *TMP102Full) OffInterrupt() error {
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

func (d *TMP102Full) handleEdge() {
	d.mu.Lock()
	cb := d.callback
	polling := d.pollPin != nil
	d.mu.Unlock()
	if cb == nil {
		return
	}
	if polling {
		alerting, err := d.PollInterrupt()
		if err != nil || !alerting {
			return
		}
	}
	t, err := d.ReadTemperature()
	if err != nil {
		return
	}
	cb(t)
}
