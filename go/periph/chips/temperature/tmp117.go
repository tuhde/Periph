package temperature

import (
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// TMP117 register pointers.
const (
	tmp117RegTempResult uint8 = 0x00
	tmp117RegConfig     uint8 = 0x01
	tmp117RegTHigh      uint8 = 0x02
	tmp117RegTLow       uint8 = 0x03
	tmp117RegEepromUL   uint8 = 0x04
	tmp117RegEeprom1    uint8 = 0x05
	tmp117RegEeprom2    uint8 = 0x06
	tmp117RegTempOffset uint8 = 0x07
	tmp117RegEeprom3    uint8 = 0x08
	tmp117RegDeviceID   uint8 = 0x0F
)

// CONFIGURATION (0x01) bits.
const (
	tmp117CfgHighAlert uint16 = 0x8000
	tmp117CfgLowAlert  uint16 = 0x4000
	tmp117CfgDataReady uint16 = 0x2000
	tmp117CfgModShift  uint16 = 10
	tmp117CfgModMask   uint16 = 0x0C00
	tmp117CfgConvShift uint16 = 7
	tmp117CfgConvMask  uint16 = 0x0380
	tmp117CfgAvgShift  uint16 = 5
	tmp117CfgAvgMask   uint16 = 0x0060
	tmp117CfgTnA       uint16 = 0x0010
	tmp117CfgPol       uint16 = 0x0008
	tmp117CfgDrAlert   uint16 = 0x0004
	tmp117CfgSoftReset uint16 = 0x0002
	// Writable bits: MOD/CONV/AVG/T-nA/POL/DR-Alert. Soft_Reset is set only
	// on purpose.
	tmp117CfgWriteMask uint16 = 0x0FFC
)

// EEPROM_UL (0x04) bits.
const (
	tmp117Eun        uint16 = 0x8000
	tmp117EepromBusy uint16 = 0x4000
)

// TMP117I2CAddress is the default 7-bit I²C address (ADD0 = GND). The
// 4-level ADD0 strap selects any address in 0x48-0x4B.
const TMP117I2CAddress uint8 = 0x48

// TMP117DeviceID is the identity value checked at construction (DEVICE_ID
// bits 11:0; bits 15:12 are the silicon revision).
const TMP117DeviceID uint16 = 0x117

// Interrupt sources — CONFIGURATION's HIGH_Alert / LOW_Alert flags.
const (
	TMP117SourceHigh uint8 = 0x01 // result > THIGH_LIMIT
	TMP117SourceLow  uint8 = 0x02 // result < TLOW_LIMIT (Alert mode only)
)

// TMP117Mode selects the conversion mode (MOD[1:0]).
type TMP117Mode uint8

// TMP117Mode values.
const (
	TMP117Continuous TMP117Mode = 0 // continuous conversion (POR default)
	TMP117Shutdown   TMP117Mode = 1 // no conversions; TEMP_RESULT holds its last value
	TMP117OneShot    TMP117Mode = 3 // one conversion, then Shutdown
)

// TMP117AlertMode selects the ALERT behavior (T/nA).
type TMP117AlertMode uint8

// TMP117AlertMode values.
const (
	TMP117AlertWindow TMP117AlertMode = 0 // HIGH_Alert and LOW_Alert (POR default)
	TMP117AlertTherm  TMP117AlertMode = 1 // latching thermostat; TLOW_LIMIT resets it
)

// TMP117AlertPolarity selects the ALERT pin polarity (POL).
type TMP117AlertPolarity uint8

// TMP117AlertPolarity values.
const (
	TMP117AlertActiveLow  TMP117AlertPolarity = 0 // needs an external pull-up (POR default)
	TMP117AlertActiveHigh TMP117AlertPolarity = 1
)

// TMP117AlertPinFunction selects the ALERT pin function (DR/Alert).
type TMP117AlertPinFunction uint8

// TMP117AlertPinFunction values.
const (
	TMP117PinAlert     TMP117AlertPinFunction = 0 // alert/Therm status (POR default)
	TMP117PinDataReady TMP117AlertPinFunction = 1 // Data_Ready
)

// TMP117Config is the decoded conversion configuration returned by
// GetConfig.
type TMP117Config struct {
	Mode         TMP117Mode // conversion mode
	Averaging    uint8      // conversions averaged per result: 0, 8, 32 or 64
	CycleSeconds float32    // CONV[2:0] cycle time in s (no-averaging column)
}

// Errors returned by the TMP117 driver.
var (
	// ErrTMP117NotFound is returned by the constructors when DEVICE_ID bits
	// 11:0 are not 0x117.
	ErrTMP117NotFound = errors.New("TMP117: not found (DEVICE_ID mismatch)")
	// ErrTMP117InvalidAveraging is returned by Configure for an averaging
	// count other than 0, 8, 32 or 64.
	ErrTMP117InvalidAveraging = errors.New("TMP117: averaging must be 0, 8, 32 or 64")
	// ErrTMP117InvalidSlot is returned by ReadEepromScratch for a slot other
	// than 1, 2 or 3, and by WriteEepromScratch for any slot other than 2
	// (EEPROM1/EEPROM3 hold factory NIST-traceability data).
	ErrTMP117InvalidSlot = errors.New("TMP117: invalid EEPROM scratch slot")
)

var (
	// Conversion cycle times in s, indexed by CONV[2:0] (no-averaging column).
	tmp117Cycles = [8]float32{0.0155, 0.125, 0.25, 0.5, 1.0, 4.0, 8.0, 16.0}
	// Averaging counts, indexed by AVG[1:0].
	tmp117Averagings = [4]uint8{0, 8, 32, 64}
)

const tmp117LsbC = 0.0078125

func tmp117DecodeTemperature(raw16 uint16) float32 {
	return float32(int16(raw16)) * tmp117LsbC
}

// tmp117EncodeTemperature rounds half away from zero to 0.0078125 °C steps
// and clamps to the 16-bit two's-complement range (-256.0 to 255.9921875 °C).
func tmp117EncodeTemperature(celsius float32) uint16 {
	steps := celsius / tmp117LsbC
	var value int32
	if steps >= 0 {
		value = int32(steps + 0.5)
	} else {
		value = -int32(-steps + 0.5)
	}
	if value < -32768 {
		value = -32768
	}
	if value > 32767 {
		value = 32767
	}
	return uint16(int16(value))
}

func tmp117Abs(x float32) float32 {
	if x < 0 {
		return -x
	}
	return x
}

// TMP117Minimal is the minimal TMP117 interface: read the temperature.
//
// TMP117 ±0.1 °C high-accuracy, low-power digital temperature sensor (Texas
// Instruments): a NIST-traceable 16-bit sensor (0.0078125 °C per LSB) read
// over an I²C/SMBus-compatible bus. Registers are 16-bit, big-endian,
// addressed through a non-incrementing Register Pointer.
type TMP117Minimal struct {
	conn connection.Connection
}

// NewTMP117Minimal creates a TMP117Minimal and confirms the chip's identity
// (DEVICE_ID bits 11:0 = 0x117; the revision nibble is ignored), returning
// ErrTMP117NotFound on a mismatch. No register writes are made — the
// POR/EEPROM default (continuous conversion, 8-conversion averaging, 1 s
// cycle, Alert mode) already serves the primary use case.
func NewTMP117Minimal(conn connection.Connection) (*TMP117Minimal, error) {
	d := &TMP117Minimal{conn: conn}
	id, err := d.readReg(tmp117RegDeviceID)
	if err != nil {
		return nil, fmt.Errorf("TMP117: device not responding: %w", err)
	}
	if id&0x0FFF != TMP117DeviceID {
		return nil, ErrTMP117NotFound
	}
	return d, nil
}

func (d *TMP117Minimal) readReg(reg uint8) (uint16, error) {
	b, err := d.conn.WriteRead([]byte{reg}, 2)
	if err != nil {
		return 0, err
	}
	return uint16(b[0])<<8 | uint16(b[1]), nil
}

func (d *TMP117Minimal) writeReg(reg uint8, value uint16) error {
	return d.conn.Write([]byte{reg, byte(value >> 8), byte(value)})
}

func (d *TMP117Minimal) readTemperatureReg(reg uint8) (float32, error) {
	raw, err := d.readReg(reg)
	if err != nil {
		return 0, err
	}
	return tmp117DecodeTemperature(raw), nil
}

// ReadTemperature reads the temperature in °C, decoding TEMP_RESULT's 16-bit
// two's-complement value (0.0078125 °C per LSB). It returns -256.0 until the
// first conversion after power-up completes.
func (d *TMP117Minimal) ReadTemperature() (float32, error) {
	return d.readTemperatureReg(tmp117RegTempResult)
}

// TMP117Full extends TMP117Minimal with conversion mode, averaging and
// cycle-time control, one-shot triggering, both temperature limits, the
// calibration offset, soft reset, EEPROM persistence and scratch storage,
// and the Level-2 Alert/interrupt API.
type TMP117Full struct {
	*TMP117Minimal

	mu          sync.Mutex
	callback    func(uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
	lastStatus  uint8
}

// NewTMP117Full creates a TMP117Full; same identity check as
// NewTMP117Minimal.
func NewTMP117Full(conn connection.Connection) (*TMP117Full, error) {
	m, err := NewTMP117Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &TMP117Full{TMP117Minimal: m}, nil
}

func (d *TMP117Full) readConfig() (uint16, error) {
	c, err := d.readReg(tmp117RegConfig)
	return c & tmp117CfgWriteMask, err
}

func (d *TMP117Full) writeConfig(value uint16) error {
	return d.writeReg(tmp117RegConfig, value&tmp117CfgWriteMask)
}

func (d *TMP117Full) updateConfig(set, clear uint16) error {
	c, err := d.readConfig()
	if err != nil {
		return err
	}
	return d.writeConfig((c &^ clear) | set)
}

// Configure sets the conversion mode, averaging (0, 8, 32 or 64) and cycle
// time in s. The cycle time is matched to the nearest CONV[2:0] step from
// the no-averaging column (15.5 ms, 125 ms, 250 ms, 500 ms, 1 s, 4 s, 8 s,
// 16 s); at higher averaging the hardware lengthens short cycles
// automatically. The Alert configuration bits are preserved. An unsupported
// averaging returns ErrTMP117InvalidAveraging without a bus transaction.
func (d *TMP117Full) Configure(mode TMP117Mode, averaging uint8, cycleSeconds float32) error {
	avg := -1
	for i, a := range tmp117Averagings {
		if a == averaging {
			avg = i
		}
	}
	if avg < 0 {
		return ErrTMP117InvalidAveraging
	}
	conv := 0
	for code, cycle := range tmp117Cycles {
		if tmp117Abs(cycle-cycleSeconds) < tmp117Abs(tmp117Cycles[conv]-cycleSeconds) {
			conv = code
		}
	}
	set := uint16(mode)<<tmp117CfgModShift | uint16(conv)<<tmp117CfgConvShift | uint16(avg)<<tmp117CfgAvgShift
	return d.updateConfig(set, tmp117CfgModMask|tmp117CfgConvMask|tmp117CfgAvgMask)
}

// GetConfig reads the conversion mode, averaging and cycle time
// (CycleSeconds is the no-averaging CONV[2:0] step).
func (d *TMP117Full) GetConfig() (TMP117Config, error) {
	c, err := d.readReg(tmp117RegConfig)
	if err != nil {
		return TMP117Config{}, err
	}
	mode := TMP117Continuous // MOD = 10 reads back as continuous
	switch (c & tmp117CfgModMask) >> tmp117CfgModShift {
	case 1:
		mode = TMP117Shutdown
	case 3:
		mode = TMP117OneShot
	}
	return TMP117Config{
		Mode:         mode,
		Averaging:    tmp117Averagings[(c&tmp117CfgAvgMask)>>tmp117CfgAvgShift],
		CycleSeconds: tmp117Cycles[(c&tmp117CfgConvMask)>>tmp117CfgConvShift],
	}, nil
}

// IsShutdown reports whether the sensor is in Shutdown mode (MOD[1:0] =
// Shutdown).
func (d *TMP117Full) IsShutdown() (bool, error) {
	c, err := d.readReg(tmp117RegConfig)
	return (c&tmp117CfgModMask)>>tmp117CfgModShift == 1, err
}

// TriggerOneShot starts a single conversion (MOD[1:0] = One-Shot); the
// sensor returns to Shutdown once the conversion (including averaging)
// completes.
func (d *TMP117Full) TriggerOneShot() error {
	return d.updateConfig(uint16(TMP117OneShot)<<tmp117CfgModShift, tmp117CfgModMask)
}

// IsDataReady reports whether a fresh conversion result is available
// (Data_Ready). Reading this flag clears it (as does reading TEMP_RESULT).
func (d *TMP117Full) IsDataReady() (bool, error) {
	c, err := d.readReg(tmp117RegConfig)
	return c&tmp117CfgDataReady != 0, err
}

// GetHighLimit reads THIGH_LIMIT in °C.
func (d *TMP117Full) GetHighLimit() (float32, error) { return d.readTemperatureReg(tmp117RegTHigh) }

// SetHighLimit writes THIGH_LIMIT in °C, rounded to the nearest 0.0078125 °C
// and clamped to -256.0…255.9921875 °C.
func (d *TMP117Full) SetHighLimit(celsius float32) error {
	return d.writeReg(tmp117RegTHigh, tmp117EncodeTemperature(celsius))
}

// GetLowLimit reads TLOW_LIMIT in °C.
func (d *TMP117Full) GetLowLimit() (float32, error) { return d.readTemperatureReg(tmp117RegTLow) }

// SetLowLimit writes TLOW_LIMIT in °C, rounded to the nearest 0.0078125 °C
// and clamped to -256.0…255.9921875 °C. In Therm mode this is HIGH_Alert's
// reset threshold (hysteresis).
func (d *TMP117Full) SetLowLimit(celsius float32) error {
	return d.writeReg(tmp117RegTLow, tmp117EncodeTemperature(celsius))
}

// GetTemperatureOffset reads TEMP_OFFSET (calibration offset) in °C.
func (d *TMP117Full) GetTemperatureOffset() (float32, error) {
	return d.readTemperatureReg(tmp117RegTempOffset)
}

// SetTemperatureOffset writes TEMP_OFFSET in °C, added to every result after
// linearization; rounded to the nearest 0.0078125 °C and clamped to
// -256.0…255.9921875 °C.
func (d *TMP117Full) SetTemperatureOffset(celsius float32) error {
	return d.writeReg(tmp117RegTempOffset, tmp117EncodeTemperature(celsius))
}

// Reset triggers a software reset (Soft_Reset = 1) and waits the 2 ms reset
// time. It reloads CONFIGURATION, THIGH_LIMIT, TLOW_LIMIT and TEMP_OFFSET
// from EEPROM.
func (d *TMP117Full) Reset() error {
	if err := d.writeReg(tmp117RegConfig, tmp117CfgSoftReset); err != nil {
		return err
	}
	time.Sleep(2 * time.Millisecond)
	return nil
}

// UnlockEeprom unlocks the EEPROM (EUN = 1). While unlocked, writes to
// CONFIGURATION, THIGH_LIMIT, TLOW_LIMIT, TEMP_OFFSET and EEPROM2 also
// program the EEPROM as the new power-on default. Poll IsEepromBusy after
// each such write.
func (d *TMP117Full) UnlockEeprom() error { return d.writeReg(tmp117RegEepromUL, tmp117Eun) }

// LockEeprom locks the EEPROM (EUN = 0); register writes become volatile
// only.
func (d *TMP117Full) LockEeprom() error { return d.writeReg(tmp117RegEepromUL, 0x0000) }

// IsEepromBusy reports whether an EEPROM programming operation is in
// progress (EEPROM_Busy).
func (d *TMP117Full) IsEepromBusy() (bool, error) {
	v, err := d.readReg(tmp117RegEepromUL)
	return v&tmp117EepromBusy != 0, err
}

// ReadEepromScratch reads general-purpose EEPROM scratch register slot (1,
// 2 or 3 — EEPROM1/EEPROM2/EEPROM3; slots 1 and 3 hold factory
// NIST-traceability data). Any other slot returns ErrTMP117InvalidSlot
// without a bus transaction.
func (d *TMP117Full) ReadEepromScratch(slot uint8) (uint16, error) {
	var reg uint8
	switch slot {
	case 1:
		reg = tmp117RegEeprom1
	case 2:
		reg = tmp117RegEeprom2
	case 3:
		reg = tmp117RegEeprom3
	default:
		return 0, ErrTMP117InvalidSlot
	}
	return d.readReg(reg)
}

// WriteEepromScratch writes the general-purpose EEPROM2 scratch register.
// Only slot 2 is writable — EEPROM1/EEPROM3 hold factory NIST-traceability
// data; any other slot returns ErrTMP117InvalidSlot without a bus
// transaction. The value persists across power cycles only while the EEPROM
// is unlocked.
func (d *TMP117Full) WriteEepromScratch(slot uint8, value uint16) error {
	if slot != 2 {
		return ErrTMP117InvalidSlot
	}
	return d.writeReg(tmp117RegEeprom2, value)
}

// ConfigureAlert sets the ALERT output's mode, polarity and pin function
// together.
func (d *TMP117Full) ConfigureAlert(mode TMP117AlertMode, polarity TMP117AlertPolarity, pinFunction TMP117AlertPinFunction) error {
	var set uint16
	if mode == TMP117AlertTherm {
		set |= tmp117CfgTnA
	}
	if polarity == TMP117AlertActiveHigh {
		set |= tmp117CfgPol
	}
	if pinFunction == TMP117PinDataReady {
		set |= tmp117CfgDrAlert
	}
	return d.updateConfig(set, tmp117CfgTnA|tmp117CfgPol|tmp117CfgDrAlert)
}

// PollInterrupt reads CONFIGURATION's HIGH_Alert / LOW_Alert flags — a mask
// of TMP117SourceHigh / TMP117SourceLow. In Alert mode this read also clears
// both flags (a hardware side effect); in Therm mode HIGH_Alert clears only
// once the result drops below TLOW_LIMIT.
func (d *TMP117Full) PollInterrupt() (uint8, error) {
	c, err := d.readReg(tmp117RegConfig)
	if err != nil {
		return 0, err
	}
	var status uint8
	if c&tmp117CfgHighAlert != 0 {
		status |= TMP117SourceHigh
	}
	if c&tmp117CfgLowAlert != 0 {
		status |= TMP117SourceLow
	}
	return status, nil
}

// OnInterrupt subscribes callback to ALERT events; it receives the
// PollInterrupt mask. With the connection's IntPin wired, it fires on every
// ALERT edge — falling for active-low, rising for active-high, following the
// configured POL (call ConfigureAlert first). Otherwise a polling goroutine
// calls back whenever the status mask changes.
func (d *TMP117Full) OnInterrupt(callback func(status uint8)) error {
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
		c, err := d.readReg(tmp117RegConfig)
		if err != nil {
			return err
		}
		if c&tmp117CfgPol != 0 {
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
func (d *TMP117Full) OffInterrupt() error {
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

func (d *TMP117Full) handleEdge() {
	status, err := d.PollInterrupt()
	if err != nil {
		return
	}
	d.mu.Lock()
	cb := d.callback
	// A real ALERT edge is always an event; the polling fallback ticks
	// continuously, so it only reports changes of the status mask.
	fire := d.pollPin == nil || status != d.lastStatus
	d.lastStatus = status
	d.mu.Unlock()
	if fire && cb != nil {
		cb(status)
	}
}
