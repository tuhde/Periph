// Package pressure contains drivers for standalone pressure sensors.
package pressure

import (
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// LPS22HB register addresses.
const (
	lps22hbRegInterruptCfg uint8 = 0x0B
	lps22hbRegThsPL        uint8 = 0x0C
	lps22hbRegThsPH        uint8 = 0x0D
	lps22hbRegWhoAmI       uint8 = 0x0F
	lps22hbRegCtrlReg1     uint8 = 0x10
	lps22hbRegCtrlReg2     uint8 = 0x11
	lps22hbRegCtrlReg3     uint8 = 0x12
	lps22hbRegFifoCtrl     uint8 = 0x14
	lps22hbRegRefPXl       uint8 = 0x15
	lps22hbRegRefPL        uint8 = 0x16
	lps22hbRegRefPH        uint8 = 0x17
	lps22hbRegRpdsL        uint8 = 0x18
	lps22hbRegRpdsH        uint8 = 0x19
	lps22hbRegResConf      uint8 = 0x1A
	lps22hbRegIntSource    uint8 = 0x25
	lps22hbRegFifoStatus   uint8 = 0x26
	lps22hbRegStatus       uint8 = 0x27
	lps22hbRegPressXl      uint8 = 0x28
	lps22hbRegPressL       uint8 = 0x29
	lps22hbRegPressH       uint8 = 0x2A
	lps22hbRegTempL        uint8 = 0x2B
	lps22hbRegTempH        uint8 = 0x2C
	lps22hbRegLpfpRes      uint8 = 0x33
)

// LPS22HB expected chip ID.
const lps22hbChipID uint8 = 0xB1

// LPS22HB default CTRL_REG1/CTRL_REG2 values.
const (
	lps22hbCtrlReg1Default uint8 = 0x12 // ODR=1 Hz, BDU=1
	lps22hbCtrlReg2Reset   uint8 = 0x04 // SWRESET=1
	lps22hbCtrlReg2Default uint8 = 0x10 // IF_ADD_INC=1
)

// LPS22HB STATUS register bits.
const (
	LPS22HBStatusPDA uint8 = 0x01
	LPS22HBStatusTDA uint8 = 0x02
)

// LPS22HB status-poll parameters.
const (
	lps22hbStatusTimeoutIterations = 50
	lps22hbStatusPollInterval      = 5 * time.Millisecond
)

// LPS22HB output data rates.
const (
	// LPS22HBODRPowerDown is the ODR=000 setting (power-down / one-shot).
	LPS22HBODRPowerDown uint8 = 0
	// LPS22HBODR1Hz selects ODR=001 (1 Hz continuous).
	LPS22HBODR1Hz uint8 = 1
	// LPS22HBODR10Hz selects ODR=010 (10 Hz continuous).
	LPS22HBODR10Hz uint8 = 2
	// LPS22HBODR25Hz selects ODR=011 (25 Hz continuous).
	LPS22HBODR25Hz uint8 = 3
	// LPS22HBODR50Hz selects ODR=100 (50 Hz continuous).
	LPS22HBODR50Hz uint8 = 4
	// LPS22HBODR75Hz selects ODR=101 (75 Hz continuous).
	LPS22HBODR75Hz uint8 = 5
)

// LPS22HB low-pass filter bandwidth selections (when EN_LPFP=1).
const (
	// LPS22HBLPFPBWODR9 is ODR/9 bandwidth.
	LPS22HBLPFPBWODR9 uint8 = 0
	// LPS22HBLPFPBWODR20 is ODR/20 bandwidth.
	LPS22HBLPFPBWODR20 uint8 = 1
)

// LPS22HB FIFO modes.
const (
	LPS22HBFIFOModeBypass           uint8 = 0
	LPS22HBFIFOModeFifo             uint8 = 1
	LPS22HBFIFOModeStream           uint8 = 2
	LPS22HBFIFOModeStreamToFifo     uint8 = 3
	LPS22HBFIFOModeBypassToStream   uint8 = 4
	LPS22HBFIFOModeDynamicStream    uint8 = 6
	LPS22HBFIFOModeBypassToFifo     uint8 = 7
)

// LPS22HB INT_DRDY signal selections.
const (
	LPS22HBIntSDataSignals  uint8 = 0
	LPS22HBIntSPressureHigh uint8 = 1
	LPS22HBIntSPressureLow  uint8 = 2
	LPS22HBIntSPressureBoth uint8 = 3
)

// LPS22HB STATUS bit flags.
const (
	LPS22HBStatusPDAFlag uint8 = 0x01
	LPS22HBStatusTDAFlag uint8 = 0x02
	LPS22HBStatusPORFlag uint8 = 0x10
	LPS22HBStatusTORFlag uint8 = 0x20
)

// lps22hbWriteReg writes a single byte to a register.
func lps22hbWriteReg(t connection.RegisterConnection, reg, value uint8) error {
	return t.WriteReg(uint32(reg), []byte{value})
}

// lps22hbReadReg8 reads a single byte from a register.
func lps22hbReadReg8(t connection.RegisterConnection, reg uint8) (uint8, error) {
	b, err := t.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// lps22hbWaitStatus polls STATUS until (status & mask) == mask, or times out.
func lps22hbWaitStatus(t connection.RegisterConnection, mask uint8) error {
	for i := 0; i < lps22hbStatusTimeoutIterations; i++ {
		s, err := lps22hbReadReg8(t, lps22hbRegStatus)
		if err != nil {
			return err
		}
		if s&mask == mask {
			return nil
		}
		time.Sleep(lps22hbStatusPollInterval)
	}
	return nil
}

// LPS22HBMinimal is the LPS22HB absolute pressure + temperature driver —
// minimal interface. The default configuration baked in is ODR=1 Hz,
// BDU=1, EN_LPFP=0, IF_ADD_INC=1.
type LPS22HBMinimal struct {
	connection connection.RegisterConnection
	addr       uint8
}

// NewLPS22HBMinimal creates an LPS22HBMinimal, verifies the chip ID,
// software-resets, and applies the default configuration.
//
// t must be a configured I²C connection bound to the chip (I²C address
// 0x5C or 0x5D).
func NewLPS22HBMinimal(t connection.RegisterConnection, addr uint8) (*LPS22HBMinimal, error) {
	id, err := lps22hbReadReg8(t, lps22hbRegWhoAmI)
	if err != nil {
		return nil, err
	}
	if id != lps22hbChipID {
		return nil, &lps22hbChipIDError{expected: lps22hbChipID, got: id}
	}
	d := &LPS22HBMinimal{connection: t, addr: addr}
	if err := lps22hbWriteReg(t, lps22hbRegCtrlReg2, lps22hbCtrlReg2Reset); err != nil {
		return nil, err
	}
	time.Sleep(1 * time.Millisecond)
	if err := lps22hbWriteReg(t, lps22hbRegCtrlReg2, lps22hbCtrlReg2Default); err != nil {
		return nil, err
	}
	if err := lps22hbWriteReg(t, lps22hbRegCtrlReg1, lps22hbCtrlReg1Default); err != nil {
		return nil, err
	}
	return d, nil
}

// lps22hbChipIDError is returned by NewLPS22HBMinimal when the chip responds
// with an unexpected WHO_AM_I byte.
type lps22hbChipIDError struct {
	expected uint8
	got      uint8
}

func (e *lps22hbChipIDError) Error() string {
	return "LPS22HB not found: expected 0xB1, got 0x" + lps22hbUint8Hex(e.got)
}

func lps22hbUint8Hex(v uint8) string {
	const hex = "0123456789ABCDEF"
	return string([]byte{hex[v>>4], hex[v&0x0F]})
}

// Pressure reads the calibrated absolute pressure. Waits for STATUS.P_DA,
// then bursts 5 bytes from PRESS_OUT_XL through TEMP_OUT_H. With BDU=1
// the latch releases once PRESS_OUT_H has been read, which falls inside
// the burst.
//
// Returns pressure in Pa.
func (d *LPS22HBMinimal) Pressure() (float32, error) {
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
func (d *LPS22HBMinimal) Temperature() (float32, error) {
	_, t, err := d.readPressTemp()
	if err != nil {
		return 0, err
	}
	return t, nil
}

func (d *LPS22HBMinimal) readPressTemp() (float32, float32, error) {
	if err := lps22hbWaitStatus(d.connection, LPS22HBStatusPDA|LPS22HBStatusTDA); err != nil {
		return 0, 0, err
	}
	raw, err := d.connection.ReadReg(uint32(lps22hbRegPressXl), 5)
	if err != nil {
		return 0, 0, err
	}
	rawPress := int32(raw[0]) | int32(raw[1])<<8 | int32(raw[2])<<16
	rawPress = int32(connection.ToSigned(uint32(rawPress), 24))
	rawTemp := int32(raw[3]) | int32(raw[4])<<8
	rawTemp = int32(connection.ToSigned(uint32(rawTemp), 16))
	pressurePa := float32(rawPress) * 100.0 / 4096.0
	temperatureC := float32(rawTemp) / 100.0
	return pressurePa, temperatureC, nil
}

// LPS22HBFull is the LPS22HB absolute pressure + temperature driver — full
// interface. Extends LPS22HBMinimal with configuration, one-shot, FIFO,
// interrupt routing, AUTOZERO/AUTORIFP, soft reset, reboot, and pressure
// offset.
type LPS22HBFull struct {
	*LPS22HBMinimal
}

// NewLPS22HBFull creates an LPS22HBFull and applies the default
// configuration.
func NewLPS22HBFull(t connection.RegisterConnection, addr uint8) (*LPS22HBFull, error) {
	m, err := NewLPS22HBMinimal(t, addr)
	if err != nil {
		return nil, err
	}
	return &LPS22HBFull{LPS22HBMinimal: m}, nil
}

// Configure writes CTRL_REG1 (ODR/BDU/EN_LPFP/LPFP_CFG/SIM) and the LC_EN
// bit inside RES_CONF.
//
// Parameters:
//   - odr     — output data rate (LPS22HBODR*)
//   - bdu     — block data update (true = hold until PRESS_OUT_H read)
//   - enLpfp  — enable additional low-pass filter on pressure
//   - lpfpCfg — LPF bandwidth when enabled (LPS22HBLPFPBW*)
//   - lcEn    — low-current mode (only writable in power-down)
//   - sim     — SPI 3-wire mode
func (d *LPS22HBFull) Configure(odr, bdu, enLpfp, lpfpCfg, lcEn, sim uint8) error {
	bduBit := bdu & 1
	enLpfpBit := enLpfp & 1
	lcEnBit := lcEn & 1
	simBit := sim & 1
	ctrl1 := (odr&7)<<4 | enLpfpBit<<3 | (lpfpCfg&1)<<2 | bduBit<<1 | simBit
	if err := lps22hbWriteReg(d.connection, lps22hbRegCtrlReg1, ctrl1); err != nil {
		return err
	}
	current, err := lps22hbReadReg8(d.connection, lps22hbRegResConf)
	if err != nil {
		return err
	}
	newRes := (current & 0xFE) | lcEnBit
	return lps22hbWriteReg(d.connection, lps22hbRegResConf, newRes)
}

// OneShot triggers a single pressure+temperature measurement. Requires
// ODR=000 (power-down). Writes ONE_SHOT in CTRL_REG2 and polls STATUS
// until both P_DA and T_DA are set, then bursts 5 bytes.
//
// Returns (pressure_Pa, temperature_C).
func (d *LPS22HBFull) OneShot() (float32, float32, error) {
	current, err := lps22hbReadReg8(d.connection, lps22hbRegCtrlReg2)
	if err != nil {
		return 0, 0, err
	}
	if err := lps22hbWriteReg(d.connection, lps22hbRegCtrlReg2, current|0x01); err != nil {
		return 0, 0, err
	}
	for i := 0; i < lps22hbStatusTimeoutIterations; i++ {
		status, err := lps22hbReadReg8(d.connection, lps22hbRegStatus)
		if err != nil {
			return 0, 0, err
		}
		if status&(LPS22HBStatusPDA|LPS22HBStatusTDA) == (LPS22HBStatusPDA | LPS22HBStatusTDA) {
			return d.readPressTemp()
		}
		time.Sleep(lps22hbStatusPollInterval)
	}
	return 0, 0, nil
}

// Status reads the STATUS register. Bit 0 = P_DA, bit 1 = T_DA,
// bit 4 = P_OR, bit 5 = T_OR.
func (d *LPS22HBFull) Status() (uint8, error) {
	return lps22hbReadReg8(d.connection, lps22hbRegStatus)
}

// InterruptStatus reads the INT_SOURCE register. Bit 0 = PH, bit 1 = PL,
// bit 2 = IA, bit 7 = BOOT_STATUS.
func (d *LPS22HBFull) InterruptStatus() (uint8, error) {
	return lps22hbReadReg8(d.connection, lps22hbRegIntSource)
}

// Reset software-resets via SWRESET, waits for self-clear, restores defaults.
func (d *LPS22HBFull) Reset() error {
	if err := lps22hbWriteReg(d.connection, lps22hbRegCtrlReg2, lps22hbCtrlReg2Reset); err != nil {
		return err
	}
	for i := 0; i < lps22hbStatusTimeoutIterations; i++ {
		current, err := lps22hbReadReg8(d.connection, lps22hbRegCtrlReg2)
		if err != nil {
			return err
		}
		if current&0x04 == 0 {
			break
		}
		time.Sleep(1 * time.Millisecond)
	}
	if err := lps22hbWriteReg(d.connection, lps22hbRegCtrlReg2, lps22hbCtrlReg2Default); err != nil {
		return err
	}
	return lps22hbWriteReg(d.connection, lps22hbRegCtrlReg1, lps22hbCtrlReg1Default)
}

// Reboot reloads factory trimming from internal Flash via BOOT bit.
func (d *LPS22HBFull) Reboot() error {
	if err := lps22hbWriteReg(d.connection, lps22hbRegCtrlReg2, 0x80); err != nil {
		return err
	}
	for i := 0; i < 100; i++ {
		status, err := lps22hbReadReg8(d.connection, lps22hbRegIntSource)
		if err != nil {
			return err
		}
		if status&0x80 == 0 {
			break
		}
		time.Sleep(lps22hbStatusPollInterval)
	}
	return nil
}

// SetPressureOffset writes RPDS to apply a one-point calibration offset.
//
// offsetHPa is in hectopascals. 1 RPDS LSB = 1/16 hPa.
func (d *LPS22HBFull) SetPressureOffset(offsetHPa float32) error {
	raw := int32(offsetHPa * 16.0)
	if raw < 0 {
		raw += 0x10000
	}
	if err := lps22hbWriteReg(d.connection, lps22hbRegRpdsL, uint8(raw&0xFF)); err != nil {
		return err
	}
	return lps22hbWriteReg(d.connection, lps22hbRegRpdsH, uint8((raw>>8)&0xFF))
}

// SetAutozero sets AUTOZERO=1 — current pressure is stored in REF_P.
func (d *LPS22HBFull) SetAutozero() error {
	current, err := lps22hbReadReg8(d.connection, lps22hbRegInterruptCfg)
	if err != nil {
		return err
	}
	return lps22hbWriteReg(d.connection, lps22hbRegInterruptCfg, current|0x20)
}

// ClearAutozero clears AUTOZERO mode and resets REF_P to 0.
func (d *LPS22HBFull) ClearAutozero() error {
	current, err := lps22hbReadReg8(d.connection, lps22hbRegInterruptCfg)
	if err != nil {
		return err
	}
	return lps22hbWriteReg(d.connection, lps22hbRegInterruptCfg, current|0x10)
}

// SetAutorifp sets AUTORIFP=1 — next measurement value is stored in RPDS.
func (d *LPS22HBFull) SetAutorifp() error {
	current, err := lps22hbReadReg8(d.connection, lps22hbRegInterruptCfg)
	if err != nil {
		return err
	}
	return lps22hbWriteReg(d.connection, lps22hbRegInterruptCfg, current|0x80)
}

// ClearAutorifp clears AUTORIFP mode and resets RPDS to 0.
func (d *LPS22HBFull) ClearAutorifp() error {
	current, err := lps22hbReadReg8(d.connection, lps22hbRegInterruptCfg)
	if err != nil {
		return err
	}
	return lps22hbWriteReg(d.connection, lps22hbRegInterruptCfg, current|0x40)
}

// ConfigureInterrupt routes CTRL_REG3 events to the INT_DRDY pin.
//
// Each `enable` parameter is a boolean (0 = disable, 1 = enable).
func (d *LPS22HBFull) ConfigureInterrupt(drdy, fFth, fOvr, fFss5, intS, activeLow, openDrain uint8) error {
	ctrl3 := (activeLow&1)<<7 | (openDrain&1)<<6 | (fFss5&1)<<5 | (fFth&1)<<4 |
		(fOvr&1)<<3 | (drdy&1)<<2 | (intS & 0x03)
	return lps22hbWriteReg(d.connection, lps22hbRegCtrlReg3, ctrl3)
}

// ConfigurePressureInterrupt configures the differential pressure threshold
// interrupt. Each `enable` parameter is a boolean.
func (d *LPS22HBFull) ConfigurePressureInterrupt(highEn, lowEn, thresholdHPa float32, latch bool) error {
	rawThs := int32(thresholdHPa * 16.0) & 0xFFFF
	if err := lps22hbWriteReg(d.connection, lps22hbRegThsPL, uint8(rawThs&0xFF)); err != nil {
		return err
	}
	if err := lps22hbWriteReg(d.connection, lps22hbRegThsPH, uint8((rawThs>>8)&0xFF)); err != nil {
		return err
	}
	current, err := lps22hbReadReg8(d.connection, lps22hbRegInterruptCfg)
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
	return lps22hbWriteReg(d.connection, lps22hbRegInterruptCfg, newCfg)
}

// EnableFifo enables the FIFO with the given mode and watermark. Mode 5
// is reserved and silently ignored.
func (d *LPS22HBFull) EnableFifo(mode, watermark uint8) error {
	if mode == 5 {
		return nil
	}
	ctrl := (mode&7)<<5 | (watermark & 0x1F)
	if err := lps22hbWriteReg(d.connection, lps22hbRegFifoCtrl, ctrl); err != nil {
		return err
	}
	current, err := lps22hbReadReg8(d.connection, lps22hbRegCtrlReg2)
	if err != nil {
		return err
	}
	return lps22hbWriteReg(d.connection, lps22hbRegCtrlReg2, current|0x40)
}

// DisableFifo disables the FIFO and resets to Bypass mode.
func (d *LPS22HBFull) DisableFifo() error {
	current, err := lps22hbReadReg8(d.connection, lps22hbRegCtrlReg2)
	if err != nil {
		return err
	}
	if err := lps22hbWriteReg(d.connection, lps22hbRegCtrlReg2, current&0xBF); err != nil {
		return err
	}
	return lps22hbWriteReg(d.connection, lps22hbRegFifoCtrl, 0)
}

// FifoStatus reads the FIFO_STATUS register. Bit 7 = FTH_FIFO, bit 6 = OVR,
// bits [5:0] = FSS count.
func (d *LPS22HBFull) FifoStatus() (uint8, error) {
	return lps22hbReadReg8(d.connection, lps22hbRegFifoStatus)
}

// ResetLpf reads LPFP_RES to flush any transitory LPF state.
func (d *LPS22HBFull) ResetLpf() error {
	_, err := d.connection.ReadReg(uint32(lps22hbRegLpfpRes), 1)
	return err
}