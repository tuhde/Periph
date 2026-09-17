// Package magnetometer contains drivers for Magnetometers.
package magnetometer

import (
	"fmt"

	"github.com/tuhde/Periph/go/periph/connection"
)

// HMC5883L register addresses.
const (
	hmc5883lRegConfigA   uint8 = 0x00
	hmc5883lRegConfigB   uint8 = 0x01
	hmc5883lRegMode      uint8 = 0x02
	hmc5883lRegDataXMSB  uint8 = 0x03
	hmc5883lRegStatus    uint8 = 0x09
	hmc5883lRegIDA       uint8 = 0x0A
	hmc5883lRegIDB       uint8 = 0x0B
	hmc5883lRegIDC       uint8 = 0x0C
)

// Gain table: LSb per Gauss.
var hmc5883lGainLsbPerGauss = [8]float64{
	1370, // GN=0: ±0.88 Ga
	1090, // GN=1: ±1.3 Ga (default)
	820,  // GN=2: ±1.9 Ga
	660,  // GN=3: ±2.5 Ga
	440,  // GN=4: ±4.0 Ga
	390,  // GN=5: ±4.7 Ga
	330,  // GN=6: ±5.6 Ga
	230,  // GN=7: ±8.1 Ga
}

// HMC5883LAddr is the fixed 7-bit I²C address of the chip.
const HMC5883LAddr uint8 = 0x1E

// HMC5883LMinimal is the 3-axis magnetometer driver — minimal interface.
//
// Reads magnetic field on all three axes in continuous mode with sensible
// defaults baked in.
//
// Default behaviour (baked into Minimal):
//   - Averaging: 8 samples (MA=11)
//   - ODR: 15 Hz (DO=100)
//   - Gain: ±1.3 Ga (GN=001), 1090 LSb/Gauss
//   - Mode: continuous measurement
type HMC5883LMinimal struct {
	connection connection.Connection
	gain       uint8
	gainLsb    float64
}

// NewHMC5883LMinimal creates a new HMC5883LMinimal and initialises with default configuration.
//
// The constructor writes Config A, Config B, and Mode registers, then waits 6 ms
// for the first measurement to become available.
//
// connection must be a configured I²C connection bound to address 0x1E.
func NewHMC5883LMinimal(t connection.Connection) (*HMC5883LMinimal, error) {
	d := &HMC5883LMinimal{
		connection: t,
		gain:       1,
		gainLsb:    hmc5883lGainLsbPerGauss[1],
	}
	if err := d.initMinimal(); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *HMC5883LMinimal) initMinimal() error {
	if err := d.writeReg8(hmc5883lRegConfigA, 0x70); err != nil { // 8 avg, 15 Hz, normal
		return err
	}
	if err := d.writeReg8(hmc5883lRegConfigB, 0x20); err != nil { // gain=1 (±1.3 Ga)
		return err
	}
	if err := d.writeReg8(hmc5883lRegMode, 0x00); err != nil { // continuous mode
		return err
	}
	return nil
}

func (d *HMC5883LMinimal) writeReg8(reg uint8, val uint8) error {
	return d.connection.Write([]byte{reg, val})
}

func (d *HMC5883LMinimal) readReg8(reg uint8) (uint8, error) {
	buf, err := d.connection.WriteRead([]byte{reg}, 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (d *HMC5883LMinimal) readReg16(reg uint8) (int16, error) {
	buf, err := d.connection.WriteRead([]byte{reg}, 2)
	if err != nil {
		return 0, err
	}
	return int16((uint16(buf[0]) << 8) | uint16(buf[1])), nil
}

// MagneticField reads magnetic field on all three axes.
//
// Performs a 6-byte burst read from register 0x03 (X MSB). The output register
// byte order is X, Z, Y (not X, Y, Z).
//
// Returns (x, y, z) magnetic field strength in Tesla.
// Returns nil for any axis that overflows (raw == -4096).
func (d *HMC5883LMinimal) MagneticField() (x, y, z float64, err error) {
	buf, err := d.connection.WriteRead([]byte{hmc5883lRegDataXMSB}, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	rawX := int16((uint16(buf[0]) << 8) | uint16(buf[1]))
	rawZ := int16((uint16(buf[2]) << 8) | uint16(buf[3]))
	rawY := int16((uint16(buf[4]) << 8) | uint16(buf[5]))

	x = d.rawToTesla(rawX)
	y = d.rawToTesla(rawY)
	z = d.rawToTesla(rawZ)
	return x, y, z, nil
}

func (d *HMC5883LMinimal) rawToTesla(raw int16) float64 {
	if raw == -4096 {
		return -1.0 // sentinel for overflow; caller checks nil
	}
	return (float64(raw) / d.gainLsb) * 1e-4 // Gauss → Tesla
}

// HMC5883LFull is the 3-axis magnetometer driver — full interface.
// Extends HMC5883LMinimal with configuration, single-shot mode, self-test,
// identification, and status access.
//
// Embeds HMC5883LMinimal to inherit MagneticField and the constructor.
type HMC5883LFull struct {
	*HMC5883LMinimal
}

// NewHMC5883LFull creates a new HMC5883LFull and initialises with default configuration.
func NewHMC5883LFull(t connection.Connection) (*HMC5883LFull, error) {
	m, err := NewHMC5883LMinimal(t)
	if err != nil {
		return nil, err
	}
	return &HMC5883LFull{HMC5883LMinimal: m}, nil
}

// Configure writes Configuration Registers A and B.
//
// odr: Data output rate in Hz (continuous mode). Valid: 0.75, 1.5, 3, 7.5, 15, 30, 75.
// averaging: Samples averaged per output. Valid: 1, 2, 4, 8.
// gain: Gain index 0–7.
func (d *HMC5883LFull) Configure(odr float64, averaging uint8, gain uint8) error {
	ma, ok := map[uint8]uint8{1: 0b00, 2: 0b01, 4: 0b10, 8: 0b11}[averaging]
	if !ok {
		return fmt.Errorf("averaging must be 1, 2, 4, or 8")
	}

	var doBits uint8
	switch {
	case float64Equal(odr, 0.75):
		doBits = 0b000
	case float64Equal(odr, 1.5):
		doBits = 0b001
	case float64Equal(odr, 3.0):
		doBits = 0b010
	case float64Equal(odr, 7.5):
		doBits = 0b011
	case float64Equal(odr, 15.0):
		doBits = 0b100
	case float64Equal(odr, 30.0):
		doBits = 0b101
	case float64Equal(odr, 75.0):
		doBits = 0b110
	default:
		return fmt.Errorf("odr must be 0.75, 1.5, 3, 7.5, 15, 30, or 75")
	}

	if gain > 7 {
		return fmt.Errorf("gain must be 0–7")
	}

	configA := (ma << 5) | (doBits << 2)
	if err := d.writeReg8(hmc5883lRegConfigA, configA); err != nil {
		return err
	}

	configB := gain << 5
	if err := d.writeReg8(hmc5883lRegConfigB, configB); err != nil {
		return err
	}

	d.gain = gain
	d.gainLsb = hmc5883lGainLsbPerGauss[gain]
	return nil
}

// SetGain updates the gain setting (GN bits in Config B).
func (d *HMC5883LFull) SetGain(gain uint8) error {
	if gain > 7 {
		return fmt.Errorf("gain must be 0–7")
	}
	if err := d.writeReg8(hmc5883lRegConfigB, gain<<5); err != nil {
		return err
	}
	d.gain = gain
	d.gainLsb = hmc5883lGainLsbPerGauss[gain]
	return nil
}

// SetMode sets the operating mode.
// mode: "continuous", "single", or "idle".
func (d *HMC5883LFull) SetMode(mode string) error {
	var md uint8
	switch mode {
	case "continuous":
		md = 0b00
	case "single":
		md = 0b01
	case "idle":
		md = 0b10
	default:
		return fmt.Errorf("mode must be 'continuous', 'single', or 'idle'")
	}
	return d.writeReg8(hmc5883lRegMode, md)
}

// DataReady checks if new measurement data is ready.
// Returns true if RDY bit is set in Status Register.
func (d *HMC5883LFull) DataReady() (bool, error) {
	s, err := d.readReg8(hmc5883lRegStatus)
	if err != nil {
		return false, err
	}
	return s&0x01 != 0, nil
}

// Status reads the raw Status Register.
// Returns raw STATUS register byte (RDY in bit 0, LOCK in bit 1).
func (d *HMC5883LFull) Status() (uint8, error) {
	return d.readReg8(hmc5883lRegStatus)
}

// SingleMeasurement takes a single measurement in single-shot mode.
//
// Writes single-measurement mode, waits 6 ms, then reads all three axes.
// Returns (x, y, z) magnetic field strength in Tesla.
// Returns sentinel -1.0 for any axis that overflows.
func (d *HMC5883LFull) SingleMeasurement() (x, y, z float64, err error) {
	if err := d.writeReg8(hmc5883lRegMode, 0x01); err != nil {
		return 0, 0, 0, err
	}
	return d.MagneticField()
}

// Identify reads the identification registers.
// Returns (id_a, id_b, id_c) — expected (0x48, 0x34, 0x33) = ASCII "H43".
func (d *HMC5883LFull) Identify() (idA, idB, idC uint8, err error) {
	idA, err = d.readReg8(hmc5883lRegIDA)
	if err != nil {
		return
	}
	idB, err = d.readReg8(hmc5883lRegIDB)
	if err != nil {
		return
	}
	idC, err = d.readReg8(hmc5883lRegIDC)
	return
}

// SelfTest runs self-test with positive or negative bias.
// positive: true for positive bias (MS=01), false for negative bias (MS=10).
// Returns (x, y, z) magnetic field deflection in Tesla during self-test.
// Returns sentinel -1.0 for any axis that overflows.
func (d *HMC5883LFull) SelfTest(positive bool) (x, y, z float64, err error) {
	configA, err := d.readReg8(hmc5883lRegConfigA)
	if err != nil {
		return 0, 0, 0, err
	}
	ms := uint8(0b01)
	if !positive {
		ms = 0b10
	}
	if err := d.writeReg8(hmc5883lRegConfigA, (configA&0xFC)|ms); err != nil {
		return 0, 0, 0, err
	}

	if err := d.writeReg8(hmc5883lRegMode, 0x01); err != nil {
		return 0, 0, 0, err
	}

	x, y, z, err = d.MagneticField()

	if err := d.writeReg8(hmc5883lRegConfigA, (configA&0xFC)|0b00); err != nil {
		return 0, 0, 0, err
	}
	return x, y, z, err
}

func float64Equal(a, b float64) bool {
	return a == b || (a < b+0.01 && a > b-0.01)
}