// Package power contains drivers for Power management chips.
package power

import (
	"fmt"

	"github.com/tuhde/Periph/go/periph/transport"
)

// INA3221 register addresses.
const (
	ina3221RegConfig   uint8 = 0x00
	ina3221RegShunt1   uint8 = 0x01
	ina3221RegBus1     uint8 = 0x02
	ina3221RegShunt2   uint8 = 0x03
	ina3221RegBus2     uint8 = 0x04
	ina3221RegShunt3   uint8 = 0x05
	ina3221RegBus3     uint8 = 0x06
	ina3221RegCh1Crit  uint8 = 0x07
	ina3221RegCh1Warn  uint8 = 0x08
	ina3221RegCh2Crit  uint8 = 0x09
	ina3221RegCh2Warn  uint8 = 0x0A
	ina3221RegCh3Crit  uint8 = 0x0B
	ina3221RegCh3Warn  uint8 = 0x0C
	ina3221RegSum      uint8 = 0x0D
	ina3221RegSumLimit uint8 = 0x0E
	ina3221RegMaskEn   uint8 = 0x0F
	ina3221RegPVUpper  uint8 = 0x10
	ina3221RegPVLower  uint8 = 0x11
	ina3221RegMfrID    uint8 = 0xFE
	ina3221RegDieID    uint8 = 0xFF
)

// INA3221 Mask/Enable alert flag bit positions, matching the chip's
// register layout. Decoded from INA3221Full.AlertFlags.
const (
	// CF1 is the channel-1 critical-alert flag bit.
	CF1 uint16 = 0x0200
	// CF2 is the channel-2 critical-alert flag bit.
	CF2 uint16 = 0x0100
	// CF3 is the channel-3 critical-alert flag bit.
	CF3 uint16 = 0x0080
	// SF is the summation-alert flag bit.
	SF uint16 = 0x0040
	// WF1 is the channel-1 warning-alert flag bit.
	WF1 uint16 = 0x0020
	// WF2 is the channel-2 warning-alert flag bit.
	WF2 uint16 = 0x0010
	// WF3 is the channel-3 warning-alert flag bit.
	WF3 uint16 = 0x0008
	// PVF is the power-valid flag bit.
	PVF uint16 = 0x0004
	// TCF is the timing-control flag bit.
	TCF uint16 = 0x0002
	// CVRF is the conversion-ready flag bit.
	CVRF uint16 = 0x0001
)

// INA3221 operating-mode constants share their lower 3 bits with the
// INA219 and are declared in ina219.go (MODE_POWERDOWN through
// MODE_SHUNT_BUS_CONT). The INA3221 spec treats MODE 100 as a second
// power-down encoding — callers can use either MODE_POWERDOWN (= 0) or
// MODE_ADC_OFF (= 4) to power the chip down.

// ina3221ShuntRegs maps channel number (1–3) to its shunt voltage register.
var ina3221ShuntRegs = [3]uint8{ina3221RegShunt1, ina3221RegShunt2, ina3221RegShunt3}

// ina3221BusRegs maps channel number (1–3) to its bus voltage register.
var ina3221BusRegs = [3]uint8{ina3221RegBus1, ina3221RegBus2, ina3221RegBus3}

// ina3221CritRegs maps channel number (1–3) to its critical-alert limit register.
var ina3221CritRegs = [3]uint8{ina3221RegCh1Crit, ina3221RegCh2Crit, ina3221RegCh3Crit}

// ina3221WarnRegs maps channel number (1–3) to its warning-alert limit register.
var ina3221WarnRegs = [3]uint8{ina3221RegCh1Warn, ina3221RegCh2Warn, ina3221RegCh3Warn}

// ina3221ValidChannel converts a 1-based channel number into a 0-based
// index, returning an error for any value outside 1–3.
func ina3221ValidChannel(channel uint8) (uint8, error) {
	if channel < 1 || channel > 3 {
		return 0, fmt.Errorf("ina3221: channel must be 1, 2, or 3, got %d", channel)
	}
	return channel - 1, nil
}

// INA3221Minimal is the INA3221 three-channel 26V current/voltage/power
// monitor driver — minimal interface.
//
// Communicates over I²C at up to 400 kHz fast mode. The INA3221 has no
// Calibration, Current, or Power registers — current and power are
// computed in software from the shunt voltage and the user-supplied
// shunt resistance.
//
// The chip's power-on defaults are used (all three channels enabled,
// continuous shunt+bus, max range, no averaging) — the Minimal driver
// writes nothing at construction.
type INA3221Minimal struct {
	transport transport.Transport
	rShunt    [3]float32
}

// NewINA3221Minimal creates a new INA3221Minimal with a single shunt
// resistance value applied to all three channels.
//
// transport must be a configured I²C transport bound to the device's
// 7-bit address (0x40–0x43, default 0x40).
//
// rShunt is the shunt resistor value in ohms, applied to all three channels.
func NewINA3221Minimal(transport transport.Transport, rShunt float32) (*INA3221Minimal, error) {
	return &INA3221Minimal{
		transport: transport,
		rShunt:    [3]float32{rShunt, rShunt, rShunt},
	}, nil
}

// NewINA3221MinimalPerChannel creates a new INA3221Minimal with
// per-channel shunt resistance values.
//
// rShunt is a 3-element array of shunt resistor values in ohms, one per
// channel (index 0 = channel 1, index 1 = channel 2, index 2 = channel 3).
func NewINA3221MinimalPerChannel(transport transport.Transport, rShunt [3]float32) (*INA3221Minimal, error) {
	return &INA3221Minimal{transport: transport, rShunt: rShunt}, nil
}

// writeReg writes a 16-bit value to a register (big-endian, pointer byte
// followed by two data bytes).
func (d *INA3221Minimal) writeReg(reg uint8, value uint16) error {
	return d.transport.Write([]byte{reg, byte(value >> 8), byte(value & 0xFF)})
}

// readReg reads a 16-bit unsigned register value.
func (d *INA3221Minimal) readReg(reg uint8) (uint16, error) {
	buf, err := d.transport.WriteRead([]byte{reg}, 2)
	if err != nil {
		return 0, err
	}
	return uint16(buf[0])<<8 | uint16(buf[1]), nil
}

// readRegSigned reads a 16-bit register value and returns it as signed.
func (d *INA3221Minimal) readRegSigned(reg uint8) (int16, error) {
	v, err := d.readReg(reg)
	if err != nil {
		return 0, err
	}
	return int16(v), nil
}

// Voltage reads the bus voltage for a channel. LSB = 8 mV; the raw
// register is left-aligned by 3, so right-shifting by 3 then scaling
// gives the same result as multiplying the signed 16-bit raw value by 1 mV.
func (d *INA3221Minimal) Voltage(channel uint8) (float32, error) {
	idx, err := ina3221ValidChannel(channel)
	if err != nil {
		return 0, err
	}
	raw, err := d.readReg(ina3221BusRegs[idx])
	if err != nil {
		return 0, err
	}
	return float32(raw>>3) * 8e-3, nil
}

// ShuntVoltage reads the differential shunt voltage for a channel.
// LSB = 40 µV. The raw register stores a 13-bit signed left-aligned
// value; multiplying the signed 16-bit raw value by 5 µV is mathematically
// equivalent to an explicit sign-extending right-shift by 3.
func (d *INA3221Minimal) ShuntVoltage(channel uint8) (float32, error) {
	idx, err := ina3221ValidChannel(channel)
	if err != nil {
		return 0, err
	}
	raw, err := d.readRegSigned(ina3221ShuntRegs[idx])
	if err != nil {
		return 0, err
	}
	return float32(raw) * 5e-6, nil
}

// Current reads the calculated current for a channel, computed as
// shunt_voltage / r_shunt[channel].
func (d *INA3221Minimal) Current(channel uint8) (float32, error) {
	idx, err := ina3221ValidChannel(channel)
	if err != nil {
		return 0, err
	}
	sv, err := d.ShuntVoltage(channel)
	if err != nil {
		return 0, err
	}
	return sv / d.rShunt[idx], nil
}

// Power reads the calculated power for a channel, computed as
// voltage * current.
func (d *INA3221Minimal) Power(channel uint8) (float32, error) {
	v, err := d.Voltage(channel)
	if err != nil {
		return 0, err
	}
	c, err := d.Current(channel)
	if err != nil {
		return 0, err
	}
	return v * c, nil
}

// INA3221Full is the INA3221 driver — full interface. Extends
// INA3221Minimal with Configuration Register access, channel enables,
// per-channel critical and warning alerts, shunt-voltage summation,
// power-valid monitoring, reset, and shutdown/wake.
type INA3221Full struct {
	*INA3221Minimal
	mode uint8
}

// NewINA3221Full creates a new INA3221Full with a single shunt resistance
// value applied to all three channels. Same arguments as
// NewINA3221Minimal.
func NewINA3221Full(transport transport.Transport, rShunt float32) (*INA3221Full, error) {
	m, err := NewINA3221Minimal(transport, rShunt)
	if err != nil {
		return nil, err
	}
	return &INA3221Full{INA3221Minimal: m, mode: 0x07}, nil
}

// NewINA3221FullPerChannel creates a new INA3221Full with per-channel
// shunt resistance values.
func NewINA3221FullPerChannel(transport transport.Transport, rShunt [3]float32) (*INA3221Full, error) {
	m, err := NewINA3221MinimalPerChannel(transport, rShunt)
	if err != nil {
		return nil, err
	}
	return &INA3221Full{INA3221Minimal: m, mode: 0x07}, nil
}

// Configure writes the Configuration Register, preserving the channel-
// enable bits (CH1en, CH2en, CH3en) in bits 14:12.
//
// avg is the averaging count selector 0–7 (0 = 1 sample, 7 = 1024).
// vbusCT is the bus voltage conversion time selector 0–7 (default 4 = 1.1 ms).
// vshCT is the shunt voltage conversion time selector 0–7 (default 4 = 1.1 ms).
// mode is the operating mode 0–7 (default 7 = shunt+bus continuous).
func (d *INA3221Full) Configure(avg, vbusCT, vshCT, mode uint8) error {
	cfg, err := d.readReg(ina3221RegConfig)
	if err != nil {
		return err
	}
	config := (uint16(avg&0x07) << 9) |
		(uint16(vbusCT&0x07) << 6) |
		(uint16(vshCT&0x07) << 3) |
		uint16(mode&0x07)
	config |= cfg & 0x7000
	d.mode = mode & 0x07
	return d.writeReg(ina3221RegConfig, config)
}

// EnableChannel sets or clears the CH<n>en bit in the Configuration
// Register. Disabled channels are bypassed in the measurement sequence.
func (d *INA3221Full) EnableChannel(channel uint8, enabled bool) error {
	idx, err := ina3221ValidChannel(channel)
	if err != nil {
		return err
	}
	cfg, err := d.readReg(ina3221RegConfig)
	if err != nil {
		return err
	}
	bit := uint16(1) << uint(14-idx)
	if enabled {
		cfg |= bit
	} else {
		cfg &^= bit
	}
	return d.writeReg(ina3221RegConfig, cfg)
}

// ChannelEnabled reports whether a channel is enabled.
func (d *INA3221Full) ChannelEnabled(channel uint8) (bool, error) {
	idx, err := ina3221ValidChannel(channel)
	if err != nil {
		return false, err
	}
	cfg, err := d.readReg(ina3221RegConfig)
	if err != nil {
		return false, err
	}
	bit := uint16(1) << uint(14-idx)
	return cfg&bit != 0, nil
}

// ConversionReady reads the CVRF (Conversion Ready Flag) bit from the
// Mask/Enable Register. CVRF clears on a write to the Configuration
// Register (except for power-down) or on a read of Mask/Enable.
func (d *INA3221Full) ConversionReady() (bool, error) {
	v, err := d.readReg(ina3221RegMaskEn)
	if err != nil {
		return false, err
	}
	return v&CVRF != 0, nil
}

// SetCriticalAlert writes the critical-alert limit for a channel and
// updates the CEN (Critical-alert latch) bit in the Mask/Enable Register.
//
// limitV is the shunt-voltage threshold in volts. latch enables latched
// alert mode when true.
func (d *INA3221Full) SetCriticalAlert(channel uint8, limitV float32, latch bool) error {
	idx, err := ina3221ValidChannel(channel)
	if err != nil {
		return err
	}
	raw := (uint16(limitV/40e-6) << 3) & 0xFFF8
	if err := d.writeReg(ina3221CritRegs[idx], raw); err != nil {
		return err
	}
	cfg, err := d.readReg(ina3221RegMaskEn)
	if err != nil {
		return err
	}
	if latch {
		cfg |= 0x0400
	} else {
		cfg &^= 0x0400
	}
	return d.writeReg(ina3221RegMaskEn, cfg)
}

// SetWarningAlert writes the warning-alert limit for a channel and
// updates the WEN (Warning-alert latch) bit in the Mask/Enable Register.
//
// limitV is the shunt-voltage threshold in volts. latch enables latched
// alert mode when true.
func (d *INA3221Full) SetWarningAlert(channel uint8, limitV float32, latch bool) error {
	idx, err := ina3221ValidChannel(channel)
	if err != nil {
		return err
	}
	raw := (uint16(limitV/40e-6) << 3) & 0xFFF8
	if err := d.writeReg(ina3221WarnRegs[idx], raw); err != nil {
		return err
	}
	cfg, err := d.readReg(ina3221RegMaskEn)
	if err != nil {
		return err
	}
	if latch {
		cfg |= 0x0800
	} else {
		cfg &^= 0x0800
	}
	return d.writeReg(ina3221RegMaskEn, cfg)
}

// AlertFlags reads the raw 16-bit Mask/Enable Register. Reading the
// register clears the latched CF/WF/SF flags when latch mode is enabled.
func (d *INA3221Full) AlertFlags() (uint16, error) {
	return d.readReg(ina3221RegMaskEn)
}

// SetSummationChannels configures the shunt-voltage summation function.
// The selected channels' single-conversion shunt readings are summed
// into the Shunt-Voltage Sum register and compared against limitV.
//
// The summation function only makes sense when all selected channels
// use the same shunt resistance — the limit is in volts, not amperes.
func (d *INA3221Full) SetSummationChannels(channels []uint8, limitV float32) error {
	cfg, err := d.readReg(ina3221RegMaskEn)
	if err != nil {
		return err
	}
	cfg &^= 0xE000
	for _, ch := range channels {
		idx, err := ina3221ValidChannel(ch)
		if err != nil {
			return err
		}
		cfg |= uint16(1) << uint(15-idx)
	}
	if err := d.writeReg(ina3221RegMaskEn, cfg); err != nil {
		return err
	}
	raw := (uint16(limitV/40e-6) << 1) & 0xFFFE
	return d.writeReg(ina3221RegSumLimit, raw)
}

// SummationValue reads the Shunt-Voltage Sum Register and returns the
// sum of the selected channels' shunt voltages in volts.
func (d *INA3221Full) SummationValue() (float32, error) {
	raw, err := d.readRegSigned(ina3221RegSum)
	if err != nil {
		return 0, err
	}
	return float32(raw) * 5e-6, nil
}

// SetPowerValidLimits writes the Power-Valid upper and lower bus-voltage
// thresholds. The PV pin asserts high when all enabled bus-voltage
// readings exceed upperV, and de-asserts when any drops below lowerV.
func (d *INA3221Full) SetPowerValidLimits(upperV, lowerV float32) error {
	rawUpper := (uint16(upperV/8e-3) << 3) & 0xFFF8
	if err := d.writeReg(ina3221RegPVUpper, rawUpper); err != nil {
		return err
	}
	rawLower := (uint16(lowerV/8e-3) << 3) & 0xFFF8
	return d.writeReg(ina3221RegPVLower, rawLower)
}

// PowerValid reads the PVF (Power-Valid Flag) bit from the Mask/Enable
// Register. True when all enabled bus voltages are within the PV limits.
func (d *INA3221Full) PowerValid() (bool, error) {
	v, err := d.readReg(ina3221RegMaskEn)
	if err != nil {
		return false, err
	}
	return v&PVF != 0, nil
}

// Shutdown enters power-down mode (MODE = 000), saving the current mode
// for Wake.
func (d *INA3221Full) Shutdown() error {
	cfg, err := d.readReg(ina3221RegConfig)
	if err != nil {
		return err
	}
	d.mode = uint8(cfg & 0x07)
	return d.writeReg(ina3221RegConfig, cfg&0xFFF8)
}

// Wake restores the operating mode saved by Shutdown.
func (d *INA3221Full) Wake() error {
	cfg, err := d.readReg(ina3221RegConfig)
	if err != nil {
		return err
	}
	return d.writeReg(ina3221RegConfig, (cfg&0xFFF8)|uint16(d.mode))
}

// Reset sets the RST bit, restoring all registers to power-on defaults
// (including alert limits and the Mask/Enable register). The caller must
// re-program any user-set limits after this call.
func (d *INA3221Full) Reset() error {
	return d.writeReg(ina3221RegConfig, 0x8000)
}

// ManufacturerID reads the Manufacturer ID Register. Expect 0x5449
// (Texas Instruments, ASCII "TI"). Same value as the INA226.
func (d *INA3221Full) ManufacturerID() (uint16, error) {
	return d.readReg(ina3221RegMfrID)
}

// DieID reads the Die ID Register. Expect 0x3220.
func (d *INA3221Full) DieID() (uint16, error) {
	return d.readReg(ina3221RegDieID)
}
