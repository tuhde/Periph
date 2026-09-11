// Package pressure contains drivers for standalone pressure sensors.
package pressure

import (
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// LPS33HW register addresses.
const (
	lps33hwRegInterruptCfg uint8 = 0x0B
	lps33hwRegThsPL        uint8 = 0x0C
	lps33hwRegThsPH        uint8 = 0x0D
	lps33hwRegWhoAmI       uint8 = 0x0F
	lps33hwRegCtrlReg1     uint8 = 0x10
	lps33hwRegCtrlReg2     uint8 = 0x11
	lps33hwRegCtrlReg3     uint8 = 0x12
	lps33hwRegFifoCtrl     uint8 = 0x14
	lps33hwRegRefPXl       uint8 = 0x15
	lps33hwRegRefPL        uint8 = 0x16
	lps33hwRegRefPH        uint8 = 0x17
	lps33hwRegRpdsL        uint8 = 0x18
	lps33hwRegRpdsH        uint8 = 0x19
	lps33hwRegResConf      uint8 = 0x1A
	lps33hwRegIntSource    uint8 = 0x25
	lps33hwRegFifoStatus   uint8 = 0x26
	lps33hwRegStatus       uint8 = 0x27
	lps33hwRegPressXl      uint8 = 0x28
	lps33hwRegPressL       uint8 = 0x29
	lps33hwRegPressH       uint8 = 0x2A
	lps33hwRegTempL        uint8 = 0x2B
	lps33hwRegTempH        uint8 = 0x2C
	lps33hwRegLpfpRes      uint8 = 0x33
)

// LPS33HW expected chip ID.
const lps33hwChipID uint8 = 0xB1

// LPS33HW default CTRL_REG1/CTRL_REG2 values.
const (
	lps33hwCtrlReg1Default uint8 = 0x12 // ODR=1 Hz, BDU=1
	lps33hwCtrlReg2Reset   uint8 = 0x04 // SWRESET=1
	lps33hwCtrlReg2Default uint8 = 0x10 // IF_ADD_INC=1
)

// LPS33HW STATUS register bits.
const (
	LPS33HWStatusPDA uint8 = 0x01
	LPS33HWStatusTDA uint8 = 0x02
)

// LPS33HW status-poll parameters.
const (
	lps33hwStatusTimeoutIterations = 50
	lps33hwStatusPollInterval      = 5 * time.Millisecond
)

// LPS33HW output data rates.
const (
	// LPS33HWODRPowerDown is the ODR=000 setting (power-down / one-shot).
	LPS33HWODRPowerDown uint8 = 0
	// LPS33HWODR1Hz selects ODR=001 (1 Hz continuous).
	LPS33HWODR1Hz uint8 = 1
	// LPS33HWODR10Hz selects ODR=010 (10 Hz continuous).
	LPS33HWODR10Hz uint8 = 2
	// LPS33HWODR25Hz selects ODR=011 (25 Hz continuous).
	LPS33HWODR25Hz uint8 = 3
	// LPS33HWODR50Hz selects ODR=100 (50 Hz continuous).
	LPS33HWODR50Hz uint8 = 4
	// LPS33HWODR75Hz selects ODR=101 (75 Hz continuous).
	LPS33HWODR75Hz uint8 = 5
)

// LPS33HW low-pass filter bandwidth selections (when EN_LPFP=1).
const (
	// LPS33HWLPFPBWODR9 is ODR/9 bandwidth.
	LPS33HWLPFPBWODR9 uint8 = 0
	// LPS33HWLPFPBWODR20 is ODR/20 bandwidth.
	LPS33HWLPFPBWODR20 uint8 = 1
)

// LPS33HW FIFO modes.
const (
	LPS33HWFIFOModeBypass           uint8 = 0
	LPS33HWFIFOModeFifo             uint8 = 1
	LPS33HWFIFOModeStream           uint8 = 2
	LPS33HWFIFOModeStreamToFifo     uint8 = 3
	LPS33HWFIFOModeBypassToStream   uint8 = 4
	LPS33HWFIFOModeDynamicStream    uint8 = 6
	LPS33HWFIFOModeBypassToFifo     uint8 = 7
)

// LPS33HW INT_DRDY signal selections.
const (
	LPS33HWIntSDataSignals  uint8 = 0
	LPS33HWIntSPressureHigh uint8 = 1
	LPS33HWIntSPressureLow  uint8 = 2
	LPS33HWIntSPressureBoth uint8 = 3
)

// LPS33HW STATUS bit flags.
const (
	LPS33HWStatusPDAFlag uint8 = 0x01
	LPS33HWStatusTDAFlag uint8 = 0x02
	LPS33HWStatusPORFlag uint8 = 0x10
	LPS33HWStatusTORFlag uint8 = 0x20
)

// lps33hwWriteReg writes a single byte to a register.
func lps33hwWriteReg(t connection.Connection, reg, value uint8) error {
	return t.Write([]byte{reg, value})
}

// lps33hwReadReg8 reads a single byte from a register.
func lps33hwReadReg8(t connection.Connection, reg uint8) (uint8, error) {
	b, err := t.WriteRead([]byte{reg}, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// lps33hwWaitStatus polls STATUS until (status & mask) == mask, or times out.
func lps33hwWaitStatus(t connection.Connection, mask uint8) error {
	for i := 0; i < lps33hwStatusTimeoutIterations; i++ {
		s, err := lps33hwReadReg8(t, lps33hwRegStatus)
		if err != nil {
			return err
		}
		if s&mask == mask {
			return nil
		}
		time.Sleep(lps33hwStatusPollInterval)
	}
	return nil
}

// LPS33HWMinimal is the LPS33HW absolute pressure + temperature driver —
// minimal interface. The default configuration baked in is ODR=1 Hz,
// BDU=1, EN_LPFP=0, IF_ADD_INC=1.
type LPS33HWMinimal struct {
	connection connection.Connection
	addr       uint8
}

// NewLPS33HWMinimal creates an LPS33HWMinimal, verifies the chip ID,
// software-resets, and applies the default configuration.
//
// t must be a configured I²C connection bound to the chip (I²C address
// 0x5C or 0x5D).
func NewLPS33HWMinimal(t connection.Connection, addr uint8) (*LPS33HWMinimal, error) {
	id, err := lps33hwReadReg8(t, lps33hwRegWhoAmI)
	if err != nil {
		return nil, err
	}
	if id != lps33hwChipID {
		return nil, &lps33hwChipIDError{expected: lps33hwChipID, got: id}
	}
	d := &LPS33HWMinimal{connection: t, addr: addr}
	if err := lps33hwWriteReg(t, lps33hwRegCtrlReg2, lps33hwCtrlReg2Reset); err != nil {
		return nil, err
	}
	time.Sleep(1 * time.Millisecond)
	if err := lps33hwWriteReg(t, lps33hwRegCtrlReg2, lps33hwCtrlReg2Default); err != nil {
		return nil, err
	}
	if err := lps33hwWriteReg(t, lps33hwRegCtrlReg1, lps33hwCtrlReg1Default); err != nil {
		return nil, err
	}
	return d, nil
}

// lps33hwChipIDError is returned by NewLPS33HWMinimal when the chip responds
// with an unexpected WHO_AM_I byte.
type lps33hwChipIDError struct {
	expected uint8
	got      uint8
}

func (e *lps33hwChipIDError) Error() string {
	return "LPS33HW not found: expected 0xB1, got 0x" + uint8Hex(e.got)
}

func uint8Hex(v uint8) string {
	const hex = "0123456789ABCDEF"
	return string([]byte{hex[v>>4], hex[v&0x0F]})
}

// Pressure reads the calibrated absolute pressure. Waits for STATUS.P_DA,
// then bursts 5 bytes from PRESS_OUT_XL through TEMP_OUT_H. With BDU=1
// the latch releases once PRESS_OUT_H has been read, which falls inside
// the burst.
//
// Returns pressure in Pa.
func (d *LPS33HWMinimal) Pressure() (float32, error) {
	p, _, err := d.readPressTemp()
	if err != nil {
		return 0, err
	}
	return p, nil
}

// Temperature reads the calibrated temperature. Waits for STATUS.T_DA,
// then bursts 5 bytes from PRESS_OUT_XL through TEMP_OUT_H to release
// the BDU latch cleanly.
//
// Returns temperature in °C.
func (d *LPS33HWMinimal) Temperature() (float32, error) {
	_, t, err := d.readPressTemp()
	if err != nil {
		return 0, err
	}
	return t, nil
}

func (d *LPS33HWMinimal) readPressTemp() (float32, float32, error) {
	if err := lps33hwWaitStatus(d.connection, LPS33HWStatusPDA|LPS33HWStatusTDA); err != nil {
		return 0, 0, err
	}
	raw, err := d.connection.WriteRead([]byte{lps33hwRegPressXl}, 5)
	if err != nil {
		return 0, 0, err
	}
	rawPress := int32(raw[0]) | int32(raw[1])<<8 | int32(raw[2])<<16
	if rawPress >= 0x800000 {
		rawPress -= 0x1000000
	}
	rawTemp := int32(raw[3]) | int32(raw[4])<<8
	if rawTemp >= 0x8000 {
		rawTemp -= 0x10000
	}
	pressurePa := float32(rawPress) * 100.0 / 4096.0
	temperatureC := float32(rawTemp) / 100.0
	return pressurePa, temperatureC, nil
}

// LPS33HWFull is the LPS33HW absolute pressure + temperature driver — full
// interface. Extends LPS33HWMinimal with configuration, one-shot, FIFO,
// interrupt routing, AUTOZERO/AUTORIFP, soft reset, reboot, and pressure
// offset.
type LPS33HWFull struct {
	*LPS33HWMinimal
}

// NewLPS33HWFull creates an LPS33HWFull and applies the default
// configuration.
func NewLPS33HWFull(t connection.Connection, addr uint8) (*LPS33HWFull, error) {
	m, err := NewLPS33HWMinimal(t, addr)
	if err != nil {
		return nil, err
	}
	return &LPS33HWFull{LPS33HWMinimal: m}, nil
}

// Configure writes CTRL_REG1 (ODR/BDU/EN_LPFP/LPFP_CFG/SIM) and the LC_EN
// bit inside RES_CONF.
//
// Parameters:
//   - odr     — output data rate (LPS33HWODR*)
//   - bdu     — block data update (true = hold until PRESS_OUT_H read)
//   - enLpfp  — enable additional low-pass filter on pressure
//   - lpfpCfg — LPF bandwidth when enabled (LPS33HWLPFPBW*)
//   - lcEn    — low-current mode (only writable in power-down)
//   - sim     — SPI 3-wire mode
func (d *LPS33HWFull) Configure(odr, bdu, enLpfp, lpfpCfg, lcEn, sim uint8) error {
	bduBit := bdu & 1
	enLpfpBit := enLpfp & 1
	lcEnBit := lcEn & 1
	simBit := sim & 1
	ctrl1 := (odr&7)<<4 | enLpfpBit<<3 | (lpfpCfg&1)<<2 | bduBit<<1 | simBit
	if err := lps33hwWriteReg(d.connection, lps33hwRegCtrlReg1, ctrl1); err != nil {
		return err
	}
	current, err := lps33hwReadReg8(d.connection, lps33hwRegResConf)
	if err != nil {
		return err
	}
	newRes := (current & 0xFE) | lcEnBit
	return lps33hwWriteReg(d.connection, lps33hwRegResConf, newRes)
}

// OneShot triggers a single pressure+temperature measurement. Requires
// ODR=000 (power-down). Writes ONE_SHOT in CTRL_REG2 and polls STATUS
// until both P_DA and T_DA are set, then bursts 5 bytes.
//
// Returns (pressure_Pa, temperature_C).
func (d *LPS33HWFull) OneShot() (float32, float32, error) {
	current, err := lps33hwReadReg8(d.connection, lps33hwRegCtrlReg2)
	if err != nil {
		return 0, 0, err
	}
	if err := lps33hwWriteReg(d.connection, lps33hwRegCtrlReg2, current|0x01); err != nil {
		return 0, 0, err
	}
	for i := 0; i < lps33hwStatusTimeoutIterations; i++ {
		status, err := lps33hwReadReg8(d.connection, lps33hwRegStatus)
		if err != nil {
			return 0, 0, err
		}
		if status&(LPS33HWStatusPDA|LPS33HWStatusTDA) == (LPS33HWStatusPDA | LPS33HWStatusTDA) {
			return d.readPressTemp()
		}
		time.Sleep(lps33hwStatusPollInterval)
	}
	return 0, 0, nil
}

// Status reads the STATUS register. Bit 0 = P_DA, bit 1 = T_DA,
// bit 4 = P_OR, bit 5 = T_OR.
func (d *LPS33HWFull) Status() (uint8, error) {
	return lps33hwReadReg8(d.connection, lps33hwRegStatus)
}

// InterruptStatus reads the INT_SOURCE register. Bit 0 = PH, bit 1 = PL,
// bit 2 = IA, bit 7 = BOOT_STATUS.
func (d *LPS33HWFull) InterruptStatus() (uint8, error) {
	return lps33hwReadReg8(d.connection, lps33hwRegIntSource)
}

// Reset software-resets via SWRESET, waits for self-clear, restores defaults.
func (d *LPS33HWFull) Reset() error {
	if err := lps33hwWriteReg(d.connection, lps33hwRegCtrlReg2, lps33hwCtrlReg2Reset); err != nil {
		return err
	}
	for i := 0; i < lps33hwStatusTimeoutIterations; i++ {
		current, err := lps33hwReadReg8(d.connection, lps33hwRegCtrlReg2)
		if err != nil {
			return err
		}
		if current&0x04 == 0 {
			break
		}
		time.Sleep(1 * time.Millisecond)
	}
	if err := lps33hwWriteReg(d.connection, lps33hwRegCtrlReg2, lps33hwCtrlReg2Default); err != nil {
		return err
	}
	return lps33hwWriteReg(d.connection, lps33hwRegCtrlReg1, lps33hwCtrlReg1Default)
}

// Reboot reloads factory trimming from internal Flash via BOOT bit.
func (d *LPS33HWFull) Reboot() error {
	if err := lps33hwWriteReg(d.connection, lps33hwRegCtrlReg2, 0x80); err != nil {
		return err
	}
	for i := 0; i < 100; i++ {
		status, err := lps33hwReadReg8(d.connection, lps33hwRegIntSource)
		if err != nil {
			return err
		}
		if status&0x80 == 0 {
			break
		}
		time.Sleep(lps33hwStatusPollInterval)
	}
	return nil
}

// SetPressureOffset writes RPDS to apply a one-point calibration offset.
//
// offsetHPa is in hectopascals. 1 RPDS LSB = 1/16 hPa.
func (d *LPS33HWFull) SetPressureOffset(offsetHPa float32) error {
	raw := int32(offsetHPa * 16.0)
	if raw < 0 {
		raw += 0x10000
	}
	if err := lps33hwWriteReg(d.connection, lps33hwRegRpdsL, uint8(raw&0xFF)); err != nil {
		return err
	}
	return lps33hwWriteReg(d.connection, lps33hwRegRpdsH, uint8((raw>>8)&0xFF))
}

// SetAutozero sets AUTOZERO=1 — current pressure is stored in REF_P.
func (d *LPS33HWFull) SetAutozero() error {
	current, err := lps33hwReadReg8(d.connection, lps33hwRegInterruptCfg)
	if err != nil {
		return err
	}
	return lps33hwWriteReg(d.connection, lps33hwRegInterruptCfg, current|0x20)
}

// ClearAutozero clears AUTOZERO mode and resets REF_P to 0.
func (d *LPS33HWFull) ClearAutozero() error {
	current, err := lps33hwReadReg8(d.connection, lps33hwRegInterruptCfg)
	if err != nil {
		return err
	}
	return lps33hwWriteReg(d.connection, lps33hwRegInterruptCfg, current|0x10)
}

// SetAutorifp sets AUTORIFP=1 — next measurement value is stored in RPDS.
func (d *LPS33HWFull) SetAutorifp() error {
	current, err := lps33hwReadReg8(d.connection, lps33hwRegInterruptCfg)
	if err != nil {
		return err
	}
	return lps33hwWriteReg(d.connection, lps33hwRegInterruptCfg, current|0x80)
}

// ClearAutorifp clears AUTORIFP mode and resets RPDS to 0.
func (d *LPS33HWFull) ClearAutorifp() error {
	current, err := lps33hwReadReg8(d.connection, lps33hwRegInterruptCfg)
	if err != nil {
		return err
	}
	return lps33hwWriteReg(d.connection, lps33hwRegInterruptCfg, current|0x40)
}

// ConfigureInterrupt routes CTRL_REG3 events to the INT_DRDY pin.
//
// Each `enable` parameter is a boolean (0 = disable, 1 = enable).
func (d *LPS33HWFull) ConfigureInterrupt(drdy, fFth, fOvr, fFss5, intS, activeLow, openDrain uint8) error {
	ctrl3 := (activeLow&1)<<7 | (openDrain&1)<<6 | (fFss5&1)<<5 | (fFth&1)<<4 |
		(fOvr&1)<<3 | (drdy&1)<<2 | (intS & 0x03)
	return lps33hwWriteReg(d.connection, lps33hwRegCtrlReg3, ctrl3)
}

// ConfigurePressureInterrupt configures the differential pressure threshold
// interrupt. Each `enable` parameter is a boolean.
func (d *LPS33HWFull) ConfigurePressureInterrupt(highEn, lowEn, thresholdHPa float32, latch bool) error {
	rawThs := int32(thresholdHPa * 16.0) & 0xFFFF
	if err := lps33hwWriteReg(d.connection, lps33hwRegThsPL, uint8(rawThs&0xFF)); err != nil {
		return err
	}
	if err := lps33hwWriteReg(d.connection, lps33hwRegThsPH, uint8((rawThs>>8)&0xFF)); err != nil {
		return err
	}
	current, err := lps33hwReadReg8(d.connection, lps33hwRegInterruptCfg)
	if err != nil {
		return err
	}
	var latchBit, highBit, lowBit uint8
	if latch {
		latchBit = 1
	}
	if highEn != 0 {
		highBit = 1
	}
	if lowEn != 0 {
		lowBit = 1
	}
	newCfg := (current & 0xF0) | latchBit<<2 | highBit<<1 | lowBit
	return lps33hwWriteReg(d.connection, lps33hwRegInterruptCfg, newCfg)
}

// EnableFifo enables the FIFO with the given mode and watermark. Mode 5
// is reserved and silently ignored.
func (d *LPS33HWFull) EnableFifo(mode, watermark uint8) error {
	if mode == 5 {
		return nil
	}
	ctrl := (mode&7)<<5 | (watermark & 0x1F)
	if err := lps33hwWriteReg(d.connection, lps33hwRegFifoCtrl, ctrl); err != nil {
		return err
	}
	current, err := lps33hwReadReg8(d.connection, lps33hwRegCtrlReg2)
	if err != nil {
		return err
	}
	return lps33hwWriteReg(d.connection, lps33hwRegCtrlReg2, current|0x40)
}

// DisableFifo disables the FIFO and resets to Bypass mode.
func (d *LPS33HWFull) DisableFifo() error {
	current, err := lps33hwReadReg8(d.connection, lps33hwRegCtrlReg2)
	if err != nil {
		return err
	}
	if err := lps33hwWriteReg(d.connection, lps33hwRegCtrlReg2, current&0xBF); err != nil {
		return err
	}
	return lps33hwWriteReg(d.connection, lps33hwRegFifoCtrl, 0)
}

// FifoStatus reads the FIFO_STATUS register. Bit 7 = FTH_FIFO, bit 6 = OVR,
// bits [5:0] = FSS count.
func (d *LPS33HWFull) FifoStatus() (uint8, error) {
	return lps33hwReadReg8(d.connection, lps33hwRegFifoStatus)
}

// ResetLpf reads LPFP_RES to flush any transitory LPF state.
func (d *LPS33HWFull) ResetLpf() error {
	_, err := d.connection.WriteRead([]byte{lps33hwRegLpfpRes}, 1)
	return err
}