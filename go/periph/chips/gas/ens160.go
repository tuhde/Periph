// Package gas contains drivers for Gas sensors (eCO2, TVOC, etc.).
package gas

import (
	"fmt"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/transport"
)

// ENS160 register map.
const (
	regPartID       = 0x00
	regOPMODE       = 0x10
	regCONFIG       = 0x11
	regCOMMAND      = 0x12
	regTEMP_IN      = 0x13
	regRH_IN        = 0x15
	regDeviceStatus = 0x20
	regDataAQI      = 0x21
	regDataTVOC     = 0x22
	regDataECO2     = 0x24
	regDataT        = 0x30
	regDataRH       = 0x32
	regGPR_READ     = 0x48
)

// OPMODE values.
const (
	opModeDeepSleep = 0x00
	opModeIdle      = 0x01
	opModeStandard  = 0x02
)

// COMMAND values (IDLE-only).
const (
	cmdNOP     = 0x00
	cmdAppVer  = 0x0E
	cmdClrGPR  = 0xCC
)

// ENS160 expected PART_ID (little-endian uint16 = 0x60, 0x01).
const partIDExpected = 0x0160

// AQI validity flags.
const (
	ValidityOK              = 0
	ValidityWarmup          = 1
	ValidityInitialStartup  = 2
	ValidityInvalid         = 3
)

const (
	ens160StepDelay     = 1 * time.Millisecond
	ens160NewDataWaitMs = 5000
)

// ENS160Minimal is the ENS160 digital multi-gas sensor driver — minimal
// interface.
//
// Provides calibrated air quality readings (AQI, TVOC, eCO2) with no
// configuration required beyond the transport. The sensor performs
// automatic baseline correction and on-chip signal processing.
//
// Default: STANDARD mode (gas sensing active), polling only, no external
// T/RH compensation.
type ENS160Minimal struct {
	transport transport.Transport
}

// NewENS160Minimal creates a new ENS160Minimal, verifies PART_ID, and
// starts STANDARD mode.
//
// transport must be a configured I²C transport bound to the device's
// 7-bit address (0x52 default, 0x53 alternate).
func NewENS160Minimal(t transport.Transport) (*ENS160Minimal, error) {
	d := &ENS160Minimal{transport: t}
	// Force IDLE for a clean init state.
	if err := d.writeReg(regOPMODE, opModeIdle); err != nil {
		return nil, err
	}
	time.Sleep(ens160StepDelay)
	part, err := d.readReg16LE(regPartID)
	if err != nil {
		return nil, err
	}
	if part != partIDExpected {
		return nil, fmt.Errorf("ENS160 PART_ID: expected 0x%04X, got 0x%04X", partIDExpected, part)
	}
	if err := d.writeReg(regOPMODE, opModeStandard); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *ENS160Minimal) writeReg(reg, value byte) error {
	return d.transport.Write([]byte{reg, value})
}

func (d *ENS160Minimal) writeReg16LE(reg byte, value uint16) error {
	return d.transport.Write([]byte{reg, byte(value & 0xFF), byte((value >> 8) & 0xFF)})
}

func (d *ENS160Minimal) readReg(reg byte, n int) ([]byte, error) {
	return d.transport.WriteRead([]byte{reg}, n)
}

func (d *ENS160Minimal) readReg16LE(reg byte) (uint16, error) {
	b, err := d.transport.WriteRead([]byte{reg}, 2)
	if err != nil {
		return 0, err
	}
	return uint16(b[0]) | uint16(b[1])<<8, nil
}

func (d *ENS160Minimal) readDeviceStatus() (byte, error) {
	b, err := d.readReg(regDeviceStatus, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *ENS160Minimal) waitForNewData() (byte, error) {
	deadline := time.Now().Add(time.Duration(ens160NewDataWaitMs) * time.Millisecond)
	for {
		s, err := d.readDeviceStatus()
		if err != nil {
			return 0, err
		}
		if s&0x02 != 0 {
			return s, nil
		}
		if time.Now().After(deadline) {
			return 0, fmt.Errorf("ENS160: NEWDAT not set within %d ms", ens160NewDataWaitMs)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// Status reads the VALIDITY_FLAG from DEVICE_STATUS.
//
// Returns 0=OK, 1=Warm-up, 2=Initial Start-up, 3=No valid output.
func (d *ENS160Minimal) Status() (int, error) {
	s, err := d.readDeviceStatus()
	if err != nil {
		return 0, err
	}
	return int(s>>2) & 0x03, nil
}

// ReadAirQuality polls for new data and returns AQI, TVOC, and eCO2.
//
// Returns (aqi (1-5), tvocPpb, eco2Ppm). Returns an error while
// VALIDITY_FLAG is non-zero.
func (d *ENS160Minimal) ReadAirQuality() (int, float32, float32, error) {
	s, err := d.waitForNewData()
	if err != nil {
		return 0, 0, 0, err
	}
	validity := int(s>>2) & 0x03
	if validity != ValidityOK {
		return 0, 0, 0, fmt.Errorf("ENS160: data not valid (VALIDITY_FLAG=%d)", validity)
	}
	// Burst-read 5 bytes from 0x21: AQI, TVOC[0:2], eCO2[0:2].
	b, err := d.readReg(regDataAQI, 5)
	if err != nil {
		return 0, 0, 0, err
	}
	aqi := int(b[0]) & 0x07
	tvoc := float32(uint16(b[1]) | uint16(b[2])<<8)
	eco2 := float32(uint16(b[3]) | uint16(b[4])<<8)
	return aqi, tvoc, eco2, nil
}

// ENS160Full is the ENS160 digital multi-gas sensor driver — full
// interface. Extends ENS160Minimal with external T/RH compensation,
// individual gas readings, raw sensor resistance, firmware version
// query, interrupt configuration, and sleep/wake control.
//
// Embeds ENS160Minimal to inherit Status, ReadAirQuality, and the
// constructor.
type ENS160Full struct {
	*ENS160Minimal
}

// NewENS160Full creates a new ENS160Full with the same initialization as
// NewENS160Minimal.
func NewENS160Full(t transport.Transport) (*ENS160Full, error) {
	m, err := NewENS160Minimal(t)
	if err != nil {
		return nil, err
	}
	return &ENS160Full{ENS160Minimal: m}, nil
}

// SetCompensation writes external temperature and humidity values for
// the sensor's compensation routines.
//
// tempCelsius: ambient temperature in °C.
// rhPercent: relative humidity in % (0-100).
func (d *ENS160Full) SetCompensation(tempCelsius, rhPercent float32) error {
	tempRaw := uint16(math.Round((float64(tempCelsius) + 273.15) * 64.0))
	rhRaw := uint16(math.Round(float64(rhPercent) * 512.0))
	if err := d.writeReg16LE(regTEMP_IN, tempRaw); err != nil {
		return err
	}
	return d.writeReg16LE(regRH_IN, rhRaw)
}

// ReadTVOC reads the TVOC concentration in ppb.
func (d *ENS160Full) ReadTVOC() (float32, error) {
	if _, err := d.waitForNewData(); err != nil {
		return 0, err
	}
	v, err := d.readReg16LE(regDataTVOC)
	if err != nil {
		return 0, err
	}
	return float32(v), nil
}

// ReadECO2 reads the equivalent CO2 concentration in ppm.
func (d *ENS160Full) ReadECO2() (float32, error) {
	if _, err := d.waitForNewData(); err != nil {
		return 0, err
	}
	v, err := d.readReg16LE(regDataECO2)
	if err != nil {
		return 0, err
	}
	return float32(v), nil
}

// ReadAQI reads the Air Quality Index on the UBA scale (1-5).
func (d *ENS160Full) ReadAQI() (int, error) {
	if _, err := d.waitForNewData(); err != nil {
		return 0, err
	}
	b, err := d.readReg(regDataAQI, 1)
	if err != nil {
		return 0, err
	}
	return int(b[0]) & 0x07, nil
}

// ReadEthanol reads the ethanol concentration estimate in ppb (alias of
// DATA_TVOC at 0x22).
func (d *ENS160Full) ReadEthanol() (float32, error) {
	if _, err := d.waitForNewData(); err != nil {
		return 0, err
	}
	v, err := d.readReg16LE(regDataTVOC)
	if err != nil {
		return 0, err
	}
	return float32(v), nil
}

// ReadRawResistance reads a raw sensor resistance value from GPR_READ.
//
// sensor: 1 or 4 (the four MOX sensing elements).
func (d *ENS160Full) ReadRawResistance(sensor int) (float32, error) {
	var offset int
	switch sensor {
	case 1:
		offset = 0
	case 4:
		offset = 6
	default:
		return 0, fmt.Errorf("ENS160 ReadRawResistance: sensor must be 1 or 4, got %d", sensor)
	}
	v, err := d.readReg16LE(byte(regGPR_READ + offset))
	if err != nil {
		return 0, err
	}
	return float32(math.Pow(2.0, float64(v)/2048.0)), nil
}

// ReadCompensationActuals reads the temperature and humidity values
// used by the sensor.
//
// Returns (tempCelsius, rhPercent).
func (d *ENS160Full) ReadCompensationActuals() (float32, float32, error) {
	b, err := d.readReg(regDataT, 4)
	if err != nil {
		return 0, 0, err
	}
	tempRaw := uint16(b[0]) | uint16(b[1])<<8
	rhRaw := uint16(b[2]) | uint16(b[3])<<8
	return float32(tempRaw)/64.0 - 273.15, float32(rhRaw) / 512.0, nil
}

// GetFirmwareVersion queries the firmware version. Requires IDLE mode;
// the function switches the sensor to IDLE, issues GET_APPVER, reads
// GPR_READ, and returns to STANDARD mode.
//
// Returns (major, minor, release).
func (d *ENS160Full) GetFirmwareVersion() (int, int, int, error) {
	if err := d.writeReg(regOPMODE, opModeIdle); err != nil {
		return 0, 0, 0, err
	}
	time.Sleep(ens160StepDelay)
	if err := d.writeReg(regCOMMAND, cmdAppVer); err != nil {
		return 0, 0, 0, err
	}
	time.Sleep(ens160StepDelay)
	b, err := d.readReg(byte(regGPR_READ+4), 3)
	if err != nil {
		return 0, 0, 0, err
	}
	if err := d.writeReg(regOPMODE, opModeStandard); err != nil {
		return 0, 0, 0, err
	}
	return int(b[0]), int(b[1]), int(b[2]), nil
}

// ConfigureInterrupt sets the INTn interrupt pin configuration.
func (d *ENS160Full) ConfigureInterrupt(enabled, activeHigh, pushPull, onData, onGPR bool) error {
	var v uint8
	if enabled {
		v |= 0x01
	}
	if onData {
		v |= 0x02
	}
	if onGPR {
		v |= 0x08
	}
	if pushPull {
		v |= 0x20
	}
	if activeHigh {
		v |= 0x40
	}
	return d.writeReg(regCONFIG, v)
}

// Sleep puts the sensor into DEEP SLEEP for power saving.
func (d *ENS160Full) Sleep() error {
	return d.writeReg(regOPMODE, opModeDeepSleep)
}

// Wake returns the sensor to STANDARD gas-sensing mode.
func (d *ENS160Full) Wake() error {
	if err := d.writeReg(regOPMODE, opModeIdle); err != nil {
		return err
	}
	time.Sleep(ens160StepDelay)
	return d.writeReg(regOPMODE, opModeStandard)
}
