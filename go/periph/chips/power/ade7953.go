// Package power contains drivers for power-monitoring ICs.
package power

import (
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ADE7953 register addresses (16-bit register space).
const (
	ade7953RegConfig        uint16 = 0x102
	ade7953RegPFA           uint16 = 0x10A
	ade7953RegPeriod        uint16 = 0x10E
	ade7953RegInternalRes   uint16 = 0x120
	ade7953RegAWatt         uint16 = 0x212
	ade7953RegVRMS          uint16 = 0x21C
	ade7953RegAEnergyA      uint16 = 0x21E
	ade7953RegOVLVL         uint16 = 0x224
	ade7953RegOILVL         uint16 = 0x225
	ade7953RegVersion       uint16 = 0x702

	ade7953Reg120Unlock byte = 0xFE
	ade7953Reg120Value  uint16 = 0x30
)

// ADC scaling constants.
const (
	ade7953ADCFSVolts  = 0.5 / 1.4142135623730951
	ade7953ADCFSCode   = 9032007
	ade7953PowerFSCode = 4862401
	ade7953TSample     = 1.0 / 206900.0
	ade7953PFLSB       = 1.0 / 32768.0
	ade7953AngleLSB    = 1.0 / 223750.0
)

// INA7953Minimal is the minimal ADE7953 driver.
type ADE7953Minimal struct {
	conn           connection.Connection
	voltageGain    float64
	currentGainA   float64
	currentGainB   float64
	pgaA, pgaB, pgaV uint8
}

// NewADE7953Minimal creates a new ADE7953 minimal driver.
func NewADE7953Minimal(conn connection.Connection, voltageGain, currentGain float64) (*ADE7953Minimal, error) {
	d := &ADE7953Minimal{
		conn:         conn,
		voltageGain:  voltageGain,
		currentGainA: currentGain,
		currentGainB: currentGain,
		pgaA:         1,
		pgaB:         1,
		pgaV:         1,
	}
	if err := d.initChip(); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *ADE7953Minimal) initChip() error {
	time.Sleep(110 * time.Millisecond)
	if err := d.writeReg8(ade7953RegInternalRes, ade7953Reg120Unlock); err != nil {
		return err
	}
	return d.writeReg16(ade7953RegInternalRes, ade7953Reg120Value)
}

// writeReg8 sends the 16-bit register address followed by a single data byte.
func (d *ADE7953Minimal) writeReg8(reg uint16, value byte) error {
	payload := []byte{byte(reg >> 8), byte(reg & 0xFF), value}
	return d.conn.Write(payload)
}

func (d *ADE7953Minimal) writeReg16(reg uint16, value uint16) error {
	payload := []byte{byte(reg >> 8), byte(reg & 0xFF), byte(value >> 8), byte(value & 0xFF)}
	return d.conn.Write(payload)
}

func (d *ADE7953Minimal) writeReg24(reg uint16, value uint32) error {
	payload := []byte{
		byte(reg >> 8), byte(reg & 0xFF),
		byte(value >> 16), byte(value >> 8), byte(value & 0xFF),
	}
	return d.conn.Write(payload)
}

func (d *ADE7953Minimal) readReg24(reg uint16) (uint32, error) {
	addr := []byte{byte(reg >> 8), byte(reg & 0xFF)}
	buf, err := d.conn.WriteRead(addr, 3)
	if err != nil {
		return 0, err
	}
	return uint32(buf[0])<<16 | uint32(buf[1])<<8 | uint32(buf[2]), nil
}

func (d *ADE7953Minimal) readReg24Signed(reg uint16) (int32, error) {
	v, err := d.readReg24(reg)
	if err != nil {
		return 0, err
	}
	if v&0x800000 != 0 {
		return int32(v) - 0x1000000, nil
	}
	return int32(v), nil
}

func (d *ADE7953Minimal) readReg16(reg uint16) (uint16, error) {
	addr := []byte{byte(reg >> 8), byte(reg & 0xFF)}
	buf, err := d.conn.WriteRead(addr, 2)
	if err != nil {
		return 0, err
	}
	return uint16(buf[0])<<8 | uint16(buf[1]), nil
}

func (d *ADE7953Minimal) readReg16Signed(reg uint16) (int16, error) {
	v, err := d.readReg16(reg)
	if err != nil {
		return 0, err
	}
	return int16(v), nil
}

func (d *ADE7953Minimal) readReg8(reg uint16) (byte, error) {
	addr := []byte{byte(reg >> 8), byte(reg & 0xFF)}
	buf, err := d.conn.WriteRead(addr, 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (d *ADE7953Minimal) voltageScale() float64 {
	return (ade7953ADCFSVolts * d.voltageGain) / (float64(ade7953ADCFSCode) * float64(d.pgaV))
}

func (d *ADE7953Minimal) currentScale(gain float64) float64 {
	return (ade7953ADCFSVolts * gain) / float64(ade7953ADCFSCode)
}

func (d *ADE7953Minimal) powerScale(gain float64) float64 {
	return (ade7953ADCFSVolts * ade7953ADCFSVolts * d.voltageGain * gain) /
		float64(ade7953PowerFSCode)
}

func (d *ADE7953Minimal) energyScale(gain float64) float64 {
	return (ade7953ADCFSVolts * ade7953ADCFSVolts * d.voltageGain * gain * ade7953TSample) / 3600.0
}

// Voltage reads the RMS voltage on the voltage channel.
func (d *ADE7953Minimal) Voltage() (float64, error) {
	raw, err := d.readReg24(ade7953RegVRMS)
	if err != nil {
		return 0, err
	}
	return float64(raw) * d.voltageScale(), nil
}

// Current reads the RMS current on Current Channel A.
func (d *ADE7953Minimal) Current() (float64, error) {
	raw, err := d.readReg24(0x21A)
	if err != nil {
		return 0, err
	}
	return float64(raw) * d.currentScale(d.currentGainA), nil
}

// ActivePower reads the instantaneous active power on Current Channel A.
func (d *ADE7953Minimal) ActivePower() (float64, error) {
	raw, err := d.readReg24Signed(ade7953RegAWatt)
	if err != nil {
		return 0, err
	}
	return float64(raw) * d.powerScale(d.currentGainA), nil
}

// ActiveEnergy reads the active-energy accumulator for Current Channel A.
func (d *ADE7953Minimal) ActiveEnergy() (float64, error) {
	raw, err := d.readReg24Signed(ade7953RegAEnergyA)
	if err != nil {
		return 0, err
	}
	return float64(raw) * d.energyScale(d.currentGainA), nil
}

// Version reads the silicon version register.
func (d *ADE7953Minimal) Version() (byte, error) {
	return d.readReg8(ade7953RegVersion)
}

// PowerFactor reads the power factor for Current Channel A.
func (d *ADE7953Minimal) PowerFactor() (float64, error) {
	raw, err := d.readReg16Signed(ade7953RegPFA)
	if err != nil {
		return 0, err
	}
	return float64(raw) * ade7953PFLSB, nil
}

// LinePeriod reads the line period in seconds.
func (d *ADE7953Minimal) LinePeriod() (float64, error) {
	raw, err := d.readReg16(ade7953RegPeriod)
	if err != nil {
		return 0, err
	}
	return float64(raw+1) * ade7953AngleLSB, nil
}

// LineFrequency reads the line frequency in Hertz.
func (d *ADE7953Minimal) LineFrequency() (float64, error) {
	p, err := d.LinePeriod()
	if err != nil {
		return 0, err
	}
	return 1.0 / p, nil
}

// ReactivePower reads the instantaneous reactive power on Current Channel A.
func (d *ADE7953Minimal) ReactivePower() (float64, error) {
	raw, err := d.readReg24Signed(0x214)
	if err != nil {
		return 0, err
	}
	return float64(raw) * d.powerScale(d.currentGainA), nil
}

// ApparentPower reads the instantaneous apparent power on Current Channel A.
func (d *ADE7953Minimal) ApparentPower() (float64, error) {
	raw, err := d.readReg24Signed(0x210)
	if err != nil {
		return 0, err
	}
	return float64(raw) * d.powerScale(d.currentGainA), nil
}

// ConfigureOvervoltage sets the overvoltage threshold (volts).
func (d *ADE7953Minimal) ConfigureOvervoltage(threshold float64) error {
	raw := (threshold * float64(ade7953ADCFSCode) * float64(d.pgaV)) /
		(ade7953ADCFSVolts * d.voltageGain)
	if raw < 0 {
		raw = 0
	}
	if raw > 0xFFFFFF {
		raw = 0xFFFFFF
	}
	return d.writeReg24(ade7953RegOVLVL, uint32(raw))
}

// ConfigureOvercurrent sets the overcurrent threshold (amperes; shared by both channels).
func (d *ADE7953Minimal) ConfigureOvercurrent(threshold float64) error {
	raw := (threshold * float64(ade7953ADCFSCode)) / ade7953ADCFSVolts
	if raw < 0 {
		raw = 0
	}
	if raw > 0xFFFFFF {
		raw = 0xFFFFFF
	}
	return d.writeReg24(ade7953RegOILVL, uint32(raw))
}

// Reset performs a software reset, then re-applies the mandatory power-up
// register setting. Calibration registers revert to power-on defaults.
func (d *ADE7953Minimal) Reset() error {
	cfg, err := d.readReg16(ade7953RegConfig)
	if err != nil {
		return err
	}
	if err := d.writeReg16(ade7953RegConfig, cfg|(1<<7)); err != nil {
		return err
	}
	time.Sleep(110 * time.Millisecond)
	if err := d.writeReg8(ade7953RegInternalRes, ade7953Reg120Unlock); err != nil {
		return err
	}
	if err := d.writeReg16(ade7953RegInternalRes, ade7953Reg120Value); err != nil {
		return err
	}
	d.pgaA = 1
	d.pgaB = 1
	d.pgaV = 1
	return nil
}

// ADE7953Full embeds ADE7953Minimal and adds Channel B, reactive/apparent
// measurements, calibration, accumulation modes, power-quality features,
// zero-crossing, REVP, alternate outputs, CF pulses, interrupts, checksum,
// write protection, reset and last-operation diagnostics.
type ADE7953Full struct {
	*ADE7953Minimal
}

// NewADE7953Full creates a new ADE7953 full driver.
func NewADE7953Full(conn connection.Connection, voltageGain, currentGain float64) (*ADE7953Full, error) {
	m, err := NewADE7953Minimal(conn, voltageGain, currentGain)
	if err != nil {
		return nil, err
	}
	return &ADE7953Full{m}, nil
}

// ConfigureChannelB overrides the calibration constant used by every Channel B
// current/power/energy method. Defaults to Channel A's currentGain if never called.
func (d *ADE7953Full) ConfigureChannelB(currentGainB float64) {
	d.currentGainB = currentGainB
}

// CurrentB reads RMS current on Current Channel B (neutral).
func (d *ADE7953Full) CurrentB() (float64, error) {
	raw, err := d.readReg24(0x21B)
	if err != nil {
		return 0, err
	}
	return float64(raw) * d.currentScale(d.currentGainB), nil
}

// ActivePowerB reads the instantaneous active power on Current Channel B.
func (d *ADE7953Full) ActivePowerB() (float64, error) {
	raw, err := d.readReg24Signed(0x213)
	if err != nil {
		return 0, err
	}
	return float64(raw) * d.powerScale(d.currentGainB), nil
}

// ActiveEnergyB reads the active-energy accumulator for Current Channel B.
func (d *ADE7953Full) ActiveEnergyB() (float64, error) {
	raw, err := d.readReg24Signed(0x21F)
	if err != nil {
		return 0, err
	}
	return float64(raw) * d.energyScale(d.currentGainB), nil
}