// LPS22DF absolute pressure and temperature sensor (STMicroelectronics).
//
// Communicates over I²C (address 0x5C or 0x5D) or SPI. The chip has built-in
// factory calibration; no user calibration read step is required. Pressure
// is 24-bit two's complement at 4096 LSB/hPa; temperature is 16-bit two's
// complement at 100 LSB/°C.
//
// ## Constants
//
// Output data rate: LPS22DFODRPowerDown through LPS22DFODR200Hz
// Averaging filter: LPS22DFAvg4 through LPS22DFAvg512
// FIFO modes: LPS22DFFifoBypass through LPS22DFFifoContToFifo
package pressure

import (
	"fmt"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// LPS22DF register addresses.
const (
	lps22dfRegInterruptCfg uint8 = 0x0B
	lps22dfRegThsPL       uint8 = 0x0C
	lps22dfRegThsPH       uint8 = 0x0D
	lps22dfRegWhoAmI      uint8 = 0x0F
	lps22dfRegCtrlReg1    uint8 = 0x10
	lps22dfRegCtrlReg2    uint8 = 0x11
	lps22dfRegCtrlReg3    uint8 = 0x12
	lps22dfRegCtrlReg4    uint8 = 0x13
	lps22dfRegFifoCtrl    uint8 = 0x14
	lps22dfRegFifoWtm     uint8 = 0x15
	lps22dfRegRefPL       uint8 = 0x16
	lps22dfRegRefPH       uint8 = 0x17
	lps22dfRegRpdsL       uint8 = 0x1A
	lps22dfRegRpdsH       uint8 = 0x1B
	lps22dfRegIntSource   uint8 = 0x24
	lps22dfRegFifoStatus1 uint8 = 0x25
	lps22dfRegStatus      uint8 = 0x27
	lps22dfRegPressOutXL  uint8 = 0x28
	lps22dfRegTempOutL    uint8 = 0x2B
	lps22dfRegFifoPressXL uint8 = 0x78
)

// LPS22DF expected chip ID.
const lps22dfChipID uint8 = 0xB4

// Status flag: pressure data available.
const lps22dfStatusPDa uint8 = 0x01

// LPS22DF output data rates.
const (
	// LPS22DFODRPowerDown disables continuous conversion (one-shot via ONESHOT bit).
	LPS22DFODRPowerDown uint8 = 0
	// LPS22DFODR1Hz is 1 Hz.
	LPS22DFODR1Hz uint8 = 1
	// LPS22DFODR4Hz is 4 Hz.
	LPS22DFODR4Hz uint8 = 2
	// LPS22DFODR10Hz is 10 Hz.
	LPS22DFODR10Hz uint8 = 3
	// LPS22DFODR25Hz is 25 Hz.
	LPS22DFODR25Hz uint8 = 4
	// LPS22DFODR50Hz is 50 Hz.
	LPS22DFODR50Hz uint8 = 5
	// LPS22DFODR75Hz is 75 Hz.
	LPS22DFODR75Hz uint8 = 6
	// LPS22DFODR100Hz is 100 Hz.
	LPS22DFODR100Hz uint8 = 7
	// LPS22DFODR200Hz is 200 Hz.
	LPS22DFODR200Hz uint8 = 8
)

// LPS22DF averaging filter options.
const (
	// LPS22DFAvg4 averages 4 samples.
	LPS22DFAvg4 uint8 = 0
	// LPS22DFAvg8 averages 8 samples.
	LPS22DFAvg8 uint8 = 1
	// LPS22DFAvg16 averages 16 samples.
	LPS22DFAvg16 uint8 = 2
	// LPS22DFAvg32 averages 32 samples.
	LPS22DFAvg32 uint8 = 3
	// LPS22DFAvg64 averages 64 samples.
	LPS22DFAvg64 uint8 = 4
	// LPS22DFAvg128 averages 128 samples.
	LPS22DFAvg128 uint8 = 5
	// LPS22DFAvg512 averages 512 samples.
	LPS22DFAvg512 uint8 = 7
)

// LPS22DF FIFO modes.
const (
	// LPS22DFFifoBypass disables the FIFO.
	LPS22DFFifoBypass uint8 = 0
	// LPS22DFFifoFifo uses standard FIFO mode.
	LPS22DFFifoFifo uint8 = 1
	// LPS22DFFifoContinuous uses continuous (dynamic-stream) mode.
	LPS22DFFifoContinuous uint8 = 2
	// LPS22DFFifoBypassToFifo triggers a one-shot into FIFO.
	LPS22DFFifoBypassToFifo uint8 = 3
	// LPS22DFFifoBypassToContinuous triggers a one-shot into continuous mode.
	LPS22DFFifoBypassToContinuous uint8 = 4
	// LPS22DFFifoContToFifo triggers continuous into FIFO mode.
	LPS22DFFifoContToFifo uint8 = 5
)

// LPS22DFInterruptSource is the decoded INT_SOURCE register layout.
type LPS22DFInterruptSource struct {
	BootOn bool
	IA     bool
	PH     bool
	PL     bool
}

// LPS22DFMinimal is the LPS22DF combined pressure + temperature driver —
// minimal interface.
//
// The default configuration baked in is: ODR=10 Hz, AVG=4 samples, BDU on,
// low-pass filter off, FIFO bypass mode. Pressure returns Pa; temperature
// returns °C.
type LPS22DFMinimal struct {
	connection connection.Connection
	spi        bool
}

// NewLPS22DFMinimal creates an LPS22DFMinimal, verifies chip ID, and applies
// the default configuration.
//
// connection must be a configured I²C or SPI connection bound to the chip
// (I²C address 0x5C/0x5D, or an SPI chip-select). Pass spi=true for SPI —
// per the datasheet's register-address protocol, write addresses have bit 7
// cleared (reg & 0x7F); reads stay unmasked.
func NewLPS22DFMinimal(t connection.Connection, spi bool) (*LPS22DFMinimal, error) {
	buf, err := t.WriteRead([]byte{lps22dfRegWhoAmI}, 1)
	if err != nil {
		return nil, err
	}
	if buf[0] != lps22dfChipID {
		return nil, fmt.Errorf("LPS22DF not found: WHO_AM_I expected 0x%02X, got 0x%02X", lps22dfChipID, buf[0])
	}
	d := &LPS22DFMinimal{connection: t, spi: spi}
	if err := d.writeReg(lps22dfRegCtrlReg2, 0x04); err != nil { // SWRESET=1
		return nil, err
	}
	time.Sleep(time.Millisecond)
	if err := d.writeReg(lps22dfRegCtrlReg1, (LPS22DFODR10Hz<<3)|LPS22DFAvg4); err != nil {
		return nil, err
	}
	if err := d.writeReg(lps22dfRegCtrlReg2, 0x08); err != nil { // BDU=1
		return nil, err
	}
	return d, nil
}

func (d *LPS22DFMinimal) writeReg(reg, val uint8) error {
	addr := reg
	if d.spi {
		addr &= 0x7F
	}
	return d.connection.Write([]byte{addr, val})
}

func (d *LPS22DFMinimal) readReg8(reg uint8) (uint8, error) {
	b, err := d.connection.WriteRead([]byte{reg}, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *LPS22DFMinimal) readRegBytes(reg uint8, n int) ([]byte, error) {
	return d.connection.WriteRead([]byte{reg}, n)
}

func (d *LPS22DFMinimal) waitPDa() error {
	for {
		v, err := d.readReg8(lps22dfRegStatus)
		if err != nil {
			return err
		}
		if v&lps22dfStatusPDa != 0 {
			return nil
		}
		time.Sleep(time.Millisecond)
	}
}

// Pressure polls STATUS.P_DA then burst-reads PRESS_OUT_XL..H, sign-extends
// the 24-bit two's complement value and converts to pascals (4096 LSB/hPa).
//
// Returns pressure in Pa.
func (d *LPS22DFMinimal) Pressure() (float32, error) {
	if err := d.waitPDa(); err != nil {
		return 0, err
	}
	raw, err := d.readRegBytes(lps22dfRegPressOutXL, 3)
	if err != nil {
		return 0, err
	}
	value := int32(raw[0]) | int32(raw[1])<<8 | int32(raw[2])<<16
	if value&0x800000 != 0 {
		value -= 0x1000000
	}
	return float32(value) / 4096.0 * 100.0, nil
}

// Temperature reads TEMP_OUT_L..H, sign-extends the 16-bit two's complement
// value and converts to °C (100 LSB/°C).
//
// Returns temperature in °C.
func (d *LPS22DFMinimal) Temperature() (float32, error) {
	raw, err := d.readRegBytes(lps22dfRegTempOutL, 2)
	if err != nil {
		return 0, err
	}
	value := int16(uint16(raw[0]) | uint16(raw[1])<<8)
	return float32(value) / 100.0, nil
}

// WhoAmI reads the chip ID register (expected 0xB4).
func (d *LPS22DFMinimal) WhoAmI() (uint8, error) {
	return d.readReg8(lps22dfRegWhoAmI)
}

// LPS22DFFull is the LPS22DF combined pressure + temperature driver — full
// interface. Extends LPS22DFMinimal with configuration, threshold/offset
// calibration, FIFO, interrupts, and AUTOZERO/AUTOREFP.
type LPS22DFFull struct {
	*LPS22DFMinimal
}

// NewLPS22DFFull creates an LPS22DFFull and applies the default configuration.
func NewLPS22DFFull(t connection.Connection, spi bool) (*LPS22DFFull, error) {
	m, err := NewLPS22DFMinimal(t, spi)
	if err != nil {
		return nil, err
	}
	return &LPS22DFFull{LPS22DFMinimal: m}, nil
}

// Configure writes CTRL_REG1 and CTRL_REG2.
//
// Parameters:
//   - odr      — output data rate (LPS22DFODRPowerDown=0..LPS22DFODR200Hz=8)
//   - avg      — averaging filter (LPS22DFAvg4..LPS22DFAvg512)
//   - enLpfp   — enable low-pass filter on pressure output
//   - lfpfCfg  — 0=ODR/4 cutoff, 1=ODR/9 cutoff
//   - bdu      — block data update (recommended on)
func (d *LPS22DFFull) Configure(odr, avg uint8, enLpfp bool, lfpfCfg uint8, bdu bool) error {
	ctrl1 := (odr&0x0F)<<3 | (avg & 0x07)
	var ctrl2 uint8
	if enLpfp {
		ctrl2 |= 0x10
	}
	if lfpfCfg != 0 {
		ctrl2 |= 0x20
	}
	if bdu {
		ctrl2 |= 0x08
	}
	if err := d.writeReg(lps22dfRegCtrlReg1, ctrl1); err != nil {
		return err
	}
	return d.writeReg(lps22dfRegCtrlReg2, ctrl2)
}

// OneShot triggers a single measurement in power-down mode; blocks until P_DA.
func (d *LPS22DFFull) OneShot() error {
	if err := d.writeReg(lps22dfRegCtrlReg1, 0x00); err != nil {
		return err
	}
	if err := d.writeReg(lps22dfRegCtrlReg2, 0x08|0x01); err != nil {
		return err
	}
	return d.waitPDa()
}

// Altitude computes altitude above sea level from the current pressure.
//
// seaLevelPa is the reference pressure in pascals (default 101325).
//
// Returns altitude in metres.
func (d *LPS22DFFull) Altitude(seaLevelPa float32) (float32, error) {
	p, err := d.Pressure()
	if err != nil {
		return 0, err
	}
	ratio := float64(p / seaLevelPa)
	return float32(44330.0 * (1.0 - math.Pow(ratio, 1.0/5.255))), nil
}

// SoftwareReset asserts SWRESET and waits for self-clear.
func (d *LPS22DFFull) SoftwareReset() error {
	if err := d.writeReg(lps22dfRegCtrlReg2, 0x04); err != nil {
		return err
	}
	time.Sleep(time.Millisecond)
	return nil
}

// SetPressureOffset writes a one-point calibration offset (signed; persisted in NVM).
func (d *LPS22DFFull) SetPressureOffset(offsetPa float32) error {
	offsetHpa := float64(offsetPa) / 100.0
	raw := int32(math.Round(offsetHpa * 4096.0))
	if raw < 0 {
		raw += 0x10000
	}
	if err := d.writeReg(lps22dfRegRpdsL, uint8(raw)); err != nil {
		return err
	}
	return d.writeReg(lps22dfRegRpdsH, uint8(raw>>8))
}

// SetPressureThreshold writes a 15-bit unsigned pressure threshold.
func (d *LPS22DFFull) SetPressureThreshold(thresholdPa float32) error {
	thresholdHpa := float64(thresholdPa) / 100.0
	raw := uint16(math.Round(thresholdHpa * 16.0)) & 0x7FFF
	if err := d.writeReg(lps22dfRegThsPL, uint8(raw)); err != nil {
		return err
	}
	return d.writeReg(lps22dfRegThsPH, uint8(raw>>8))
}

// ConfigureInterrupt configures the INT pin and routing.
func (d *LPS22DFFull) ConfigureInterrupt(intHL, ppOd, drdy, drdyPls, intEn, intFWtm, intFFull, intFOvr bool) error {
	var ctrl3 uint8 = 0x01 // IF_ADD_INC=1
	if intHL {
		ctrl3 |= 0x08
	}
	if ppOd {
		ctrl3 |= 0x02
	}
	var ctrl4 uint8
	if drdyPls {
		ctrl4 |= 0x40
	}
	if drdy {
		ctrl4 |= 0x20
	}
	if intEn {
		ctrl4 |= 0x10
	}
	if intFFull {
		ctrl4 |= 0x04
	}
	if intFWtm {
		ctrl4 |= 0x02
	}
	if intFOvr {
		ctrl4 |= 0x01
	}
	if err := d.writeReg(lps22dfRegCtrlReg3, ctrl3); err != nil {
		return err
	}
	return d.writeReg(lps22dfRegCtrlReg4, ctrl4)
}

// ConfigurePressureEvent configures pressure-event interrupts.
func (d *LPS22DFFull) ConfigurePressureEvent(phe, ple, lir bool) error {
	var cfg uint8
	if phe {
		cfg |= 0x01
	}
	if ple {
		cfg |= 0x02
	}
	if lir {
		cfg |= 0x04
	}
	return d.writeReg(lps22dfRegInterruptCfg, cfg)
}

// Autozero captures the current pressure as the AUTOZERO reference.
func (d *LPS22DFFull) Autozero() error {
	return d.writeReg(lps22dfRegInterruptCfg, 0x20)
}

// Autorefp captures the current pressure in REF_P for use as a comparator.
func (d *LPS22DFFull) Autorefp() error {
	return d.writeReg(lps22dfRegInterruptCfg, 0x80)
}

// ResetReference resets AUTOZERO and AUTOREFP, returning PRESS_OUT to absolute.
func (d *LPS22DFFull) ResetReference() error {
	return d.writeReg(lps22dfRegInterruptCfg, 0x50)
}

// ReferencePressure reads the stored AUTOZERO/AUTOREFP reference pressure in Pa.
func (d *LPS22DFFull) ReferencePressure() (float32, error) {
	raw, err := d.readRegBytes(lps22dfRegRefPL, 2)
	if err != nil {
		return 0, err
	}
	value := int16(uint16(raw[0]) | uint16(raw[1])<<8)
	return float32(value) / 4096.0 * 100.0, nil
}

// SetFifoMode sets the FIFO mode.
func (d *LPS22DFFull) SetFifoMode(mode uint8) error {
	var trig, fm uint8
	switch mode {
	case 0:
		trig, fm = 0, 0
	case 1:
		trig, fm = 0, 1
	case 2:
		trig, fm = 0, 2
	case 3:
		trig, fm = 1, 1
	case 4:
		trig, fm = 1, 2
	default:
		trig, fm = 1, 3
	}
	return d.writeReg(lps22dfRegFifoCtrl, (trig<<2)|(fm&0x03))
}

// SetFifoWatermark sets the FIFO watermark level (0..127).
func (d *LPS22DFFull) SetFifoWatermark(level uint8) error {
	return d.writeReg(lps22dfRegFifoWtm, level&0x7F)
}

// FifoSampleCount reads the FIFO sample count (0..127).
func (d *LPS22DFFull) FifoSampleCount() (uint8, error) {
	return d.readReg8(lps22dfRegFifoStatus1)
}

// ReadFifo reads every available FIFO sample into out (one float per sample in Pa).
//
// Returns the number of samples written.
func (d *LPS22DFFull) ReadFifo(out []float32) (uint8, error) {
	count, err := d.FifoSampleCount()
	if err != nil {
		return 0, err
	}
	if int(count) > len(out) {
		count = uint8(len(out))
	}
	if count == 0 {
		return 0, nil
	}
	raw, err := d.readRegBytes(lps22dfRegFifoPressXL, int(count)*3)
	if err != nil {
		return 0, err
	}
	for i := uint8(0); i < count; i++ {
		base := int(i) * 3
		value := int32(raw[base]) | int32(raw[base+1])<<8 | int32(raw[base+2])<<16
		if value&0x800000 != 0 {
			value -= 0x1000000
		}
		out[i] = float32(value) / 4096.0 * 100.0
	}
	return count, nil
}

// InterruptSource reads and clears the INT_SOURCE register.
func (d *LPS22DFFull) InterruptSource() (LPS22DFInterruptSource, error) {
	v, err := d.readReg8(lps22dfRegIntSource)
	if err != nil {
		return LPS22DFInterruptSource{}, err
	}
	return LPS22DFInterruptSource{
		BootOn: v&0x80 != 0,
		IA:     v&0x04 != 0,
		PH:     v&0x01 != 0,
		PL:     v&0x02 != 0,
	}, nil
}