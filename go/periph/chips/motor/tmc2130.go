package motor

import (
	"errors"
	"fmt"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// TMC2130 register addresses.
const (
	tmc2130RegGConf      uint8 = 0x00
	tmc2130RegGStat      uint8 = 0x01
	tmc2130RegIoin       uint8 = 0x04
	tmc2130RegIholdIrun  uint8 = 0x10
	tmc2130RegTPowerDown uint8 = 0x11
	tmc2130RegTStep      uint8 = 0x12
	tmc2130RegTPwmThrs   uint8 = 0x13
	tmc2130RegTCoolThrs  uint8 = 0x14
	tmc2130RegTHigh      uint8 = 0x15
	tmc2130RegXDirect    uint8 = 0x2D
	tmc2130RegVDcMin     uint8 = 0x33
	tmc2130RegMslut0     uint8 = 0x60
	tmc2130RegMslutSel   uint8 = 0x68
	tmc2130RegMslutStart uint8 = 0x69
	tmc2130RegMsCnt      uint8 = 0x6A
	tmc2130RegMsCurAct   uint8 = 0x6B
	tmc2130RegChopConf   uint8 = 0x6C
	tmc2130RegCoolConf   uint8 = 0x6D
	tmc2130RegDcCtrl     uint8 = 0x6E
	tmc2130RegDrvStatus  uint8 = 0x6F
	tmc2130RegPwmConf    uint8 = 0x70
	tmc2130RegPwmScale   uint8 = 0x71
	tmc2130RegLostSteps  uint8 = 0x73
)

const (
	tmc2130GConfIScaleAnalog  uint32 = 1 << 0
	tmc2130GConfInternalRsens uint32 = 1 << 1
	tmc2130GConfEnPwmMode     uint32 = 1 << 2
	tmc2130GConfShaft         uint32 = 1 << 4
	tmc2130GConfSmallHyst     uint32 = 1 << 14
	tmc2130GConfStopEnable    uint32 = 1 << 15
	tmc2130GConfDirectMode    uint32 = 1 << 16

	tmc2130Version        uint8  = 0x11
	tmc2130ChopConfDflt   uint32 = 0x140100C3
	tmc2130PwmConfDefault uint32 = 0x000401C8
)

// Fault bitmask returned by ReadFault.
const (
	TMC2130FaultOvertempWarning uint8 = 0x01 // DRV_STATUS.otpw
	TMC2130FaultOvertemp        uint8 = 0x02 // DRV_STATUS.ot
	TMC2130FaultShortGND        uint8 = 0x04 // s2ga / s2gb
	TMC2130FaultOpenLoad        uint8 = 0x10 // ola / olb (informative only)
)

// Errors returned by the TMC2130 drivers.
var (
	ErrTMC2130NotFound         = errors.New("TMC2130: device not found")
	ErrTMC2130MirrorMismatch   = errors.New("TMC2130: write mirror mismatch")
	ErrTMC2130InvalidArgument  = errors.New("TMC2130: invalid argument")
	ErrTMC2130Unsupported      = errors.New("TMC2130: unsupported with internal sense resistors")
	ErrTMC2130DirectModeNeeded = errors.New("TMC2130: direct mode required")
	ErrTMC2130NoPin            = errors.New("TMC2130: required pin not configured")
)

var tmc2130Readable = map[uint8]bool{0x00: true, 0x01: true, 0x04: true, 0x12: true, 0x2D: true,
	0x6A: true, 0x6B: true, 0x6C: true, 0x6F: true, 0x71: true, 0x73: true}

func tmc2130Writable(r uint8) bool {
	switch r {
	case 0x00, 0x10, 0x11, 0x13, 0x14, 0x15, 0x2D, 0x33, 0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67,
		0x68, 0x69, 0x6C, 0x6D, 0x6E, 0x70:
		return true
	}
	return false
}

func tmc2130SignExtend(v uint32, bits uint) int16 {
	v &= (1 << bits) - 1
	if v&(1<<(bits-1)) != 0 {
		return int16(int32(v) - int32(1<<bits))
	}
	return int16(v)
}

// TMC2130Status is the decoded DRV_STATUS register.
type TMC2130Status struct {
	Standstill bool   // stst
	FsActive   bool   // full-step operation active
	CSActual   uint8  // actual current scale 0-31
	SGResult   uint16 // StallGuard2 result 0-1023
	StallGuard bool   // stall detected
	OTPW       bool   // over-temperature pre-warning
	OT         bool   // over-temperature shutdown
	ShortGND   bool   // short to GND, either phase
	OpenLoad   bool   // open load, either phase
}

// TMC2130GlobalStatus is the decoded GSTAT register.
type TMC2130GlobalStatus struct {
	Reset  bool // IC was reset since the last read
	DrvErr bool // driver shut down by over-temperature or short
	UVCP   bool // charge-pump undervoltage
}

// TMC2130Ioin is the decoded IOIN register.
type TMC2130Ioin struct {
	Raw     uint32
	Step    bool
	Dir     bool
	Dcen    bool
	Dcin    bool
	Enn     bool
	Dco     bool
	Version uint8 // silicon version (0x11)
}

// TMC2130Minimal drives a Trinamic TMC2130 stepper driver over SPI (Mode 3).
// It brings the driver up in SPI mode (internal reference, StealthChop below
// TPWMTHRS = 500, 16 microsteps with 256-step interpolation, 0.5 A RMS run
// current) and moves the motor with STEP/DIR pulses -- the chip has no
// internal step generator. The 40-bit datagram framing and the pipelined
// two-datagram read are done here; the connection only needs plain Write and
// WriteRead.
type TMC2130Minimal struct {
	conn           connection.Connection
	stepPin        connection.OutputPin
	dirPin         connection.OutputPin
	enPin          connection.OutputPin
	rSense         float64
	gconf          uint32
	chopconf       uint32
	pwmconf        uint32
	coolconf       uint32
	microsteps     uint16
	vsense         bool
	autoVsense     bool
	internalRsense bool
	toff           uint8
	runCS, holdCS  uint8
	iholdDelay     uint8
}

// NewTMC2130Minimal creates the driver, probes the chip (IOIN identity) and
// applies the default configuration with mirror-verified writes. conn must be
// an SPI connection configured for Mode 3. rSenseOhm is the external sense
// resistor (0.11 ohm on common boards); enPin is the optional ENN output
// (active low, held high -- driver off -- until Enable).
func NewTMC2130Minimal(conn connection.Connection, stepPin, dirPin connection.OutputPin, rSenseOhm float64, enPin connection.OutputPin) (*TMC2130Minimal, error) {
	d := &TMC2130Minimal{
		conn: conn, stepPin: stepPin, dirPin: dirPin, enPin: enPin, rSense: rSenseOhm,
		microsteps: 16, autoVsense: true, toff: 3, iholdDelay: 6,
	}
	if err := d.initialise(); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *TMC2130Minimal) writeReg(reg uint8, value uint32, verify bool) error {
	frame := []byte{reg | 0x80, byte(value >> 24), byte(value >> 16), byte(value >> 8), byte(value)}
	if err := d.conn.Write(frame); err != nil {
		return err
	}
	switch reg {
	case tmc2130RegGConf:
		d.gconf = value
	case tmc2130RegChopConf:
		d.chopconf = value
	case tmc2130RegPwmConf:
		d.pwmconf = value
	case tmc2130RegCoolConf:
		d.coolconf = value
	}
	if verify {
		mirror, err := d.secondDatagram()
		if err != nil {
			return err
		}
		if mirror != value {
			return ErrTMC2130MirrorMismatch
		}
	}
	return nil
}

// secondDatagram sends a harmless GCONF read request whose reply is the
// previous datagram's answer (pipelined protocol). Never GSTAT, which clears
// on read.
func (d *TMC2130Minimal) secondDatagram() (uint32, error) {
	reply, err := d.conn.WriteRead([]byte{tmc2130RegGConf}, 4)
	if err != nil {
		return 0, err
	}
	if len(reply) != 4 {
		return 0, fmt.Errorf("TMC2130: short read (%d bytes)", len(reply))
	}
	return uint32(reply[0])<<24 | uint32(reply[1])<<16 | uint32(reply[2])<<8 | uint32(reply[3]), nil
}

func (d *TMC2130Minimal) readReg(reg uint8) (uint32, error) {
	if _, err := d.conn.WriteRead([]byte{reg & 0x7F}, 4); err != nil {
		return 0, err
	}
	return d.secondDatagram()
}

func (d *TMC2130Minimal) modify(reg uint8, mask, value uint32) error {
	var cur uint32
	switch reg {
	case tmc2130RegGConf:
		cur = d.gconf
	case tmc2130RegChopConf:
		cur = d.chopconf
	case tmc2130RegCoolConf:
		cur = d.coolconf
	default:
		cur = d.pwmconf
	}
	return d.writeReg(reg, (cur&^mask)|(value&mask), false)
}

func (d *TMC2130Minimal) csFor(rmsA float32, vsense bool) uint8 {
	vfs := 0.325
	if vsense {
		vfs = 0.180
	}
	cs := int(math.Floor(float64(rmsA)*math.Sqrt2*(d.rSense+0.020)*32/vfs+0.5)) - 1
	if cs < 0 {
		cs = 0
	}
	if cs > 31 {
		cs = 31
	}
	return uint8(cs)
}

// currentToCS returns the current scale and the vsense setting; vsense is
// selected automatically unless forced (non-nil) or disabled via SetVsense.
func (d *TMC2130Minimal) currentToCS(rmsA float32, forced *bool) (uint8, bool, error) {
	if d.internalRsense {
		return 0, false, ErrTMC2130Unsupported
	}
	if forced != nil {
		return d.csFor(rmsA, *forced), *forced, nil
	}
	if !d.autoVsense {
		return d.csFor(rmsA, d.vsense), d.vsense, nil
	}
	cs := d.csFor(rmsA, false)
	if cs < 16 {
		if high := d.csFor(rmsA, true); high <= 31 {
			return high, true, nil
		}
	}
	return cs, false, nil
}

func (d *TMC2130Minimal) writeIholdIrun(verify bool) error {
	return d.writeReg(tmc2130RegIholdIrun,
		uint32(d.holdCS&0x1F)|uint32(d.runCS&0x1F)<<8|uint32(d.iholdDelay&0xF)<<16, verify)
}

func (d *TMC2130Minimal) initialise() error {
	if d.enPin != nil {
		if err := d.enPin.Set(true); err != nil {
			return err
		}
	}
	time.Sleep(10 * time.Millisecond)
	ioin, err := d.readReg(tmc2130RegIoin)
	if err != nil {
		return fmt.Errorf("TMC2130: device not responding: %w", err)
	}
	if uint8(ioin>>24) != tmc2130Version || ioin&(1<<6) == 0 {
		return ErrTMC2130NotFound
	}
	if _, err := d.readReg(tmc2130RegGStat); err != nil {
		return err
	}
	cs, vsense, _ := d.currentToCS(0.5, nil)
	d.runCS, d.holdCS, d.vsense = cs, cs/3, vsense
	chop := tmc2130ChopConfDflt
	if vsense {
		chop |= 1 << 17
	}
	steps := []func() error{
		func() error { return d.writeReg(tmc2130RegChopConf, chop, true) },
		func() error { return d.writeIholdIrun(true) },
		func() error { return d.writeReg(tmc2130RegTPowerDown, 10, true) },
		func() error { return d.writeReg(tmc2130RegGConf, tmc2130GConfEnPwmMode, true) },
		func() error { return d.writeReg(tmc2130RegTPwmThrs, 500, true) },
		func() error { return d.writeReg(tmc2130RegPwmConf, tmc2130PwmConfDefault, true) },
	}
	for _, s := range steps {
		if err := s(); err != nil {
			return err
		}
	}
	if g, err := d.readReg(tmc2130RegGConf); err != nil || g != d.gconf {
		if err != nil {
			return err
		}
		return ErrTMC2130MirrorMismatch
	}
	if c, err := d.readReg(tmc2130RegChopConf); err != nil || c != d.chopconf {
		if err != nil {
			return err
		}
		return ErrTMC2130MirrorMismatch
	}
	return nil
}

// Enable switches the power stage on (ENN low, or TOFF restored when no ENN
// pin is wired) and waits 100 ms for StealthChop to settle.
func (d *TMC2130Minimal) Enable() error {
	var err error
	if d.enPin != nil {
		err = d.enPin.Set(false)
	} else {
		err = d.writeReg(tmc2130RegChopConf, (d.chopconf&^0xF)|uint32(d.toff), false)
	}
	time.Sleep(100 * time.Millisecond)
	return err
}

// Disable switches the power stage off; the motor freewheels.
func (d *TMC2130Minimal) Disable() error {
	if d.enPin != nil {
		return d.enPin.Set(true)
	}
	if d.chopconf&0xF != 0 {
		d.toff = uint8(d.chopconf & 0xF)
	}
	return d.writeReg(tmc2130RegChopConf, d.chopconf&^0xF, false)
}

// SetCurrent sets the run current in A RMS (at most 1.4); the hold current
// becomes one third of it. It selects CHOPCONF.vsense automatically unless
// SetVsense was called.
func (d *TMC2130Minimal) SetCurrent(rmsA float32) error {
	if rmsA > 1.4 {
		return ErrTMC2130InvalidArgument
	}
	cs, vsense, err := d.currentToCS(rmsA, nil)
	if err != nil {
		return err
	}
	d.runCS, d.holdCS = cs, cs/3
	if vsense != d.vsense {
		d.vsense = vsense
		bit := uint32(0)
		if vsense {
			bit = 1 << 17
		}
		if err := d.modify(tmc2130RegChopConf, 1<<17, bit); err != nil {
			return err
		}
	}
	return d.writeIholdIrun(false)
}

// Move moves fullSteps full steps (sign = direction) at fullStepsPerS
// (blocking, software-timed STEP pulses).
func (d *TMC2130Minimal) Move(fullSteps int, fullStepsPerS float32) error {
	if fullStepsPerS <= 0 {
		return ErrTMC2130InvalidArgument
	}
	n := fullSteps
	if n < 0 {
		n = -n
	}
	pulses := n * int(d.microsteps)
	half := time.Duration(float64(time.Second) / (2 * float64(fullStepsPerS) * float64(d.microsteps)))
	if err := d.dirPin.Set(fullSteps >= 0); err != nil {
		return err
	}
	for i := 0; i < pulses; i++ {
		if err := d.stepPin.Set(true); err != nil {
			return err
		}
		time.Sleep(half)
		if err := d.stepPin.Set(false); err != nil {
			return err
		}
		time.Sleep(half)
	}
	return nil
}

// IsStandstill returns DRV_STATUS.stst.
func (d *TMC2130Minimal) IsStandstill() (bool, error) {
	v, err := d.readReg(tmc2130RegDrvStatus)
	return v>>31 == 1, err
}

// ReadFault returns the TMC2130Fault* bitmask, 0 when none.
func (d *TMC2130Minimal) ReadFault() (uint8, error) {
	s, err := d.readReg(tmc2130RegDrvStatus)
	if err != nil {
		return 0, err
	}
	var f uint8
	if s&(1<<26) != 0 {
		f |= TMC2130FaultOvertempWarning
	}
	if s&(1<<25) != 0 {
		f |= TMC2130FaultOvertemp
	}
	if s&(3<<27) != 0 {
		f |= TMC2130FaultShortGND
	}
	if s&(3<<29) != 0 {
		f |= TMC2130FaultOpenLoad
	}
	return f, nil
}

// TMC2130Full exposes the complete TMC2130 feature set; it embeds
// TMC2130Minimal.
type TMC2130Full struct {
	*TMC2130Minimal
	diag0 connection.InputPin
	diag1 connection.InputPin
}

// NewTMC2130Full creates the Full driver. diag0 and diag1 are optional (nil).
func NewTMC2130Full(conn connection.Connection, stepPin, dirPin connection.OutputPin, rSenseOhm float64,
	enPin connection.OutputPin, diag0, diag1 connection.InputPin) (*TMC2130Full, error) {
	m, err := NewTMC2130Minimal(conn, stepPin, dirPin, rSenseOhm, enPin)
	if err != nil {
		return nil, err
	}
	return &TMC2130Full{TMC2130Minimal: m, diag0: diag0, diag1: diag1}, nil
}

func tmc2130Bit(b bool, bits uint32) uint32 {
	if b {
		return bits
	}
	return 0
}

// ReadRegister reads a raw 32-bit register (pipelined two-datagram read);
// write-only and reserved registers return ErrTMC2130InvalidArgument.
func (d *TMC2130Full) ReadRegister(reg uint8) (uint32, error) {
	if !tmc2130Readable[reg] {
		return 0, ErrTMC2130InvalidArgument
	}
	return d.readReg(reg)
}

// WriteRegister writes a raw 32-bit register; verify adds the mirror check.
func (d *TMC2130Full) WriteRegister(reg uint8, value uint32, verify bool) error {
	if !tmc2130Writable(reg) {
		return ErrTMC2130InvalidArgument
	}
	return d.writeReg(reg, value, verify)
}

// SetHoldCurrent sets the standstill hold current in A RMS.
func (d *TMC2130Full) SetHoldCurrent(rmsA float32) error {
	vs := d.vsense
	cs, _, err := d.currentToCS(rmsA, &vs)
	if err != nil {
		return err
	}
	d.holdCS = cs
	return d.writeIholdIrun(false)
}

// SetCurrentDelay sets IHOLDDELAY (0-15) and TPOWERDOWN (0-255).
func (d *TMC2130Full) SetCurrentDelay(iholdDelay, tpowerdown uint8) error {
	if iholdDelay > 15 {
		return ErrTMC2130InvalidArgument
	}
	d.iholdDelay = iholdDelay
	if err := d.writeIholdIrun(false); err != nil {
		return err
	}
	return d.writeReg(tmc2130RegTPowerDown, uint32(tpowerdown), false)
}

// SetMicrosteps selects 1, 2, 4, ..., 256 microsteps.
func (d *TMC2130Full) SetMicrosteps(n uint16) error {
	if n == 0 || n > 256 || n&(n-1) != 0 {
		return ErrTMC2130InvalidArgument
	}
	mres := uint32(8)
	for v := n; v > 1; v >>= 1 {
		mres--
	}
	if err := d.modify(tmc2130RegChopConf, 0xF<<24, mres<<24); err != nil {
		return err
	}
	d.microsteps = n
	return nil
}

// SetInterpolation enables 256-microstep interpolation (CHOPCONF.intpol).
func (d *TMC2130Full) SetInterpolation(enabled bool) error {
	return d.modify(tmc2130RegChopConf, 1<<28, tmc2130Bit(enabled, 1<<28))
}

// SetChopperMode selects SpreadCycle only (true) or StealthChop (false);
// switch at standstill.
func (d *TMC2130Full) SetChopperMode(spreadcycle bool) error {
	return d.modify(tmc2130RegGConf, tmc2130GConfEnPwmMode, tmc2130Bit(!spreadcycle, tmc2130GConfEnPwmMode))
}

// SetStealthchopThreshold sets TPWMTHRS (20 bit); 0 disables.
func (d *TMC2130Full) SetStealthchopThreshold(tpwmthrs uint32) error {
	if tpwmthrs > 0xFFFFF {
		return ErrTMC2130InvalidArgument
	}
	return d.writeReg(tmc2130RegTPwmThrs, tpwmthrs, false)
}

// SetHighVelocityThreshold sets THIGH (20 bit).
func (d *TMC2130Full) SetHighVelocityThreshold(thigh uint32) error {
	if thigh > 0xFFFFF {
		return ErrTMC2130InvalidArgument
	}
	return d.writeReg(tmc2130RegTHigh, thigh, false)
}

// ConfigureChopper writes the SpreadCycle (chm = 0) fields: toff 0-15, tbl
// 0-3, hstrt 0-7 and the raw hend 0-15.
func (d *TMC2130Full) ConfigureChopper(toff, tbl, hstrt, hend uint8) error {
	if toff > 15 || tbl > 3 || hstrt > 7 || hend > 15 {
		return ErrTMC2130InvalidArgument
	}
	if toff != 0 {
		d.toff = toff
	}
	mask := uint32(0xF | 3<<15 | 7<<4 | 0xF<<7 | 1<<14 | 1<<11)
	return d.modify(tmc2130RegChopConf, mask, uint32(toff)|uint32(tbl)<<15|uint32(hstrt)<<4|uint32(hend)<<7)
}

// ConfigureConstantOffTime writes the constant-off-time (chm = 1) fields:
// tfd 0-15, offset -3..12.
func (d *TMC2130Full) ConfigureConstantOffTime(toff, tbl, tfd uint8, offset int8, disfdcc, rndtf bool) error {
	if toff > 15 || tbl > 3 || tfd > 15 || offset < -3 || offset > 12 {
		return ErrTMC2130InvalidArgument
	}
	if toff != 0 {
		d.toff = toff
	}
	mask := uint32(0xF | 3<<15 | 7<<4 | 0xF<<7 | 1<<14 | 1<<13 | 1<<12 | 1<<11)
	v := uint32(toff) | uint32(tbl)<<15 | uint32(tfd&7)<<4 | uint32(offset+3)<<7 | 1<<14 |
		tmc2130Bit(rndtf, 1<<13) | tmc2130Bit(disfdcc, 1<<12) | uint32(tfd>>3&1)<<11
	return d.modify(tmc2130RegChopConf, mask, v)
}

// ConfigureHighVelocity sets CHOPCONF.vhighchm and vhighfs.
func (d *TMC2130Full) ConfigureHighVelocity(vhighchm, vhighfs bool) error {
	return d.modify(tmc2130RegChopConf, 1<<19|1<<18, tmc2130Bit(vhighchm, 1<<19)|tmc2130Bit(vhighfs, 1<<18))
}

// SetChopperSync sets CHOPCONF.SYNC (0-15).
func (d *TMC2130Full) SetChopperSync(sync uint8) error {
	if sync > 15 {
		return ErrTMC2130InvalidArgument
	}
	return d.modify(tmc2130RegChopConf, 0xF<<20, uint32(sync)<<20)
}

// ConfigureStealthchop writes the StealthChop fields of PWMCONF (freewheel
// is kept): pwmFreq 0-3.
func (d *TMC2130Full) ConfigureStealthchop(autoscale, symmetric bool, pwmAmpl, pwmGrad, pwmFreq uint8) error {
	if pwmFreq > 3 {
		return ErrTMC2130InvalidArgument
	}
	mask := uint32(1<<19 | 1<<18 | 3<<16 | 0xFF<<8 | 0xFF)
	v := tmc2130Bit(symmetric, 1<<19) | tmc2130Bit(autoscale, 1<<18) | uint32(pwmFreq)<<16 | uint32(pwmGrad)<<8 | uint32(pwmAmpl)
	return d.modify(tmc2130RegPwmConf, mask, v)
}

// SetFreewheel selects PWMCONF.freewheel (0-3).
func (d *TMC2130Full) SetFreewheel(mode uint8) error {
	if mode > 3 {
		return ErrTMC2130InvalidArgument
	}
	return d.modify(tmc2130RegPwmConf, 3<<20, uint32(mode)<<20)
}

// SetVsense sets CHOPCONF.vsense and disables the automatic selection in
// SetCurrent.
func (d *TMC2130Full) SetVsense(highSensitivity bool) error {
	if err := d.modify(tmc2130RegChopConf, 1<<17, tmc2130Bit(highSensitivity, 1<<17)); err != nil {
		return err
	}
	d.vsense = highSensitivity
	d.autoVsense = false
	return nil
}

// SetInternalRsense sets GCONF.internal_Rsense (SetCurrent then fails).
func (d *TMC2130Full) SetInternalRsense(enabled bool) error {
	if err := d.modify(tmc2130RegGConf, tmc2130GConfInternalRsens, tmc2130Bit(enabled, tmc2130GConfInternalRsens)); err != nil {
		return err
	}
	d.internalRsense = enabled
	return nil
}

// SetAnalogScaling sets GCONF.I_scale_analog.
func (d *TMC2130Full) SetAnalogScaling(enabled bool) error {
	return d.modify(tmc2130RegGConf, tmc2130GConfIScaleAnalog, tmc2130Bit(enabled, tmc2130GConfIScaleAnalog))
}

// SetDirectionInverted sets GCONF.shaft.
func (d *TMC2130Full) SetDirectionInverted(inverted bool) error {
	return d.modify(tmc2130RegGConf, tmc2130GConfShaft, tmc2130Bit(inverted, tmc2130GConfShaft))
}

// TMC2130DiagConfig selects the events routed to the DIAG pins (GCONF bits 5-13).
type TMC2130DiagConfig struct {
	Diag0Error, Diag0Otpw, Diag0Stall    bool
	Diag1Stall, Diag1Index, Diag1Onstate bool
	Diag0PushPull, Diag1PushPull         bool
}

// ConfigureDiag routes events to DIAG0 / DIAG1.
func (d *TMC2130Full) ConfigureDiag(c TMC2130DiagConfig) error {
	v := tmc2130Bit(c.Diag0Error, 1<<5) | tmc2130Bit(c.Diag0Otpw, 1<<6) | tmc2130Bit(c.Diag0Stall, 1<<7) |
		tmc2130Bit(c.Diag1Stall, 1<<8) | tmc2130Bit(c.Diag1Index, 1<<9) | tmc2130Bit(c.Diag1Onstate, 1<<10) |
		tmc2130Bit(c.Diag0PushPull, 1<<12) | tmc2130Bit(c.Diag1PushPull, 1<<13)
	return d.modify(tmc2130RegGConf, 0x3FE0&^(1<<11), v)
}

// SetDoubleEdge sets CHOPCONF.dedge.
func (d *TMC2130Full) SetDoubleEdge(enabled bool) error {
	return d.modify(tmc2130RegChopConf, 1<<29, tmc2130Bit(enabled, 1<<29))
}

// SetShortProtection keeps (true) or disables (false) the short-to-GND protection.
func (d *TMC2130Full) SetShortProtection(shortGND bool) error {
	return d.modify(tmc2130RegChopConf, 1<<30, tmc2130Bit(!shortGND, 1<<30))
}

// SetSmallHysteresis sets GCONF.small_hysteresis.
func (d *TMC2130Full) SetSmallHysteresis(enabled bool) error {
	return d.modify(tmc2130RegGConf, tmc2130GConfSmallHyst, tmc2130Bit(enabled, tmc2130GConfSmallHyst))
}

// SetEmergencyStop sets GCONF.stop_enable.
func (d *TMC2130Full) SetEmergencyStop(enabled bool) error {
	return d.modify(tmc2130RegGConf, tmc2130GConfStopEnable, tmc2130Bit(enabled, tmc2130GConfStopEnable))
}

// SetStallThreshold sets COOLCONF.sgt (-64..63, lower = more sensitive),
// keeping the other COOLCONF fields.
func (d *TMC2130Full) SetStallThreshold(sgt int8) error {
	if sgt < -64 || sgt > 63 {
		return ErrTMC2130InvalidArgument
	}
	return d.modify(tmc2130RegCoolConf, 0x7F<<16, (uint32(sgt)&0x7F)<<16)
}

// SetStallguardFilter sets COOLCONF.sfilt.
func (d *TMC2130Full) SetStallguardFilter(enabled bool) error {
	return d.modify(tmc2130RegCoolConf, 1<<24, tmc2130Bit(enabled, 1<<24))
}

// SetCoolstepThreshold sets TCOOLTHRS (20 bit); 0 disables.
func (d *TMC2130Full) SetCoolstepThreshold(tcoolthrs uint32) error {
	if tcoolthrs > 0xFFFFF {
		return ErrTMC2130InvalidArgument
	}
	return d.writeReg(tmc2130RegTCoolThrs, tcoolthrs, false)
}

// ConfigureCoolstep writes the CoolStep fields of COOLCONF (sgt and sfilt are
// kept): semin 0-15 (0 disables), semax 0-15, seup 0-3, sedn 0-3, seimin 0|1.
func (d *TMC2130Full) ConfigureCoolstep(semin, semax, seup, sedn, seimin uint8) error {
	if semin > 15 || semax > 15 || seup > 3 || sedn > 3 || seimin > 1 {
		return ErrTMC2130InvalidArgument
	}
	mask := uint32(0xF | 3<<5 | 0xF<<8 | 3<<13 | 1<<15)
	return d.modify(tmc2130RegCoolConf, mask,
		uint32(semin)|uint32(seup)<<5|uint32(semax)<<8|uint32(sedn)<<13|uint32(seimin)<<15)
}

// ReadStallguard returns SG_RESULT (0-1023, 0 = highest load).
func (d *TMC2130Full) ReadStallguard() (uint16, error) {
	v, err := d.readReg(tmc2130RegDrvStatus)
	return uint16(v & 0x3FF), err
}

// IsStalled returns DRV_STATUS.StallGuard.
func (d *TMC2130Full) IsStalled() (bool, error) {
	v, err := d.readReg(tmc2130RegDrvStatus)
	return v>>24&1 == 1, err
}

// ReadDiag returns the level of DIAG0 (n = 0) or DIAG1 (n = 1); it returns
// ErrTMC2130NoPin when that pin is not wired.
func (d *TMC2130Full) ReadDiag(n int) (bool, error) {
	var pin connection.InputPin
	switch n {
	case 0:
		pin = d.diag0
	case 1:
		pin = d.diag1
	default:
		return false, ErrTMC2130InvalidArgument
	}
	if pin == nil {
		return false, ErrTMC2130NoPin
	}
	return pin.IsHigh()
}

// Step emits |count| STEP pulses (negative reverses via DIR); each edge waits
// delay. No microstep conversion.
func (d *TMC2130Full) Step(count int, delay time.Duration) error {
	if err := d.dirPin.Set(count >= 0); err != nil {
		return err
	}
	if count < 0 {
		count = -count
	}
	for i := 0; i < count; i++ {
		if err := d.stepPin.Set(true); err != nil {
			return err
		}
		time.Sleep(delay)
		if err := d.stepPin.Set(false); err != nil {
			return err
		}
		time.Sleep(delay)
	}
	return nil
}

// SetDirectMode sets GCONF.direct_mode.
func (d *TMC2130Full) SetDirectMode(enabled bool) error {
	return d.modify(tmc2130RegGConf, tmc2130GConfDirectMode, tmc2130Bit(enabled, tmc2130GConfDirectMode))
}

// SetCoilCurrents writes XDIRECT (-255..255 each); direct mode is required.
func (d *TMC2130Full) SetCoilCurrents(coilA, coilB int16) error {
	if d.gconf&tmc2130GConfDirectMode == 0 {
		return ErrTMC2130DirectModeNeeded
	}
	if coilA < -255 || coilA > 255 || coilB < -255 || coilB > 255 {
		return ErrTMC2130InvalidArgument
	}
	return d.writeReg(tmc2130RegXDirect, uint32(coilA)&0x1FF|(uint32(coilB)&0x1FF)<<16, false)
}

// SetDcstep writes VDCMIN (0..2^23-1), DCCTRL.DC_TIME (0-1023) and DC_SG (0-255).
func (d *TMC2130Full) SetDcstep(vdcmin uint32, dcTime uint16, dcSG uint8) error {
	if vdcmin > 0x7FFFFF || dcTime > 1023 {
		return ErrTMC2130InvalidArgument
	}
	if err := d.writeReg(tmc2130RegVDcMin, vdcmin, false); err != nil {
		return err
	}
	return d.writeReg(tmc2130RegDcCtrl, uint32(dcTime)|uint32(dcSG)<<16, false)
}

// WriteMicrostepTable programs MSLUT[0..7], MSLUTSEL and MSLUTSTART; call at MSCNT = 0.
func (d *TMC2130Full) WriteMicrostepTable(mslut [8]uint32, mslutsel, mslutstart uint32) error {
	for i, w := range mslut {
		if err := d.writeReg(tmc2130RegMslut0+uint8(i), w, false); err != nil {
			return err
		}
	}
	if err := d.writeReg(tmc2130RegMslutSel, mslutsel, false); err != nil {
		return err
	}
	return d.writeReg(tmc2130RegMslutStart, mslutstart, false)
}

// MicrostepPosition returns MSCNT (0-1023).
func (d *TMC2130Full) MicrostepPosition() (uint16, error) {
	v, err := d.readReg(tmc2130RegMsCnt)
	return uint16(v & 0x3FF), err
}

// ReadCurrentScale returns DRV_STATUS.CS_ACTUAL (0-31).
func (d *TMC2130Full) ReadCurrentScale() (uint8, error) {
	v, err := d.readReg(tmc2130RegDrvStatus)
	return uint8(v >> 16 & 0x1F), err
}

// ReadCoilCurrents returns the signed (CUR_A, CUR_B) values from MSCURACT.
func (d *TMC2130Full) ReadCoilCurrents() (curA, curB int16, err error) {
	v, err := d.readReg(tmc2130RegMsCurAct)
	return tmc2130SignExtend(v>>16, 9), tmc2130SignExtend(v, 9), err
}

// ReadTstep returns TSTEP (20 bit).
func (d *TMC2130Full) ReadTstep() (uint32, error) {
	v, err := d.readReg(tmc2130RegTStep)
	return v & 0xFFFFF, err
}

// ReadPwmScale returns PWM_SCALE (0-255).
func (d *TMC2130Full) ReadPwmScale() (uint8, error) {
	v, err := d.readReg(tmc2130RegPwmScale)
	return uint8(v), err
}

// ReadLostSteps returns LOST_STEPS (20 bit, wraps).
func (d *TMC2130Full) ReadLostSteps() (uint32, error) {
	v, err := d.readReg(tmc2130RegLostSteps)
	return v & 0xFFFFF, err
}

// ReadStatus returns the decoded DRV_STATUS (one read).
func (d *TMC2130Full) ReadStatus() (TMC2130Status, error) {
	s, err := d.readReg(tmc2130RegDrvStatus)
	return TMC2130Status{
		Standstill: s>>31 == 1, FsActive: s&(1<<15) != 0, CSActual: uint8(s >> 16 & 0x1F),
		SGResult: uint16(s & 0x3FF), StallGuard: s&(1<<24) != 0, OTPW: s&(1<<26) != 0,
		OT: s&(1<<25) != 0, ShortGND: s&(3<<27) != 0, OpenLoad: s&(3<<29) != 0,
	}, err
}

// ReadGlobalStatus returns the decoded GSTAT; the chip clears the flags on read.
func (d *TMC2130Full) ReadGlobalStatus() (TMC2130GlobalStatus, error) {
	g, err := d.readReg(tmc2130RegGStat)
	return TMC2130GlobalStatus{Reset: g&1 != 0, DrvErr: g&2 != 0, UVCP: g&4 != 0}, err
}

// ReadIoin returns the decoded IOIN.
func (d *TMC2130Full) ReadIoin() (TMC2130Ioin, error) {
	v, err := d.readReg(tmc2130RegIoin)
	return TMC2130Ioin{
		Raw: v, Step: v&1 != 0, Dir: v&2 != 0, Dcen: v&4 != 0, Dcin: v&8 != 0,
		Enn: v&16 != 0, Dco: v&32 != 0, Version: uint8(v >> 24),
	}, err
}
