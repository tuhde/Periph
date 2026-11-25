package power

import (
	"fmt"
	"math"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// AXP2101 register addresses.
const (
	axp2101RegStatus1      uint8 = 0x00
	axp2101RegStatus2      uint8 = 0x01
	axp2101RegDataBuffer0  uint8 = 0x04
	axp2101RegCommonConfig uint8 = 0x10
	axp2101RegBatfetCtrl   uint8 = 0x12
	axp2101RegDieTempCtrl  uint8 = 0x13
	axp2101RegMinVsys      uint8 = 0x14
	axp2101RegVindpm       uint8 = 0x15
	axp2101RegIinlim       uint8 = 0x16
	axp2101RegGaugeReset   uint8 = 0x17
	axp2101RegModuleEnable uint8 = 0x18
	axp2101RegWatchdogCtrl uint8 = 0x19
	axp2101RegLowbatWarn   uint8 = 0x1A
	axp2101RegGpio1Ctrl    uint8 = 0x1B
	axp2101RegPwronStatus  uint8 = 0x20
	axp2101RegPwroffStatus uint8 = 0x21
	axp2101RegPwroffEn     uint8 = 0x22
	axp2101RegDcdcProt     uint8 = 0x23
	axp2101RegVoff         uint8 = 0x24
	axp2101RegPwrokCtrl    uint8 = 0x25
	axp2101RegSleepCtrl    uint8 = 0x26
	axp2101RegPwrkeyLevel  uint8 = 0x27
	axp2101RegFastPwron0   uint8 = 0x28
	axp2101RegAdcEnable    uint8 = 0x30
	axp2101RegVbatH        uint8 = 0x34
	axp2101RegTsH          uint8 = 0x36
	axp2101RegVbusH        uint8 = 0x38
	axp2101RegVsysH        uint8 = 0x3A
	axp2101RegTdieH        uint8 = 0x3C
	axp2101RegIrqEn0       uint8 = 0x40
	axp2101RegIrqStatus0   uint8 = 0x48
	axp2101RegTsCtrl       uint8 = 0x50
	axp2101RegTsHysL2H     uint8 = 0x52
	axp2101RegTsHysH2L     uint8 = 0x53
	axp2101RegVltfChg      uint8 = 0x54
	axp2101RegVhtfChg      uint8 = 0x55
	axp2101RegVltfWork     uint8 = 0x56
	axp2101RegVhtfWork     uint8 = 0x57
	axp2101RegJeitaEn      uint8 = 0x58
	axp2101RegJeitaCv      uint8 = 0x59
	axp2101RegJeitaCool    uint8 = 0x5A
	axp2101RegJeitaWarm    uint8 = 0x5B
	axp2101RegIprechg      uint8 = 0x61
	axp2101RegIcc          uint8 = 0x62
	axp2101RegIterm        uint8 = 0x63
	axp2101RegCv           uint8 = 0x64
	axp2101RegThermalReg   uint8 = 0x65
	axp2101RegChgTimer     uint8 = 0x67
	axp2101RegBatDetect    uint8 = 0x68
	axp2101RegChgledCtrl   uint8 = 0x69
	axp2101RegBtnBatVterm  uint8 = 0x6A
	axp2101RegDcdcEn       uint8 = 0x80
	axp2101RegDcdcPwm      uint8 = 0x81
	axp2101RegBatPercent   uint8 = 0xA4
)

// Charge states returned by ChargeState (STATUS2 bits 2:0).
const (
	// AXP2101ChargeTrickle is trickle charge.
	AXP2101ChargeTrickle = 0
	// AXP2101ChargePre is pre-charge.
	AXP2101ChargePre = 1
	// AXP2101ChargeCC is constant-current charge.
	AXP2101ChargeCC = 2
	// AXP2101ChargeCV is constant-voltage charge.
	AXP2101ChargeCV = 3
	// AXP2101ChargeDone is charge done.
	AXP2101ChargeDone = 4
	// AXP2101ChargeNone is not charging.
	AXP2101ChargeNone = 5
)

// Interrupt sources: a 24-bit mask, IRQ_STATUS0 in bits 7:0, IRQ_STATUS1 in
// bits 15:8 and IRQ_STATUS2 in bits 23:16.
const (
	AXP2101SourceBatUnderTempWork uint32 = 0x000001
	AXP2101SourceBatOverTempWork  uint32 = 0x000002
	AXP2101SourceBatUnderTempChg  uint32 = 0x000004
	AXP2101SourceBatOverTempChg   uint32 = 0x000008
	AXP2101SourceGaugeNewSoc      uint32 = 0x000010
	AXP2101SourceGaugeWdt         uint32 = 0x000020
	AXP2101SourceSocWarnL1        uint32 = 0x000040
	AXP2101SourceSocWarnL2        uint32 = 0x000080
	AXP2101SourcePwronPosEdge     uint32 = 0x000100
	AXP2101SourcePwronNegEdge     uint32 = 0x000200
	AXP2101SourcePwronLong        uint32 = 0x000400
	AXP2101SourcePwronShort       uint32 = 0x000800
	AXP2101SourceBatRemove        uint32 = 0x001000
	AXP2101SourceBatInsert        uint32 = 0x002000
	AXP2101SourceVbusRemove       uint32 = 0x004000
	AXP2101SourceVbusInsert       uint32 = 0x008000
	AXP2101SourceBatOverVoltage   uint32 = 0x010000
	AXP2101SourceChargerTimer     uint32 = 0x020000
	AXP2101SourceDieOverTempL1    uint32 = 0x040000
	AXP2101SourceChargeStart      uint32 = 0x080000
	AXP2101SourceChargeDone       uint32 = 0x100000
	AXP2101SourceLdoOverCurrent   uint32 = 0x400000
	AXP2101SourceWatchdogExpire   uint32 = 0x800000
)

type axp2101Rail struct {
	enReg  uint8
	enBit  uint8
	vReg   uint8
	kind   string
	vmask  uint8
	hasDvm bool
}

var axp2101Rails = map[string]axp2101Rail{
	"dcdc1":   {0x80, 0, 0x82, "dcdc1", 0x1F, false},
	"dcdc2":   {0x80, 1, 0x83, "dcdc23", 0x7F, true},
	"dcdc3":   {0x80, 2, 0x84, "dcdc23", 0x7F, true},
	"dcdc4":   {0x80, 3, 0x85, "dcdc4", 0x7F, false},
	"aldo1":   {0x90, 0, 0x92, "ldo100", 0x1F, false},
	"aldo2":   {0x90, 1, 0x93, "ldo100", 0x1F, false},
	"aldo3":   {0x90, 2, 0x94, "ldo100", 0x1F, false},
	"aldo4":   {0x90, 3, 0x95, "ldo100", 0x1F, false},
	"bldo1":   {0x90, 4, 0x96, "ldo100", 0x1F, false},
	"bldo2":   {0x90, 5, 0x97, "ldo100", 0x1F, false},
	"cpusldo": {0x90, 6, 0x98, "ldo50", 0x1F, false},
	"dldo1":   {0x90, 7, 0x99, "dldo1", 0x1F, false},
	"dldo2":   {0x91, 0, 0x9A, "ldo50", 0x1F, false},
}

type axp2101Fast struct{ offset, shift uint8 }

var axp2101FastRails = map[string]axp2101Fast{
	"dcdc1": {0, 0}, "dcdc2": {0, 2}, "dcdc3": {0, 4}, "dcdc4": {0, 6},
	"aldo1": {1, 2}, "aldo2": {1, 4}, "aldo3": {1, 6},
	"aldo4": {2, 0}, "bldo1": {2, 2}, "bldo2": {2, 4}, "cpusldo": {2, 6},
	"dldo1": {3, 0}, "dldo2": {3, 2},
}

// kind -> start V, step V, max code.
var axp2101RailKinds = map[string][3]float64{
	"dcdc1":  {1.5, 0.1, 19},
	"ldo100": {0.5, 0.1, 30},
	"ldo50":  {0.5, 0.05, 19},
	"dldo1":  {0.5, 0.1, 28},
}

// Maximum ICC (A) by CV code (datasheet Table 5-5).
var axp2101IccLimit = map[uint8]float64{1: 1.5, 2: 1.5, 3: 1.4, 4: 1.1, 5: 1.0}

func axp2101Round(x float64) int { return int(math.Floor(x + 0.5)) }

// axp2101StepCode returns the nearest step code or an error outside the range.
func axp2101StepCode(value, start, step float64, maxCode int, what string) (uint8, error) {
	code := axp2101Round((value - start) / step)
	if code < 0 || code > maxCode || math.Abs(value-(start+float64(code)*step)) > step/2+1e-9 {
		return 0, fmt.Errorf("AXP2101 %s out of range: %v", what, value)
	}
	return uint8(code), nil
}

// axp2101Index returns the index of value in keys (the register code for the
// enumerated settings) or an error.
func axp2101Index(value float64, keys []float64, what string) (uint8, error) {
	for i, k := range keys {
		if math.Abs(k-value) < 1e-6 {
			return uint8(i), nil
		}
	}
	return 0, fmt.Errorf("AXP2101 %s unsupported value: %v", what, value)
}

func axp2101RailCode(kind string, volts float64, name string) (uint8, error) {
	if k, ok := axp2101RailKinds[kind]; ok {
		return axp2101StepCode(volts, k[0], k[1], int(k[2]), name+" voltage")
	}
	top := 87
	if kind == "dcdc4" {
		top = 102
	}
	if volts <= 1.21 {
		return axp2101StepCode(volts, 0.5, 0.01, 70, name+" voltage")
	}
	c, err := axp2101StepCode(volts, 1.22, 0.02, top-71, name+" voltage")
	return 71 + c, err
}

func axp2101RailVolts(kind string, code uint8) float64 {
	if k, ok := axp2101RailKinds[kind]; ok {
		return k[0] + float64(code)*k[1]
	}
	if code <= 70 {
		return 0.5 + float64(code)*0.01
	}
	return 1.22 + float64(code-71)*0.02
}

func axp2101Bit(on bool, bit uint8) uint8 {
	if on {
		return bit
	}
	return 0
}

// AXP2101Minimal is the AXP2101 single-cell power-management IC — minimal
// interface: battery and power-source monitoring.
//
// At construction the ADC channels (VBAT, TS, VBUS, VSYS, TDIE) and the fuel
// gauge are enabled; charger, input limits, rails, power key, interrupts and
// watchdog keep their power-on (eFuse) configuration.
type AXP2101Minimal struct {
	conn connection.RegisterConnection
}

// NewAXP2101Minimal runs the initialisation sequence: bus check (read of
// STATUS1), ADC channels on, fuel gauge on, 20 ms wait for the first
// conversions. conn must be bound to I2C address 0x34.
func NewAXP2101Minimal(conn connection.RegisterConnection) (*AXP2101Minimal, error) {
	c := &AXP2101Minimal{conn: conn}
	if _, err := c.readReg(axp2101RegStatus1); err != nil {
		return nil, fmt.Errorf("AXP2101 not responding at I2C 0x34: %w", err)
	}
	if err := c.rmw(axp2101RegAdcEnable, 0x1F, 0x1F); err != nil {
		return nil, err
	}
	if err := c.rmw(axp2101RegModuleEnable, 0x08, 0x08); err != nil {
		return nil, err
	}
	time.Sleep(20 * time.Millisecond)
	return c, nil
}

// readBytes reads n bytes from reg, retrying once on a bus error.
func (c *AXP2101Minimal) readBytes(reg uint8, n int) ([]byte, error) {
	b, err := c.conn.ReadReg(uint32(reg), n)
	if err != nil {
		b, err = c.conn.ReadReg(uint32(reg), n)
	}
	return b, err
}

func (c *AXP2101Minimal) readReg(reg uint8) (uint8, error) {
	b, err := c.readBytes(reg, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (c *AXP2101Minimal) writeReg(reg, value uint8) error {
	err := c.conn.WriteReg(uint32(reg), []byte{value})
	if err != nil {
		err = c.conn.WriteReg(uint32(reg), []byte{value})
	}
	return err
}

// rmw replaces only the bits in mask, preserving every other bit.
func (c *AXP2101Minimal) rmw(reg, mask, value uint8) error {
	cur, err := c.readReg(reg)
	if err != nil {
		return err
	}
	return c.writeReg(reg, (cur&^mask)|(value&mask))
}

// command writes the current value with a self-clearing command bit set.
func (c *AXP2101Minimal) command(reg, bit uint8) error {
	cur, err := c.readReg(reg)
	if err != nil {
		return err
	}
	return c.writeReg(reg, cur|bit)
}

// adc14 reads a 14-bit ADC pair, high byte first.
func (c *AXP2101Minimal) adc14(reg uint8) (uint16, error) {
	hi, err := c.readReg(reg)
	if err != nil {
		return 0, err
	}
	lo, err := c.readReg(reg + 1)
	if err != nil {
		return 0, err
	}
	return uint16(hi&0x3F)<<8 | uint16(lo), nil
}

// BatteryVoltage reads the battery voltage in volts (1 mV / LSB).
func (c *AXP2101Minimal) BatteryVoltage() (float64, error) {
	v, err := c.adc14(axp2101RegVbatH)
	return float64(v) * 0.001, err
}

// BatteryPercent reads the fuel-gauge state of charge, 0-100 %.
func (c *AXP2101Minimal) BatteryPercent() (int, error) {
	v, err := c.readReg(axp2101RegBatPercent)
	return int(v & 0x7F), err
}

// ChargeState reads the charger state (STATUS2 bits 2:0), one of the
// AXP2101Charge* constants.
func (c *AXP2101Minimal) ChargeState() (int, error) {
	v, err := c.readReg(axp2101RegStatus2)
	return int(v & 0x07), err
}

// VbusPresent reports whether a good input source is connected
// (STATUS1.VBUS_GOOD).
func (c *AXP2101Minimal) VbusPresent() (bool, error) {
	v, err := c.readReg(axp2101RegStatus1)
	return v&0x20 != 0, err
}

// AXP2101Readings holds every measurement returned by ReadAll.
type AXP2101Readings struct {
	BatteryVoltage float64
	VbusVoltage    float64
	SystemVoltage  float64
	TSVoltage      float64
	DieTemperature float64
	BatteryPercent int
	ChargeState    int
	VbusPresent    bool
}

// AXP2101Status holds the decoded STATUS1 / STATUS2 flags returned by Status.
type AXP2101Status struct {
	VbusGood          bool
	BatfetOpen        bool
	BatteryPresent    bool
	BatteryActive     bool
	ThermalRegulation bool
	CurrentLimit      bool
	// BatteryDirection is "standby", "charge", "discharge" or "reserved".
	BatteryDirection string
	SystemOn         bool
	Vindpm           bool
	ChargeState      int
}

// AXP2101Full is the AXP2101 — full interface. It embeds AXP2101Minimal and
// adds the remaining ADC channels, charger, battery-temperature, power rail,
// power-key, sleep, watchdog, fuel-gauge and interrupt control.
//
// Dangerous operations: disabling or re-voltaging the rail that powers the
// host (commonly DCDC1 or DCDC3), PowerOff, the power-off thresholds, charge
// voltage / current beyond the cell's limits and SetBatfetOffEnable can power
// the board down or damage the battery.
type AXP2101Full struct {
	AXP2101Minimal

	mu          sync.Mutex
	callback    func(status uint32)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewAXP2101Full creates the Full driver; the initialisation sequence is the
// same as NewAXP2101Minimal.
func NewAXP2101Full(conn connection.RegisterConnection) (*AXP2101Full, error) {
	m, err := NewAXP2101Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &AXP2101Full{AXP2101Minimal: *m}, nil
}

// VbusVoltage reads the VBUS voltage in volts.
func (c *AXP2101Full) VbusVoltage() (float64, error) {
	v, err := c.adc14(axp2101RegVbusH)
	return float64(v) * 0.001, err
}

// SystemVoltage reads the system (VSYS) voltage in volts.
func (c *AXP2101Full) SystemVoltage() (float64, error) {
	v, err := c.adc14(axp2101RegVsysH)
	return float64(v) * 0.001, err
}

// TSVoltage reads the TS pin voltage in volts (0.5 mV / LSB).
func (c *AXP2101Full) TSVoltage() (float64, error) {
	v, err := c.adc14(axp2101RegTsH)
	return float64(v) * 0.0005, err
}

// DieTemperature reads the die temperature in °C using the vendor XPowersLib
// formula (not from the datasheet; verify on hardware).
func (c *AXP2101Full) DieTemperature() (float64, error) {
	v, err := c.adc14(axp2101RegTdieH)
	return 22 + (7274-float64(v))/20, err
}

// ReadAll reads every measurement.
func (c *AXP2101Full) ReadAll() (AXP2101Readings, error) {
	var r AXP2101Readings
	var err error
	if r.BatteryVoltage, err = c.BatteryVoltage(); err != nil {
		return r, err
	}
	if r.VbusVoltage, err = c.VbusVoltage(); err != nil {
		return r, err
	}
	if r.SystemVoltage, err = c.SystemVoltage(); err != nil {
		return r, err
	}
	if r.TSVoltage, err = c.TSVoltage(); err != nil {
		return r, err
	}
	if r.DieTemperature, err = c.DieTemperature(); err != nil {
		return r, err
	}
	if r.BatteryPercent, err = c.BatteryPercent(); err != nil {
		return r, err
	}
	if r.ChargeState, err = c.ChargeState(); err != nil {
		return r, err
	}
	r.VbusPresent, err = c.VbusPresent()
	return r, err
}

// Status decodes STATUS1 and STATUS2.
func (c *AXP2101Full) Status() (AXP2101Status, error) {
	var s AXP2101Status
	s1, err := c.readReg(axp2101RegStatus1)
	if err != nil {
		return s, err
	}
	s2, err := c.readReg(axp2101RegStatus2)
	if err != nil {
		return s, err
	}
	s.VbusGood = s1&0x20 != 0
	s.BatfetOpen = s1&0x10 != 0
	s.BatteryPresent = s1&0x08 != 0
	s.BatteryActive = s1&0x04 != 0
	s.ThermalRegulation = s1&0x02 != 0
	s.CurrentLimit = s1&0x01 != 0
	s.BatteryDirection = [4]string{"standby", "charge", "discharge", "reserved"}[(s2>>5)&3]
	s.SystemOn = s2&0x10 != 0
	s.Vindpm = s2&0x08 != 0
	s.ChargeState = int(s2 & 0x07)
	return s, nil
}

// SetADCChannels selects the enabled ADC channels (ADC_ENABLE).
func (c *AXP2101Full) SetADCChannels(vbat, ts, vbus, vsys, tdie, gpadc bool) error {
	v := axp2101Bit(vbat, 1) | axp2101Bit(ts, 2) | axp2101Bit(vbus, 4) |
		axp2101Bit(vsys, 8) | axp2101Bit(tdie, 16) | axp2101Bit(gpadc, 32)
	return c.rmw(axp2101RegAdcEnable, 0x3F, v)
}

// SetADCLowFrequency selects slow (64 s) ADC sampling when powered off on
// battery only (TS_H bit 7).
func (c *AXP2101Full) SetADCLowFrequency(enabled bool) error {
	return c.rmw(axp2101RegTsH, 0x80, axp2101Bit(enabled, 0x80))
}

// SetCharging enables or disables main-cell charging (MODULE_ENABLE bit 1).
func (c *AXP2101Full) SetCharging(enabled bool) error {
	return c.rmw(axp2101RegModuleEnable, 0x02, axp2101Bit(enabled, 0x02))
}

// SetChargeVoltage sets the charge target voltage: 4.0, 4.1, 4.2, 4.35 or
// 4.4 V (the last two only for high-voltage cells).
func (c *AXP2101Full) SetChargeVoltage(volts float64) error {
	i, err := axp2101Index(volts, []float64{4.0, 4.1, 4.2, 4.35, 4.4}, "charge voltage")
	if err != nil {
		return err
	}
	return c.rmw(axp2101RegCv, 0x07, i+1)
}

// SetChargeCurrent sets the constant-current charge current: 0-0.2 A in
// 25 mA steps, 0.2-1.5 A in 100 mA steps, capped by the limit of the
// currently programmed charge voltage.
func (c *AXP2101Full) SetChargeCurrent(amps float64) error {
	if amps < 0 || amps > 1.5+1e-9 {
		return fmt.Errorf("AXP2101 charge current out of range: %v", amps)
	}
	cv, err := c.readReg(axp2101RegCv)
	if err != nil {
		return err
	}
	if limit, ok := axp2101IccLimit[cv&0x07]; ok && amps > limit+1e-9 {
		return fmt.Errorf("AXP2101 charge current %v A exceeds %v A limit for this charge voltage", amps, limit)
	}
	var code int
	if amps <= 0.25 {
		code = axp2101Round(amps / 0.025)
		if code > 8 {
			code = 8
		}
	} else {
		code = 8 + axp2101Round((amps-0.2)/0.1)
	}
	return c.rmw(axp2101RegIcc, 0x1F, uint8(code))
}

// SetPrechargeCurrent sets the pre-charge current: 0-0.2 A in 25 mA steps.
func (c *AXP2101Full) SetPrechargeCurrent(amps float64) error {
	code, err := axp2101StepCode(amps, 0, 0.025, 8, "precharge current")
	if err != nil {
		return err
	}
	return c.rmw(axp2101RegIprechg, 0x0F, code)
}

// SetTermination sets the charge termination current (0-0.2 A in 25 mA
// steps) and the termination enable bit.
func (c *AXP2101Full) SetTermination(amps float64, enabled bool) error {
	code, err := axp2101StepCode(amps, 0, 0.025, 8, "termination current")
	if err != nil {
		return err
	}
	return c.rmw(axp2101RegIterm, 0x1F, code|axp2101Bit(enabled, 0x10))
}

// SetInputCurrentLimit sets the input current limit: 0.1, 0.5, 0.9, 1.0,
// 1.5 or 2.0 A.
func (c *AXP2101Full) SetInputCurrentLimit(amps float64) error {
	i, err := axp2101Index(amps, []float64{0.1, 0.5, 0.9, 1.0, 1.5, 2.0}, "input current limit")
	if err != nil {
		return err
	}
	return c.rmw(axp2101RegIinlim, 0x07, i)
}

// SetInputVoltageLimit sets the input voltage limit (VINDPM): 3.88-5.08 V in
// 80 mV steps.
func (c *AXP2101Full) SetInputVoltageLimit(volts float64) error {
	code, err := axp2101StepCode(volts, 3.88, 0.08, 15, "input voltage limit")
	if err != nil {
		return err
	}
	return c.rmw(axp2101RegVindpm, 0x0F, code)
}

// SetMinSystemVoltage sets the minimum system voltage: 3.2-3.9 V in 100 mV
// steps.
func (c *AXP2101Full) SetMinSystemVoltage(volts float64) error {
	code, err := axp2101StepCode(volts, 3.2, 0.1, 7, "minimum system voltage")
	if err != nil {
		return err
	}
	return c.rmw(axp2101RegMinVsys, 0x07, code)
}

// SetChargeTimers configures the charger safety timers (CHG_TIMER).
// prechargeMin is 40, 50, 60 or 70 min; doneHours is 5, 8, 12 or 20 h;
// slowInDpm runs the timers at half speed during input DPM / thermal
// regulation. The defaults are 60, 12, true, true, true.
func (c *AXP2101Full) SetChargeTimers(prechargeMin, doneHours int, prechargeEnabled, doneEnabled, slowInDpm bool) error {
	pre, err := axp2101Index(float64(prechargeMin), []float64{40, 50, 60, 70}, "precharge timer")
	if err != nil {
		return err
	}
	done, err := axp2101Index(float64(doneHours), []float64{5, 8, 12, 20}, "done timer")
	if err != nil {
		return err
	}
	v := pre | axp2101Bit(prechargeEnabled, 0x04) | done<<4 | axp2101Bit(doneEnabled, 0x40) | axp2101Bit(slowInDpm, 0x80)
	return c.rmw(axp2101RegChgTimer, 0xF7, v)
}

// SetThermalRegulation sets the charger thermal-regulation threshold: 60,
// 80, 100 or 120 °C.
func (c *AXP2101Full) SetThermalRegulation(celsius int) error {
	i, err := axp2101Index(float64(celsius), []float64{60, 80, 100, 120}, "thermal regulation")
	if err != nil {
		return err
	}
	return c.rmw(axp2101RegThermalReg, 0x03, i)
}

// SetDieProtection configures die over-temperature level 1 (115, 125 or
// 135 °C; default 125) and die-temperature detection.
func (c *AXP2101Full) SetDieProtection(level1Celsius int, detect bool) error {
	i, err := axp2101Index(float64(level1Celsius), []float64{115, 125, 135}, "die protection")
	if err != nil {
		return err
	}
	return c.rmw(axp2101RegDieTempCtrl, 0x07, i<<1|axp2101Bit(detect, 1))
}

// SetBatteryDetection enables or disables battery detection (BAT_DETECT).
func (c *AXP2101Full) SetBatteryDetection(enabled bool) error {
	return c.rmw(axp2101RegBatDetect, 0x01, axp2101Bit(enabled, 1))
}

// SetBackupCharging configures charging of the backup (coin) cell:
// termination voltage 2.6-3.3 V in 100 mV steps (default 2.9).
func (c *AXP2101Full) SetBackupCharging(enabled bool, volts float64) error {
	code, err := axp2101StepCode(volts, 2.6, 0.1, 7, "backup charge voltage")
	if err != nil {
		return err
	}
	if err := c.rmw(axp2101RegBtnBatVterm, 0x07, code); err != nil {
		return err
	}
	return c.rmw(axp2101RegModuleEnable, 0x04, axp2101Bit(enabled, 0x04))
}

// SetChgled configures the CHGLED pin. mode is "off", "type_a", "type_b" or
// "manual"; for "manual", output is "hiz", "blink_1hz", "blink_4hz" or "low".
func (c *AXP2101Full) SetChgled(mode, output string) error {
	modes := map[string]uint8{"off": 0x00, "type_a": 0x01, "type_b": 0x03, "manual": 0x05}
	outs := map[string]uint8{"hiz": 0, "blink_1hz": 1, "blink_4hz": 2, "low": 3}
	v, ok := modes[mode]
	if !ok {
		return fmt.Errorf("AXP2101 unknown CHGLED mode: %s", mode)
	}
	o, ok := outs[output]
	if !ok {
		return fmt.Errorf("AXP2101 unknown CHGLED output: %s", output)
	}
	if mode == "manual" {
		v |= o << 4
	}
	return c.rmw(axp2101RegChgledCtrl, 0x37, v)
}

// ConfigureTS configures the TS pin (TS_CTRL): batterySensor selects the
// battery thermistor (false = external input that does not affect the
// charger), currentUA is 20, 40, 50 or 60 µA, source is "off", "when_adc",
// "when_ts" or "always".
func (c *AXP2101Full) ConfigureTS(batterySensor bool, currentUA int, source string) error {
	sources := map[string]uint8{"off": 0, "when_adc": 1, "when_ts": 2, "always": 3}
	s, ok := sources[source]
	if !ok {
		return fmt.Errorf("AXP2101 unknown TS source: %s", source)
	}
	cur, err := axp2101Index(float64(currentUA), []float64{20, 40, 50, 60}, "TS current")
	if err != nil {
		return err
	}
	return c.rmw(axp2101RegTsCtrl, 0x1F, cur|s<<2|axp2101Bit(!batterySensor, 0x10))
}

// SetTSLimits sets the battery-temperature limits as TS pin voltages in
// volts: charge cold (32 mV steps), charge hot (2 mV), work cold (32 mV),
// work hot (2 mV).
func (c *AXP2101Full) SetTSLimits(chargeCold, chargeHot, workCold, workHot float64) error {
	type limit struct {
		reg   uint8
		value float64
		step  float64
		what  string
	}
	limits := []limit{
		{axp2101RegVltfChg, chargeCold, 0.032, "charge cold limit"},
		{axp2101RegVhtfChg, chargeHot, 0.002, "charge hot limit"},
		{axp2101RegVltfWork, workCold, 0.032, "work cold limit"},
		{axp2101RegVhtfWork, workHot, 0.002, "work hot limit"},
	}
	codes := make([]uint8, len(limits))
	for i, l := range limits {
		code, err := axp2101StepCode(l.value, 0, l.step, 255, l.what)
		if err != nil {
			return err
		}
		codes[i] = code
	}
	for i, l := range limits {
		if err := c.writeReg(l.reg, codes[i]); err != nil {
			return err
		}
	}
	return nil
}

// SetTSHysteresis sets the TS threshold hysteresis in volts: low to normal
// (16 mV steps, default 0.032) and high to normal (4 mV steps, default 0.004).
func (c *AXP2101Full) SetTSHysteresis(lowToNormal, highToNormal float64) error {
	l2h, err := axp2101StepCode(lowToNormal, 0, 0.016, 255, "TS low hysteresis")
	if err != nil {
		return err
	}
	h2l, err := axp2101StepCode(highToNormal, 0, 0.004, 255, "TS high hysteresis")
	if err != nil {
		return err
	}
	if err := c.writeReg(axp2101RegTsHysL2H, l2h); err != nil {
		return err
	}
	return c.writeReg(axp2101RegTsHysH2L, h2l)
}

// ConfigureJEITA configures the JEITA charging profile. coolVoltage (T2, TS
// voltage in V, 16 mV steps) and warmVoltage (T3, 8 mV steps) are left
// unchanged when negative. coolDrop and warmDrop are charge-voltage gears
// 0-2. The defaults are cool half current true, warm half current false,
// drops 0 and 1.
func (c *AXP2101Full) ConfigureJEITA(enabled bool, coolVoltage, warmVoltage float64, coolHalfCurrent, warmHalfCurrent bool, coolDrop, warmDrop int) error {
	if coolDrop < 0 || coolDrop > 2 || warmDrop < 0 || warmDrop > 2 {
		return fmt.Errorf("AXP2101 JEITA voltage drop must be 0-2")
	}
	v := uint8(coolDrop) | uint8(warmDrop)<<2 | axp2101Bit(coolHalfCurrent, 0x10) | axp2101Bit(warmHalfCurrent, 0x40)
	if err := c.rmw(axp2101RegJeitaCv, 0x5F, v); err != nil {
		return err
	}
	if coolVoltage >= 0 {
		code, err := axp2101StepCode(coolVoltage, 0, 0.016, 255, "JEITA cool")
		if err != nil {
			return err
		}
		if err := c.writeReg(axp2101RegJeitaCool, code); err != nil {
			return err
		}
	}
	if warmVoltage >= 0 {
		code, err := axp2101StepCode(warmVoltage, 0, 0.008, 255, "JEITA warm")
		if err != nil {
			return err
		}
		if err := c.writeReg(axp2101RegJeitaWarm, code); err != nil {
			return err
		}
	}
	return c.rmw(axp2101RegJeitaEn, 0x01, axp2101Bit(enabled, 1))
}

func axp2101LookupRail(name string) (axp2101Rail, error) {
	r, ok := axp2101Rails[name]
	if !ok {
		return r, fmt.Errorf("AXP2101 unknown rail: %s", name)
	}
	return r, nil
}

// GetRail reads the state of a power rail: whether it is enabled and its
// programmed voltage. name is "dcdc1".."dcdc4", "aldo1".."aldo4", "bldo1",
// "bldo2", "cpusldo", "dldo1" or "dldo2".
func (c *AXP2101Full) GetRail(name string) (bool, float64, error) {
	r, err := axp2101LookupRail(name)
	if err != nil {
		return false, 0, err
	}
	en, err := c.readReg(r.enReg)
	if err != nil {
		return false, 0, err
	}
	v, err := c.readReg(r.vReg)
	if err != nil {
		return false, 0, err
	}
	return en&(1<<r.enBit) != 0, axp2101RailVolts(r.kind, v&r.vmask), nil
}

// SetRailVoltage sets a rail voltage, rounded to the nearest step inside the
// rail's range; a value outside it returns an error.
func (c *AXP2101Full) SetRailVoltage(name string, volts float64) error {
	r, err := axp2101LookupRail(name)
	if err != nil {
		return err
	}
	code, err := axp2101RailCode(r.kind, volts, name)
	if err != nil {
		return err
	}
	return c.rmw(r.vReg, r.vmask, code)
}

// SetRailEnabled enables or disables a rail. Disabling the rail that powers
// the host cuts the host's power.
func (c *AXP2101Full) SetRailEnabled(name string, enabled bool) error {
	r, err := axp2101LookupRail(name)
	if err != nil {
		return err
	}
	return c.rmw(r.enReg, 1<<r.enBit, axp2101Bit(enabled, 1<<r.enBit))
}

// ConfigureDCDC configures one DCDC converter ("dcdc1".."dcdc4"): alwaysPWM
// forces PWM instead of automatic PWM/PFM; dvm enables dynamic voltage
// scaling for dcdc2 / dcdc3 (1 = on, 0 = off, -1 = leave unchanged).
func (c *AXP2101Full) ConfigureDCDC(name string, alwaysPWM bool, dvm int) error {
	r, err := axp2101LookupRail(name)
	if err != nil || r.enReg != 0x80 {
		return fmt.Errorf("AXP2101 not a DCDC: %s", name)
	}
	bit := uint8(1) << (r.enBit + 2)
	if err := c.rmw(axp2101RegDcdcPwm, bit, axp2101Bit(alwaysPWM, bit)); err != nil {
		return err
	}
	if dvm >= 0 {
		if !r.hasDvm {
			return fmt.Errorf("AXP2101 DVM is only available on dcdc2 / dcdc3")
		}
		return c.rmw(r.vReg, 0x80, axp2101Bit(dvm != 0, 0x80))
	}
	return nil
}

// ConfigureDCDCGlobal configures settings shared by all DCDC converters:
// force CCM, slow DVM ramp (31.25 µs per step), frequency spread and its
// 100 kHz range, and the under-voltage debounce (60, 120, 180 or 240 µs).
func (c *AXP2101Full) ConfigureDCDCGlobal(forceCCM, slowRamp, frequencySpread, spread100kHz bool, uvpDebounceUs int) error {
	deb, err := axp2101Index(float64(uvpDebounceUs), []float64{60, 120, 180, 240}, "UVP debounce")
	if err != nil {
		return err
	}
	if err := c.rmw(axp2101RegDcdcEn, 0x60, axp2101Bit(forceCCM, 0x40)|axp2101Bit(slowRamp, 0x20)); err != nil {
		return err
	}
	return c.rmw(axp2101RegDcdcPwm, 0xC3, axp2101Bit(frequencySpread, 0x80)|axp2101Bit(spread100kHz, 0x40)|deb)
}

// SetOffDischarge enables output discharge when a rail is off
// (COMMON_CONFIG bit 5).
func (c *AXP2101Full) SetOffDischarge(enabled bool) error {
	return c.rmw(axp2101RegCommonConfig, 0x20, axp2101Bit(enabled, 0x20))
}

// PowerOff performs a soft power-off: every output except RTCLDO turns off.
func (c *AXP2101Full) PowerOff() error { return c.command(axp2101RegCommonConfig, 0x01) }

// Restart restarts the system (power-off / power-on).
func (c *AXP2101Full) Restart() error { return c.command(axp2101RegCommonConfig, 0x02) }

// Sleep enters sleep; the host disables / re-voltages rails first.
func (c *AXP2101Full) Sleep() error { return c.command(axp2101RegSleepCtrl, 0x01) }

// Wakeup wakes the PMU from sleep.
func (c *AXP2101Full) Wakeup() error { return c.command(axp2101RegSleepCtrl, 0x02) }

// ConfigureSleep configures sleep / wake-up behaviour (SLEEP_CTRL): wake on
// the IRQ pin, PWROK low during wake-up, restore the voltages from before
// sleep. The defaults are false, true, false.
func (c *AXP2101Full) ConfigureSleep(wakeOnIRQ, pwrokLow, restoreVoltages bool) error {
	v := axp2101Bit(wakeOnIRQ, 0x10) | axp2101Bit(pwrokLow, 0x08) | axp2101Bit(restoreVoltages, 0x04)
	return c.rmw(axp2101RegSleepCtrl, 0x1C, v)
}

// PowerOnSource reads the PWRON_STATUS flags.
func (c *AXP2101Full) PowerOnSource() (uint8, error) { return c.readReg(axp2101RegPwronStatus) }

// PowerOffSource reads the PWROFF_STATUS flags.
func (c *AXP2101Full) PowerOffSource() (uint8, error) { return c.readReg(axp2101RegPwroffStatus) }

// ConfigurePowerKey configures the power-key timing and actions: onMs is
// 128 / 512 / 1000 / 2000 ms, offS is 4 / 6 / 8 / 10 s, irqS is 1 / 1.5 / 2 /
// 2.5 s. The defaults are 512, 6, 1.5, true, false, false.
func (c *AXP2101Full) ConfigurePowerKey(onMs int, offS, irqS float64, longPressOff, restartInstead, forceOff16s bool) error {
	on, err := axp2101Index(float64(onMs), []float64{128, 512, 1000, 2000}, "power key on time")
	if err != nil {
		return err
	}
	off, err := axp2101Index(offS, []float64{4, 6, 8, 10}, "power key off time")
	if err != nil {
		return err
	}
	irq, err := axp2101Index(irqS, []float64{1, 1.5, 2, 2.5}, "power key irq time")
	if err != nil {
		return err
	}
	if err := c.rmw(axp2101RegPwrkeyLevel, 0x3F, on|off<<2|irq<<4); err != nil {
		return err
	}
	if err := c.rmw(axp2101RegPwroffEn, 0x03, axp2101Bit(longPressOff, 0x02)|axp2101Bit(restartInstead, 0x01)); err != nil {
		return err
	}
	return c.rmw(axp2101RegCommonConfig, 0x04, axp2101Bit(forceOff16s, 0x04))
}

// ConfigurePowerOff configures the power-off sources and thresholds: die
// over-temperature level 2, the system power-off voltage (2.6-3.3 V in
// 100 mV steps), DCDC over-voltage and the mask of DCDC1-4 whose
// under-voltage turns the PMIC off (bits 0-3). The defaults are true, 2.6,
// true, 0x0F.
func (c *AXP2101Full) ConfigurePowerOff(dieOverTemp bool, offVoltage float64, dcdcOvp bool, dcdcUvp uint8) error {
	code, err := axp2101StepCode(offVoltage, 2.6, 0.1, 7, "power-off voltage")
	if err != nil {
		return err
	}
	if err := c.rmw(axp2101RegPwroffEn, 0x04, axp2101Bit(dieOverTemp, 0x04)); err != nil {
		return err
	}
	if err := c.rmw(axp2101RegVoff, 0x07, code); err != nil {
		return err
	}
	return c.rmw(axp2101RegDcdcProt, 0x2F, axp2101Bit(dcdcOvp, 0x20)|(dcdcUvp&0x0F))
}

// ConfigurePwrok configures the PWROK output: check after all outputs are
// valid, delay 8 / 16 / 32 / 64 ms, 4 ms power-off delay, reverse power-off
// sequence, restart when PWROK is pulled low. The defaults are true, 8,
// true, false, false.
func (c *AXP2101Full) ConfigurePwrok(check bool, delayMs int, offDelay, reverseOffSequence, restartOnLow bool) error {
	delay, err := axp2101Index(float64(delayMs), []float64{8, 16, 32, 64}, "PWROK delay")
	if err != nil {
		return err
	}
	v := delay | axp2101Bit(reverseOffSequence, 0x04) | axp2101Bit(offDelay, 0x08) | axp2101Bit(check, 0x10)
	if err := c.rmw(axp2101RegPwrokCtrl, 0x1F, v); err != nil {
		return err
	}
	return c.rmw(axp2101RegCommonConfig, 0x08, axp2101Bit(restartOnLow, 0x08))
}

// SetBatfetOffEnable turns the BATFET on when powered off on battery only
// (BATFET_CTRL bit 3).
func (c *AXP2101Full) SetBatfetOffEnable(enabled bool) error {
	return c.rmw(axp2101RegBatfetCtrl, 0x08, axp2101Bit(enabled, 0x08))
}

// ConfigureFastPoweron configures fast power-on / wake-up and, optionally,
// the per-rail start sequence. sequence maps a rail name to a start-sequence
// code 0-2, or -1 to disable the rail; a nil map leaves the sequence
// untouched.
func (c *AXP2101Full) ConfigureFastPoweron(poweron, wakeup bool, sequence map[string]int) error {
	for name, code := range sequence {
		f, ok := axp2101FastRails[name]
		if !ok {
			return fmt.Errorf("AXP2101 unknown rail: %s", name)
		}
		if code < 0 {
			code = 3
		}
		if code > 3 {
			return fmt.Errorf("AXP2101 start-sequence code must be 0-2 or -1")
		}
		if err := c.rmw(axp2101RegFastPwron0+f.offset, 3<<f.shift, uint8(code)<<f.shift); err != nil {
			return err
		}
	}
	return c.rmw(axp2101RegFastPwron0+3, 0xC0, axp2101Bit(poweron, 0x80)|axp2101Bit(wakeup, 0x40))
}

// ConfigureWatchdog configures the watchdog: timeoutS is 1, 2, 4, 8, 16, 32,
// 64 or 128 s; action is "irq", "reset", "reset_pwrok" or "power_cycle".
func (c *AXP2101Full) ConfigureWatchdog(enabled bool, timeoutS int, action string) error {
	actions := map[string]uint8{"irq": 0, "reset": 1, "reset_pwrok": 2, "power_cycle": 3}
	a, ok := actions[action]
	if !ok {
		return fmt.Errorf("AXP2101 unknown watchdog action: %s", action)
	}
	code, err := axp2101Index(float64(timeoutS), []float64{1, 2, 4, 8, 16, 32, 64, 128}, "watchdog timeout")
	if err != nil {
		return err
	}
	if err := c.rmw(axp2101RegWatchdogCtrl, 0x37, code|a<<4); err != nil {
		return err
	}
	return c.rmw(axp2101RegModuleEnable, 0x01, axp2101Bit(enabled, 1))
}

// FeedWatchdog clears (feeds) the watchdog (WATCHDOG_CTRL bit 3).
func (c *AXP2101Full) FeedWatchdog() error { return c.command(axp2101RegWatchdogCtrl, 0x08) }

// ConfigureBatteryWarning sets the low-battery warning levels (LOWBAT_WARN):
// level2 5-20 % (default 10), level1 0-15 % (default 1).
func (c *AXP2101Full) ConfigureBatteryWarning(level2, level1 int) error {
	if level2 < 5 || level2 > 20 || level1 < 0 || level1 > 15 {
		return fmt.Errorf("AXP2101 battery warning levels out of range")
	}
	return c.writeReg(axp2101RegLowbatWarn, uint8((level2-5)<<4|level1))
}

// SetGaugeEnabled enables or disables the fuel gauge (MODULE_ENABLE bit 3).
func (c *AXP2101Full) SetGaugeEnabled(enabled bool) error {
	return c.rmw(axp2101RegModuleEnable, 0x08, axp2101Bit(enabled, 0x08))
}

// ResetGauge resets the fuel gauge; full selects the deeper reset that also
// clears gauge data beyond the registers.
func (c *AXP2101Full) ResetGauge(full bool) error {
	bit := uint8(0x08)
	if full {
		bit = 0x04
	}
	return c.command(axp2101RegGaugeReset, bit)
}

// SetGPIO1 drives the open-drain GPIO1 output: true drives low, false is
// Hi-Z.
func (c *AXP2101Full) SetGPIO1(low bool) error {
	return c.rmw(axp2101RegGpio1Ctrl, 0x0C, axp2101Bit(low, 0x04))
}

// ReadDataBuffer reads one of the four general-purpose bytes (index 0-3).
func (c *AXP2101Full) ReadDataBuffer(index int) (uint8, error) {
	if index < 0 || index > 3 {
		return 0, fmt.Errorf("AXP2101 data buffer index must be 0-3")
	}
	return c.readReg(axp2101RegDataBuffer0 + uint8(index))
}

// WriteDataBuffer writes one of the four general-purpose bytes (index 0-3);
// it survives soft restarts, not a power-on reset.
func (c *AXP2101Full) WriteDataBuffer(index int, value uint8) error {
	if index < 0 || index > 3 {
		return fmt.Errorf("AXP2101 data buffer index must be 0-3")
	}
	return c.writeReg(axp2101RegDataBuffer0+uint8(index), value)
}

// EnableInterrupt sets the bits of source (an OR of AXP2101Source*
// constants) in IRQ_EN0..2.
func (c *AXP2101Full) EnableInterrupt(source uint32) error {
	for k := uint8(0); k < 3; k++ {
		if bits := uint8(source >> (8 * k)); bits != 0 {
			if err := c.rmw(axp2101RegIrqEn0+k, bits, bits); err != nil {
				return err
			}
		}
	}
	return nil
}

// DisableInterrupt clears the bits of source in IRQ_EN0..2.
func (c *AXP2101Full) DisableInterrupt(source uint32) error {
	for k := uint8(0); k < 3; k++ {
		if bits := uint8(source >> (8 * k)); bits != 0 {
			if err := c.rmw(axp2101RegIrqEn0+k, bits, 0); err != nil {
				return err
			}
		}
	}
	return nil
}

// PollInterrupt reads IRQ_STATUS0..2 and writes back exactly the bits it saw
// set (so an event arriving between the read and the write is not lost). It
// returns the 24-bit status: IRQ_STATUS0 in bits 7:0, IRQ_STATUS1 in 15:8,
// IRQ_STATUS2 in 23:16.
func (c *AXP2101Full) PollInterrupt() (uint32, error) {
	var status uint32
	for k := uint8(0); k < 3; k++ {
		v, err := c.readReg(axp2101RegIrqStatus0 + k)
		if err != nil {
			return 0, err
		}
		if v != 0 {
			if err := c.writeReg(axp2101RegIrqStatus0+k, v); err != nil {
				return 0, err
			}
		}
		status |= uint32(v) << (8 * k)
	}
	return status, nil
}

// OnInterrupt subscribes callback to IRQ pin assertions; it receives the
// 24-bit status returned by PollInterrupt. With the connection's IntPin
// wired it runs on every falling edge, otherwise a 5 ms polling goroutine
// calls back whenever an event is pending.
func (c *AXP2101Full) OnInterrupt(callback func(status uint32)) error {
	if err := c.OffInterrupt(); err != nil {
		return err
	}
	c.mu.Lock()
	c.callback = callback
	c.mu.Unlock()

	pin := c.conn.IntPin()
	var poll *connection.PollingInputPin
	if pin == nil {
		poll = connection.NewDefaultPollingInputPin()
		pin = poll
	}
	unsub := pin.OnEdge(connection.Falling, c.handleEdge)
	c.mu.Lock()
	c.unsubscribe = unsub
	c.pollPin = poll
	c.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes and stops delivery.
func (c *AXP2101Full) OffInterrupt() error {
	c.mu.Lock()
	unsub := c.unsubscribe
	poll := c.pollPin
	c.unsubscribe = nil
	c.pollPin = nil
	c.callback = nil
	c.mu.Unlock()
	if unsub != nil {
		unsub()
	}
	if poll != nil {
		return poll.Close()
	}
	return nil
}

func (c *AXP2101Full) handleEdge() {
	status, err := c.PollInterrupt()
	if err != nil || status == 0 {
		return
	}
	c.mu.Lock()
	cb := c.callback
	c.mu.Unlock()
	if cb != nil {
		cb(status)
	}
}
