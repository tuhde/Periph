// Package pressure contains drivers for standalone pressure sensors.
package pressure

import (
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// BMP581 register addresses.
const (
	bmp581RegChipID       uint8 = 0x01
	bmp581RegRevID        uint8 = 0x02
	bmp581RegIntSource    uint8 = 0x15
	bmp581RegIntConfig    uint8 = 0x14
	bmp581RegFifoSel      uint8 = 0x18
	bmp581RegFifoConfig   uint8 = 0x16
	bmp581RegFifoCount    uint8 = 0x17
	bmp581RegFifoData     uint8 = 0x29
	bmp581RegTempXLSB     uint8 = 0x1D
	bmp581RegPressXLSB    uint8 = 0x20
	bmp581RegIntStatus    uint8 = 0x27
	bmp581RegStatus       uint8 = 0x28
	bmp581RegDspConfig    uint8 = 0x30
	bmp581RegDspIIR       uint8 = 0x31
	bmp581RegOORThrPLSB   uint8 = 0x32
	bmp581RegOORThrPMSB   uint8 = 0x33
	bmp581RegOORRange     uint8 = 0x34
	bmp581RegOORConfig    uint8 = 0x35
	bmp581RegOSRConfig    uint8 = 0x36
	bmp581RegODRConfig    uint8 = 0x37
	bmp581RegOSREff       uint8 = 0x38
	bmp581RegNVMAddr      uint8 = 0x2B
	bmp581RegNVMDataLSB   uint8 = 0x2C
	bmp581RegNVMDataMSB   uint8 = 0x2D
	bmp581RegCmd          uint8 = 0x7E
)

// BMP581 expected chip ID.
const bmp581ChipID uint8 = 0x50

// BMP581 soft-reset command byte.
const bmp581ResetCmd uint8 = 0xB6

// BMP581 status register bits.
const (
	bmp581StatusNVMRdy uint8 = 0x02
	bmp581StatusNVMErr uint8 = 0x04
)

// BMP581 INT_STATUS bit.
const bmp581IntStatusDRDY uint8 = 0x01

// BMP581 oversampling modes.
const (
	// BMP581OSR1X is ×1 oversampling.
	BMP581OSR1X uint8 = 0
	// BMP581OSR2X is ×2 oversampling.
	BMP581OSR2X uint8 = 1
	// BMP581OSR4X is ×4 oversampling.
	BMP581OSR4X uint8 = 2
	// BMP581OSR8X is ×8 oversampling.
	BMP581OSR8X uint8 = 3
	// BMP581OSR16X is ×16 oversampling.
	BMP581OSR16X uint8 = 4
	// BMP581OSR32X is ×32 oversampling.
	BMP581OSR32X uint8 = 5
	// BMP581OSR64X is ×64 oversampling.
	BMP581OSR64X uint8 = 6
	// BMP581OSR128X is ×128 oversampling.
	BMP581OSR128X uint8 = 7
)

// BMP581 power modes.
const (
	// BMP581ModeStandby is the lowest-power state.
	BMP581ModeStandby uint8 = 0
	// BMP581ModeNormal is ODR-driven duty-cycled autonomous mode.
	BMP581ModeNormal uint8 = 1
	// BMP581ModeForced triggers a single-shot conversion, returns to standby.
	BMP581ModeForced uint8 = 2
	// BMP581ModeContinuous is back-to-back conversions with no standby phase.
	BMP581ModeContinuous uint8 = 3
)

// BMP581 IIR filter coefficients.
const (
	// BMP581IIRBypass disables the IIR filter.
	BMP581IIRBypass uint8 = 0
	// BMP581IIRCoeff1 selects coefficient 1.
	BMP581IIRCoeff1 uint8 = 1
	// BMP581IIRCoeff3 selects coefficient 3.
	BMP581IIRCoeff3 uint8 = 2
	// BMP581IIRCoeff7 selects coefficient 7.
	BMP581IIRCoeff7 uint8 = 3
	// BMP581IIRCoeff15 selects coefficient 15.
	BMP581IIRCoeff15 uint8 = 4
	// BMP581IIRCoeff31 selects coefficient 31.
	BMP581IIRCoeff31 uint8 = 5
	// BMP581IIRCoeff63 selects coefficient 63.
	BMP581IIRCoeff63 uint8 = 6
	// BMP581IIRCoeff127 selects coefficient 127.
	BMP581IIRCoeff127 uint8 = 7
)

// BMP581 FIFO frame selection.
const (
	// BMP581FIFODisabled disables the FIFO.
	BMP581FIFODisabled uint8 = 0
	// BMP581FIFOTemp stores temperature only.
	BMP581FIFOTemp uint8 = 1
	// BMP581FIFOPress stores pressure only.
	BMP581FIFOPress uint8 = 2
	// BMP581FIFOBoth stores pressure + temperature.
	BMP581FIFOBoth uint8 = 3
)

// BMP581 FIFO mode.
const (
	// BMP581FIFOStream overwrites oldest frame when full.
	BMP581FIFOStream uint8 = 0
	// BMP581FIFOStopOnFull stops accepting new frames when full.
	BMP581FIFOStopOnFull uint8 = 1
)

// BMP581 INT_SOURCE bits.
const (
	// BMP581IntSourceDRDY is the data-ready interrupt.
	BMP581IntSourceDRDY uint8 = 0x01
	// BMP581IntSourceFIFOFull is the FIFO-full interrupt.
	BMP581IntSourceFIFOFull uint8 = 0x02
	// BMP581IntSourceFIFOThs is the FIFO threshold interrupt.
	BMP581IntSourceFIFOThs uint8 = 0x04
	// BMP581IntSourceOORP is the pressure out-of-range interrupt.
	BMP581IntSourceOORP uint8 = 0x08
)

// bmp581U24 unpacks three bytes in (XLSB, LSB, MSB) order into a signed 24-bit integer.
func bmp581U24(b []byte) int32 {
	raw := int32(b[2])<<16 | int32(b[1])<<8 | int32(b[0])
	if raw&0x800000 != 0 {
		raw -= 0x1000000
	}
	return raw
}

// BMP581Minimal is the BMP581 MEMS pressure + temperature driver — minimal interface.
//
// Default configuration baked in: NORMAL mode, ODR 1 Hz, press_en=1,
// osr_p=×1, osr_t=×1, IIR bypass, FIFO disabled, INT_SOURCE=0.
type BMP581Minimal struct {
	connection connection.Connection
	spi        bool

	odr      uint8
	pwrMode  uint8
	osrP     uint8
	osrT     uint8
	pressEn  bool
}

// NewBMP581Minimal creates a BMP581Minimal and runs the chip init sequence.
//
// connection must be a configured I²C or SPI connection bound to the device
// (I²C address 0x46/0x47, or an SPI chip-select). Pass spi=true for SPI -
// per the BMP581 SPI protocol, writes clear bit 7 of the register address
// (reg & 0x7F); the chip does NOT need a SPI-mode-0/3 dummy read because
// the I²C init sequence below handles the first-byte race without one.
func NewBMP581Minimal(t connection.Connection, spi bool) (*BMP581Minimal, error) {
	d := &BMP581Minimal{
		connection: t,
		spi:        spi,
		odr:        0x1C,
		pwrMode:    0x01,
		osrP:       0,
		osrT:       0,
		pressEn:    true,
	}
	if spi {
		_ = t.Write([]byte{bmp581RegChipID | 0x80})
		_, _ = t.Read(1)
	}
	if _, err := d.readReg8(bmp581RegChipID); err != nil {
		// Best effort; some buses don't reply at this stage.
		_ = err
	}
	for i := 0; i < 50; i++ {
		st, _ := d.readReg8(bmp581RegStatus)
		if (st&bmp581StatusNVMRdy) != 0 && (st&bmp581StatusNVMErr) == 0 {
			break
		}
		time.Sleep(2 * time.Millisecond)
	}
	_, _ = d.readReg8(bmp581RegIntStatus)
	if err := d.writeReg(bmp581RegCmd, bmp581ResetCmd); err != nil {
		// I²C reset write returns NACK (no ACK before chip resets); ignore.
		_ = err
	}
	time.Sleep(2 * time.Millisecond)
	for i := 0; i < 50; i++ {
		st, _ := d.readReg8(bmp581RegStatus)
		if (st&bmp581StatusNVMRdy) != 0 && (st&bmp581StatusNVMErr) == 0 {
			break
		}
		time.Sleep(2 * time.Millisecond)
	}
	_, _ = d.readReg8(bmp581RegIntStatus)
	if err := d.writeReg(bmp581RegOSRConfig, 0x40); err != nil {
		return nil, err
	}
	if err := d.writeReg(bmp581RegODRConfig, 0x71); err != nil {
		return nil, err
	}
	return d, nil
}

// writeReg writes a single byte to a register.
func (d *BMP581Minimal) writeReg(reg, val uint8) error {
	addr := reg
	if d.spi {
		addr &= 0x7F
	}
	return d.connection.Write([]byte{addr, val})
}

// readReg8 reads a single byte from a register.
func (d *BMP581Minimal) readReg8(reg uint8) (uint8, error) {
	b, err := d.connection.WriteRead([]byte{reg}, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// readRegBytes reads n bytes from a register into buf.
func (d *BMP581Minimal) readRegBytes(reg uint8, n int) ([]byte, error) {
	return d.connection.WriteRead([]byte{reg}, n)
}

// waitForced blocks until drdy is set, if the chip is in FORCED mode.
func (d *BMP581Minimal) waitForced() error {
	if d.pwrMode != BMP581ModeForced {
		return nil
	}
	for i := 0; i < 200; i++ {
		st, _ := d.readReg8(bmp581RegIntStatus)
		if st&bmp581IntStatusDRDY != 0 {
			return nil
		}
		time.Sleep(5 * time.Millisecond)
	}
	return nil
}

// Pressure reads calibrated pressure.
//
// Returns pressure in Pa.
func (d *BMP581Minimal) Pressure() (float32, error) {
	if err := d.waitForced(); err != nil {
		return 0, err
	}
	buf, err := d.readRegBytes(bmp581RegPressXLSB, 3)
	if err != nil {
		return 0, err
	}
	return float32(bmp581U24(buf)) / 64.0, nil
}

// Temperature reads calibrated temperature.
//
// Returns temperature in °C.
func (d *BMP581Minimal) Temperature() (float32, error) {
	if err := d.waitForced(); err != nil {
		return 0, err
	}
	buf, err := d.readRegBytes(bmp581RegTempXLSB, 3)
	if err != nil {
		return 0, err
	}
	return float32(bmp581U24(buf)) / 65536.0, nil
}

// Both reads pressure and temperature atomically in a single 6-byte burst.
//
// Returns (pressure_Pa, temperature_C).
func (d *BMP581Minimal) Both() (float32, float32, error) {
	if err := d.waitForced(); err != nil {
		return 0, 0, err
	}
	buf, err := d.readRegBytes(bmp581RegTempXLSB, 6)
	if err != nil {
		return 0, 0, err
	}
	return float32(bmp581U24(buf[3:])) / 64.0, float32(bmp581U24(buf[:3])) / 65536.0, nil
}

// BMP581Full is the BMP581 MEMS pressure + temperature driver — full interface.
// Extends BMP581Minimal with configuration, FIFO, interrupts, OOR detection,
// and NVM access.
type BMP581Full struct {
	*BMP581Minimal
}

// NewBMP581Full creates a BMP581Full and runs the chip init sequence.
func NewBMP581Full(t connection.Connection, spi bool) (*BMP581Full, error) {
	m, err := NewBMP581Minimal(t, spi)
	if err != nil {
		return nil, err
	}
	return &BMP581Full{BMP581Minimal: m}, nil
}

// Configure writes OSR_CONFIG and ODR_CONFIG atomically.
//
// Parameters:
//   - odr     — ODR field 0x00-0x1F (default 0x1C = 1 Hz).
//   - osrP    — Pressure oversampling 0-7 (default 0 = x1).
//   - osrT    — Temperature oversampling 0-7 (default 0 = x1).
//   - pressEn — Whether to enable pressure measurements.
func (d *BMP581Full) Configure(odr, osrP, osrT uint8, pressEn bool) error {
	d.odr = odr
	d.osrP = osrP
	d.osrT = osrT
	d.pressEn = pressEn
	var osrByte uint8
	if pressEn {
		osrByte = 0x40
	}
	osrByte |= (osrP & 0x7) << 3
	osrByte |= osrT & 0x7
	if err := d.writeReg(bmp581RegOSRConfig, osrByte); err != nil {
		return err
	}
	odrByte := (d.odr&0x1F)<<2 | (d.pwrMode & 0x3)
	return d.writeReg(bmp581RegODRConfig, odrByte)
}

// SetMode sets the power mode (preserves the current ODR setting).
func (d *BMP581Full) SetMode(mode uint8) error {
	d.pwrMode = mode
	odrByte := (d.odr&0x1F)<<2 | (mode & 0x3)
	return d.writeReg(bmp581RegODRConfig, odrByte)
}

// Forced triggers a single FORCED measurement, waits for completion, and
// returns the readings.
func (d *BMP581Full) Forced() (float32, float32, error) {
	prev := d.pwrMode
	if prev != BMP581ModeForced {
		if err := d.SetMode(BMP581ModeForced); err != nil {
			return 0, 0, err
		}
	}
	for i := 0; i < 400; i++ {
		st, _ := d.readReg8(bmp581RegIntStatus)
		if st&bmp581IntStatusDRDY != 0 {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	return d.Both()
}

// Altitude computes altitude above sea level from the current pressure.
//
// seaLevelPa is the reference pressure (default 101325 Pa).
//
// Returns altitude in metres.
func (d *BMP581Full) Altitude(seaLevelPa float32) (float32, error) {
	p, err := d.Pressure()
	if err != nil {
		return 0, err
	}
	if p <= 0 {
		return 0, nil
	}
	ratio := float64(p / seaLevelPa)
	return float32(44330.0 * (1.0 - math.Pow(ratio, 1.0/5.255))), nil
}

// SoftwareReset issues a soft reset (write 0xB6 to CMD) and re-runs the init.
func (d *BMP581Full) SoftwareReset() error {
	_ = d.writeReg(bmp581RegCmd, bmp581ResetCmd) // NACK expected on I²C
	time.Sleep(2 * time.Millisecond)
	prevODR := d.odr
	prevMode := d.pwrMode
	prevOSRP := d.osrP
	prevOSRT := d.osrT
	prevPE := d.pressEn
	_, _ = d.readReg8(bmp581RegStatus)
	for i := 0; i < 50; i++ {
		st, _ := d.readReg8(bmp581RegStatus)
		if (st&bmp581StatusNVMRdy) != 0 && (st&bmp581StatusNVMErr) == 0 {
			break
		}
		time.Sleep(2 * time.Millisecond)
	}
	_, _ = d.readReg8(bmp581RegIntStatus)
	if err := d.writeReg(bmp581RegOSRConfig, 0x40); err != nil {
		return err
	}
	if err := d.writeReg(bmp581RegODRConfig, 0x71); err != nil {
		return err
	}
	d.odr = prevODR
	d.pwrMode = prevMode
	d.osrP = prevOSRP
	d.osrT = prevOSRT
	d.pressEn = prevPE
	return d.Configure(prevODR, prevOSRP, prevOSRT, prevPE)
}

// ChipID reads the chip ID register at 0x01. Expect 0x50 for a BMP581.
func (d *BMP581Full) ChipID() (uint8, error) {
	return d.readReg8(bmp581RegChipID)
}

// RevID reads the ASIC revision register at 0x02.
func (d *BMP581Full) RevID() (uint8, error) {
	return d.readReg8(bmp581RegRevID)
}

// Status reads the STATUS register at 0x28.
func (d *BMP581Full) Status() (uint8, error) {
	return d.readReg8(bmp581RegStatus)
}

// InterruptStatus reads INT_STATUS at 0x27 (clear-on-read).
func (d *BMP581Full) InterruptStatus() (uint8, error) {
	return d.readReg8(bmp581RegIntStatus)
}

// DataReady reads INT_STATUS and returns the drdy bit. Clear-on-read.
func (d *BMP581Full) DataReady() (bool, error) {
	st, err := d.InterruptStatus()
	if err != nil {
		return false, err
	}
	return st&bmp581IntStatusDRDY != 0, nil
}

// ConfigureInterrupt writes INT_CONFIG: latching, polarity, drive mode, pin enable.
func (d *BMP581Full) ConfigureInterrupt(mode, polarity uint8, openDrain, enable bool) error {
	var val uint8
	if enable {
		val |= 0x08
	}
	if openDrain {
		val |= 0x04
	}
	if polarity != 0 {
		val |= 0x02
	}
	if mode != 0 {
		val |= 0x01
	}
	return d.writeReg(bmp581RegIntConfig, val)
}

func (d *BMP581Full) setIntSource(source uint8, enable bool) error {
	cur, err := d.readReg8(bmp581RegIntSource)
	if err != nil {
		return err
	}
	var next uint8
	if enable {
		next = cur | source
	} else {
		next = cur &^ source
	}
	return d.writeReg(bmp581RegIntSource, next)
}

// EnableDRDYInterrupt enables or disables the data-ready interrupt source.
func (d *BMP581Full) EnableDRDYInterrupt(enable bool) error {
	return d.setIntSource(BMP581IntSourceDRDY, enable)
}

// EnableFIFOInterrupt enables or disables FIFO threshold and FIFO-full interrupt sources.
func (d *BMP581Full) EnableFIFOInterrupt(threshold, full bool) error {
	cur, err := d.readReg8(bmp581RegIntSource)
	if err != nil {
		return err
	}
	next := cur &^ (BMP581IntSourceFIFOFull | BMP581IntSourceFIFOThs)
	if threshold {
		next |= BMP581IntSourceFIFOThs
	}
	if full {
		next |= BMP581IntSourceFIFOFull
	}
	return d.writeReg(bmp581RegIntSource, next)
}

// EnableOORInterrupt enables or disables the pressure out-of-range interrupt source.
func (d *BMP581Full) EnableOORInterrupt(enable bool) error {
	return d.setIntSource(BMP581IntSourceOORP, enable)
}

// SetIIRFilter sets the IIR filter coefficients for pressure and temperature,
// and configures DSP_CONFIG to route post-IIR data to the output registers.
func (d *BMP581Full) SetIIRFilter(coeffP, coeffT uint8) error {
	dsp, err := d.readReg8(bmp581RegDspConfig)
	if err != nil {
		return err
	}
	dsp |= 0x28
	if err := d.writeReg(bmp581RegDspConfig, dsp); err != nil {
		return err
	}
	iir := (coeffP&0x7)<<3 | (coeffT & 0x7)
	return d.writeReg(bmp581RegDspIIR, iir)
}

// ConfigureFIFO configures FIFO source, mode, and threshold. Must be called in STANDBY mode.
func (d *BMP581Full) ConfigureFIFO(frameSel, mode, threshold uint8) error {
	prev := d.pwrMode
	if prev != BMP581ModeStandby {
		if err := d.SetMode(BMP581ModeStandby); err != nil {
			return err
		}
	}
	if err := d.writeReg(bmp581RegFifoSel, frameSel&0x3); err != nil {
		return err
	}
	cfg := (mode&0x1)<<5 | (threshold & 0x1F)
	if err := d.writeReg(bmp581RegFifoConfig, cfg); err != nil {
		return err
	}
	if prev != BMP581ModeStandby {
		return d.SetMode(prev)
	}
	return nil
}

// FIFOCount reads the number of frames currently in the FIFO.
func (d *BMP581Full) FIFOCount() (uint8, error) {
	buf, err := d.readRegBytes(bmp581RegFifoCount, 1)
	if err != nil {
		return 0, err
	}
	return buf[0] & 0x3F, nil
}

// EffectiveOSR reads OSR_EFF and returns (osr_p_eff, osr_t_eff).
func (d *BMP581Full) EffectiveOSR() (uint8, uint8, error) {
	buf, err := d.readRegBytes(bmp581RegOSREff, 1)
	if err != nil {
		return 0, 0, err
	}
	return (buf[0] >> 3) & 0x7, buf[0] & 0x7, nil
}

// ODRIsValid reads OSR_EFF and returns the odr_is_valid bit.
func (d *BMP581Full) ODRIsValid() (bool, error) {
	buf, err := d.readRegBytes(bmp581RegOSREff, 1)
	if err != nil {
		return false, err
	}
	return buf[0]&0x80 != 0, nil
}

// SetOORThreshold configures the out-of-range pressure detector.
//
// thresholdPa — pressure threshold in Pa.
// rangePa     — symmetric +/- window around the threshold in Pa.
// countLimit  — 0-3 successive over-threshold events required.
func (d *BMP581Full) SetOORThreshold(thresholdPa, rangePa float32, countLimit uint8) error {
	thr17 := int32(thresholdPa*64.0) >> 7
	if err := d.writeReg(bmp581RegOORThrPLSB, uint8(thr17&0xFF)); err != nil {
		return err
	}
	if err := d.writeReg(bmp581RegOORThrPMSB, uint8((thr17>>8)&0xFF)); err != nil {
		return err
	}
	range8 := (int32(rangePa*64.0) >> 7) & 0xFF
	if err := d.writeReg(bmp581RegOORRange, uint8(range8)); err != nil {
		return err
	}
	cfg := (countLimit&0x3)<<6 | uint8((thr17>>16)&0x01)
	return d.writeReg(bmp581RegOORConfig, cfg)
}

// NVMRead reads one user NVM row (row 0x20..0x22).
func (d *BMP581Full) NVMRead(row uint8) (uint16, error) {
	prev := d.pwrMode
	if prev != BMP581ModeStandby {
		if err := d.SetMode(BMP581ModeStandby); err != nil {
			return 0, err
		}
	}
	value, err := func() (uint16, error) {
		if err := d.writeReg(bmp581RegNVMAddr, 0x5D); err != nil {
			return 0, err
		}
		if err := d.writeReg(bmp581RegCmd, 0xA5); err != nil {
			return 0, err
		}
		time.Sleep(2 * time.Millisecond)
		if err := d.writeReg(bmp581RegNVMAddr, 0x40|(row&0x3F)); err != nil {
			return 0, err
		}
		if err := d.writeReg(bmp581RegCmd, 0xA5); err != nil {
			return 0, err
		}
		time.Sleep(2 * time.Millisecond)
		buf, err := d.readRegBytes(bmp581RegNVMDataLSB, 2)
		if err != nil {
			return 0, err
		}
		return uint16(buf[1])<<8 | uint16(buf[0]), nil
	}()
	if prev != BMP581ModeStandby {
		_ = d.SetMode(prev)
	}
	return value, err
}

// NVMWrite writes one user NVM row. Limited to 10,000 total write cycles.
func (d *BMP581Full) NVMWrite(row uint8, value uint16) error {
	prev := d.pwrMode
	if prev != BMP581ModeStandby {
		if err := d.SetMode(BMP581ModeStandby); err != nil {
			return err
		}
	}
	err := func() error {
		if err := d.writeReg(bmp581RegNVMAddr, 0x40|(row&0x3F)); err != nil {
			return err
		}
		if err := d.writeReg(bmp581RegNVMDataLSB, uint8(value&0xFF)); err != nil {
			return err
		}
		if err := d.writeReg(bmp581RegNVMDataMSB, uint8((value>>8)&0xFF)); err != nil {
			return err
		}
		if err := d.writeReg(bmp581RegNVMAddr, 0x5D); err != nil {
			return err
		}
		if err := d.writeReg(bmp581RegCmd, 0xA0); err != nil {
			return err
		}
		time.Sleep(5 * time.Millisecond)
		return nil
	}()
	if prev != BMP581ModeStandby {
		_ = d.SetMode(prev)
	}
	return err
}

// Pressure reads calibrated pressure. Promoted from BMP581Minimal.
func (d *BMP581Full) Pressure() (float32, error) { return d.BMP581Minimal.Pressure() }

// Temperature reads calibrated temperature. Promoted from BMP581Minimal.
func (d *BMP581Full) Temperature() (float32, error) { return d.BMP581Minimal.Temperature() }