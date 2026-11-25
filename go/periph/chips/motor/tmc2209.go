package motor

import (
	"errors"
	"fmt"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// TMC2209 register addresses.
const (
	tmc2209RegGConf      uint8 = 0x00
	tmc2209RegGStat      uint8 = 0x01
	tmc2209RegIfcnt      uint8 = 0x02
	tmc2209RegNodeConf   uint8 = 0x03
	tmc2209RegOtpRead    uint8 = 0x05
	tmc2209RegIoin       uint8 = 0x06
	tmc2209RegIholdIrun  uint8 = 0x10
	tmc2209RegTPowerDown uint8 = 0x11
	tmc2209RegTStep      uint8 = 0x12
	tmc2209RegTPwmThrs   uint8 = 0x13
	tmc2209RegTCoolThrs  uint8 = 0x14
	tmc2209RegVActual    uint8 = 0x22
	tmc2209RegSgThrs     uint8 = 0x40
	tmc2209RegSgResult   uint8 = 0x41
	tmc2209RegCoolConf   uint8 = 0x42
	tmc2209RegMsCnt      uint8 = 0x6A
	tmc2209RegMsCurAct   uint8 = 0x6B
	tmc2209RegChopConf   uint8 = 0x6C
	tmc2209RegDrvStatus  uint8 = 0x6F
	tmc2209RegPwmConf    uint8 = 0x70
	tmc2209RegPwmScale   uint8 = 0x71
	tmc2209RegPwmAuto    uint8 = 0x72
)

// GCONF bits.
const (
	tmc2209GConfIScaleAnalog  uint32 = 1 << 0
	tmc2209GConfInternalRsens uint32 = 1 << 1
	tmc2209GConfSpreadCycle   uint32 = 1 << 2
	tmc2209GConfShaft         uint32 = 1 << 3
	tmc2209GConfIndexOtpw     uint32 = 1 << 4
	tmc2209GConfIndexStep     uint32 = 1 << 5
	tmc2209GConfPdnDisable    uint32 = 1 << 6
	tmc2209GConfMstepRegSel   uint32 = 1 << 7
	tmc2209GConfMultistepFilt uint32 = 1 << 8
)

const tmc2209Version uint8 = 0x21

const tmc2209PwmConfDefault uint32 = 0xC10D0024

// Fault bitmask returned by ReadFault.
const (
	TMC2209FaultOvertempWarning uint8 = 0x01 // DRV_STATUS.otpw
	TMC2209FaultOvertemp        uint8 = 0x02 // DRV_STATUS.ot
	TMC2209FaultShortGND        uint8 = 0x04 // s2ga / s2gb
	TMC2209FaultShortSupply     uint8 = 0x08 // s2vsa / s2vsb
	TMC2209FaultOpenLoad        uint8 = 0x10 // ola / olb (informative only)
)

// Errors returned by the TMC2209 drivers.
var (
	ErrTMC2209NotFound        = errors.New("TMC2209: device not found")
	ErrTMC2209IfcntMismatch   = errors.New("TMC2209: IFCNT mismatch (writes not acknowledged)")
	ErrTMC2209EchoMismatch    = errors.New("TMC2209: echo mismatch")
	ErrTMC2209CRC             = errors.New("TMC2209: reply CRC error")
	ErrTMC2209Malformed       = errors.New("TMC2209: malformed reply")
	ErrTMC2209InvalidArgument = errors.New("TMC2209: invalid argument")
	ErrTMC2209Unsupported     = errors.New("TMC2209: unsupported with internal sense resistors")
	ErrTMC2209NoPin           = errors.New("TMC2209: required STEP/DIR/DIAG pin not configured")
)

// TMC2209CRC8 computes the datagram CRC-8 (polynomial 0x07, init 0, LSB
// first) over data.
func TMC2209CRC8(data []byte) uint8 {
	var crc uint8
	for _, b := range data {
		for i := 0; i < 8; i++ {
			if (crc>>7)^(b&1) != 0 {
				crc = (crc << 1) ^ 0x07
			} else {
				crc <<= 1
			}
			b >>= 1
		}
	}
	return crc
}

func tmc2209SignExtend(v uint32, bits uint) int16 {
	v &= (1 << bits) - 1
	if v&(1<<(bits-1)) != 0 {
		return int16(int32(v) - int32(1<<bits))
	}
	return int16(v)
}

// TMC2209Status is the decoded DRV_STATUS register.
type TMC2209Status struct {
	Standstill  bool  // stst
	Stealth     bool  // StealthChop active (false = SpreadCycle)
	CSActual    uint8 // actual current scale 0-31
	OTPW        bool  // over-temperature pre-warning
	OT          bool  // over-temperature shutdown
	ShortGND    bool  // short to GND, either phase
	ShortSupply bool  // low-side short, either phase
	OpenLoad    bool  // open load, either phase
	T120        bool
	T143        bool
	T150        bool
	T157        bool
}

// TMC2209GlobalStatus is the decoded GSTAT register.
type TMC2209GlobalStatus struct {
	Reset  bool // IC was reset since the last clear
	DrvErr bool // driver shut down by over-temperature or short
	UVCP   bool // charge-pump undervoltage
}

// TMC2209Ioin is the decoded identity part of IOIN.
type TMC2209Ioin struct {
	Raw     uint32
	ENN     bool  // state of ENN
	MS1     bool  // state of MS1 (address bit 0)
	MS2     bool  // state of MS2 (address bit 1)
	Diag    bool  // state of DIAG
	PdnUart bool  // state of PDN_UART
	Step    bool  // state of STEP
	Spread  bool  // state of the SPREAD pin
	Dir     bool  // state of DIR
	Version uint8 // silicon version (0x21)
}

// TMC2209PwmAuto holds the StealthChop auto-tuning results.
type TMC2209PwmAuto struct {
	PwmScaleSum  uint8
	PwmScaleAuto int16
	PwmOfsAuto   uint8
	PwmGradAuto  uint8
}

// TMC2209Minimal drives a Trinamic TMC2209 stepper driver over
// single-wire UART. It brings the driver up in UART mode with
// StealthChop, 16 microsteps with 256-step interpolation and 0.5 A RMS run
// current, and moves the motor with the internal step generator (VACTUAL) --
// no STEP pulses required. Datagram framing (sync byte, CRC8, host echo) is
// done here; the connection only needs plain Write / Read / WriteRead.
type TMC2209Minimal struct {
	conn           connection.Connection
	rSense         float64
	address        uint8
	enPin          connection.OutputPin
	echo           bool
	fclk           uint32
	multiNode      bool
	gconf          uint32
	chopconf       uint32
	pwmconf        uint32
	microsteps     uint16
	vsense         bool
	internalRsense bool
	toff           uint8
	runCS, holdCS  uint8
	iholdDelay     uint8
}

// NewTMC2209Minimal creates the driver, probes the chip (IOIN identity) and
// applies the default configuration, verifying the write counter IFCNT.
// rSenseOhm is the external sense resistor (0.11 ohm on common boards);
// address is the UART node address 0-3 set by the MS1_AD0 / MS2_AD1 pins
// (ErrTMC2209InvalidArgument otherwise); enPin is the optional ENN output (active low, held high -- driver off --
// until Enable); echo is true when TX and RX share PDN_UART.
func NewTMC2209Minimal(conn connection.Connection, rSenseOhm float64, address uint8, enPin connection.OutputPin, echo bool) (*TMC2209Minimal, error) {
	return newTMC2209Minimal(conn, rSenseOhm, address, enPin, echo, 12000000, false)
}

func newTMC2209Minimal(conn connection.Connection, rSenseOhm float64, address uint8, enPin connection.OutputPin, echo bool, fclk uint32, multiNode bool) (*TMC2209Minimal, error) {
	if address > 3 {
		return nil, ErrTMC2209InvalidArgument
	}
	d := &TMC2209Minimal{
		conn: conn, rSense: rSenseOhm, address: address, enPin: enPin, echo: echo, fclk: fclk,
		multiNode: multiNode, microsteps: 16, toff: 3, iholdDelay: 6,
	}
	if err := d.initialise(); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *TMC2209Minimal) writeDatagram(reg uint8, value uint32) error {
	dg := []byte{0x05, d.address, reg | 0x80, byte(value >> 24), byte(value >> 16), byte(value >> 8), byte(value), 0}
	dg[7] = TMC2209CRC8(dg[:7])
	if err := d.conn.Write(dg); err != nil {
		return err
	}
	if d.echo {
		echoed, err := d.conn.Read(8)
		if err != nil {
			return err
		}
		if len(echoed) != 8 || string(echoed) != string(dg) {
			return ErrTMC2209EchoMismatch
		}
	}
	return nil
}

func (d *TMC2209Minimal) readDatagram(reg uint8) (uint32, error) {
	req := []byte{0x05, d.address, reg & 0x7F, 0}
	req[3] = TMC2209CRC8(req[:3])
	var reply []byte
	if d.echo {
		raw, err := d.conn.WriteRead(req, 12)
		if err != nil {
			return 0, err
		}
		if len(raw) != 12 {
			return 0, ErrTMC2209Malformed
		}
		if string(raw[:4]) != string(req) {
			return 0, ErrTMC2209EchoMismatch
		}
		reply = raw[4:]
	} else {
		raw, err := d.conn.WriteRead(req, 8)
		if err != nil {
			return 0, err
		}
		if len(raw) != 8 {
			return 0, ErrTMC2209Malformed
		}
		reply = raw
	}
	if reply[0] != 0x05 || reply[1] != 0xFF || reply[2] != reg&0x7F {
		return 0, ErrTMC2209Malformed
	}
	if TMC2209CRC8(reply[:7]) != reply[7] {
		return 0, ErrTMC2209CRC
	}
	return uint32(reply[3])<<24 | uint32(reply[4])<<16 | uint32(reply[5])<<8 | uint32(reply[6]), nil
}

// writeReg sends a write datagram, retrying once after the >= 12 bit-time
// idle the chip needs following an error.
func (d *TMC2209Minimal) writeReg(reg uint8, value uint32) error {
	if err := d.writeDatagram(reg, value); err != nil {
		time.Sleep(2 * time.Millisecond)
		if err = d.writeDatagram(reg, value); err != nil {
			return err
		}
	}
	switch reg {
	case tmc2209RegGConf:
		d.gconf = value
	case tmc2209RegChopConf:
		d.chopconf = value
	case tmc2209RegPwmConf:
		d.pwmconf = value
	}
	return nil
}

func (d *TMC2209Minimal) readReg(reg uint8) (uint32, error) {
	v, err := d.readDatagram(reg)
	if err != nil {
		time.Sleep(2 * time.Millisecond)
		v, err = d.readDatagram(reg)
	}
	return v, err
}

func (d *TMC2209Minimal) modify(reg uint8, mask, value uint32) error {
	var cur uint32
	switch reg {
	case tmc2209RegGConf:
		cur = d.gconf
	case tmc2209RegChopConf:
		cur = d.chopconf
	default:
		cur = d.pwmconf
	}
	return d.writeReg(reg, (cur&^mask)|(value&mask))
}

func (d *TMC2209Minimal) currentToCS(rmsA float32) (uint8, error) {
	if d.internalRsense {
		return 0, ErrTMC2209Unsupported
	}
	vfs := 0.325
	if d.vsense {
		vfs = 0.180
	}
	cs := int(math.Floor(float64(rmsA)*math.Sqrt2*(d.rSense+0.020)*32/vfs+0.5)) - 1
	if cs < 0 {
		cs = 0
	}
	if cs > 31 {
		cs = 31
	}
	return uint8(cs), nil
}

func (d *TMC2209Minimal) writeIholdIrun() error {
	return d.writeReg(tmc2209RegIholdIrun,
		uint32(d.holdCS&0x1F)|uint32(d.runCS&0x1F)<<8|uint32(d.iholdDelay&0xF)<<16)
}

func (d *TMC2209Minimal) initialise() error {
	if d.enPin != nil {
		if err := d.enPin.Set(true); err != nil {
			return err
		}
	}
	time.Sleep(10 * time.Millisecond)
	baseline, err := d.readReg(tmc2209RegIfcnt)
	if err != nil {
		return fmt.Errorf("TMC2209: device not responding: %w", err)
	}
	ioin, err := d.readReg(tmc2209RegIoin)
	if err != nil {
		return fmt.Errorf("TMC2209: device not responding: %w", err)
	}
	version := uint8(ioin >> 24)
	if version != tmc2209Version {
		return ErrTMC2209NotFound
	}
	writes := uint8(6)
	if d.multiNode {
		if err := d.writeReg(tmc2209RegNodeConf, 2<<8); err != nil {
			return err
		}
		writes = 7
	}
	if err := d.writeReg(tmc2209RegGConf, tmc2209GConfIScaleAnalog|tmc2209GConfPdnDisable|tmc2209GConfMstepRegSel|tmc2209GConfMultistepFilt); err != nil {
		return err
	}
	if err := d.writeReg(tmc2209RegGStat, 0x07); err != nil {
		return err
	}
	d.runCS, _ = d.currentToCS(0.5)
	d.holdCS, _ = d.currentToCS(0.5 / 3)
	if err := d.writeIholdIrun(); err != nil {
		return err
	}
	if err := d.writeReg(tmc2209RegTPowerDown, 20); err != nil {
		return err
	}
	chop, err := d.readReg(tmc2209RegChopConf)
	if err != nil {
		return err
	}
	chop &^= 0xF | 3<<15 | 7<<4 | 0xF<<7 | 0xF<<24 | 1<<17
	chop |= 3 | 2<<15 | 5<<4 | 4<<24 | 1<<28
	if err := d.writeReg(tmc2209RegChopConf, chop); err != nil {
		return err
	}
	if err := d.writeReg(tmc2209RegPwmConf, tmc2209PwmConfDefault); err != nil {
		return err
	}
	ifcnt, err := d.readReg(tmc2209RegIfcnt)
	if err != nil {
		return err
	}
	if uint8(ifcnt) != uint8(baseline)+writes {
		return ErrTMC2209IfcntMismatch
	}
	return nil
}

// Enable switches the power stage on (ENN low, or TOFF restored when no ENN
// pin is wired).
func (d *TMC2209Minimal) Enable() error {
	if d.enPin != nil {
		return d.enPin.Set(false)
	}
	return d.writeReg(tmc2209RegChopConf, d.chopconf&^0xF|uint32(d.toff))
}

// Disable switches the power stage off; the motor freewheels.
func (d *TMC2209Minimal) Disable() error {
	if d.enPin != nil {
		return d.enPin.Set(true)
	}
	if d.chopconf&0xF != 0 {
		d.toff = uint8(d.chopconf & 0xF)
	}
	return d.writeReg(tmc2209RegChopConf, d.chopconf&^0xF)
}

// SetCurrent sets the run current in A RMS (at most 1.4); the hold current
// becomes one third of it. It fails with ErrTMC2209Unsupported while the
// internal sense resistors are selected.
func (d *TMC2209Minimal) SetCurrent(rmsA float32) error {
	run, err := d.currentToCS(rmsA)
	if err != nil {
		return err
	}
	hold, _ := d.currentToCS(rmsA / 3)
	d.runCS, d.holdCS = run, hold
	return d.writeIholdIrun()
}

// Run moves the motor with the internal step generator (VACTUAL).
// stepsPerS is in full steps per second at the configured microstepping;
// the sign gives the direction and 0 stops.
func (d *TMC2209Minimal) Run(stepsPerS float32) error {
	v := math.Floor(float64(stepsPerS)*float64(d.microsteps)*16777216/float64(d.fclk) + 0.5)
	if v > 8388607 {
		v = 8388607
	}
	if v < -8388607 {
		v = -8388607
	}
	return d.writeReg(tmc2209RegVActual, uint32(int32(v))&0xFFFFFF)
}

// Stop stops the internal step generator (VACTUAL = 0).
func (d *TMC2209Minimal) Stop() error {
	return d.writeReg(tmc2209RegVActual, 0)
}

// IsStandstill reports DRV_STATUS.stst.
func (d *TMC2209Minimal) IsStandstill() (bool, error) {
	s, err := d.readReg(tmc2209RegDrvStatus)
	return s>>31 == 1, err
}

// ReadFault returns the TMC2209Fault* bitmask, 0 when no fault is present.
func (d *TMC2209Minimal) ReadFault() (uint8, error) {
	s, err := d.readReg(tmc2209RegDrvStatus)
	if err != nil {
		return 0, err
	}
	var f uint8
	if s&0x01 != 0 {
		f |= TMC2209FaultOvertempWarning
	}
	if s&0x02 != 0 {
		f |= TMC2209FaultOvertemp
	}
	if s&0x0C != 0 {
		f |= TMC2209FaultShortGND
	}
	if s&0x30 != 0 {
		f |= TMC2209FaultShortSupply
	}
	if s&0xC0 != 0 {
		f |= TMC2209FaultOpenLoad
	}
	return f, nil
}

// TMC2209Full exposes the complete TMC2209 feature set; it embeds
// TMC2209Minimal.
type TMC2209Full struct {
	*TMC2209Minimal
	stepPin connection.OutputPin
	dirPin  connection.OutputPin
	diagPin connection.InputPin
}

// NewTMC2209Full creates the Full driver. stepPin, dirPin and diagPin are
// optional (nil); address is the UART node address 0-3; multiNode writes
// NODECONF.SENDDELAY = 2 first (several chips on one UART); fclkHz is the
// chip clock (12 MHz internal).
func NewTMC2209Full(conn connection.Connection, rSenseOhm float64, address uint8, enPin connection.OutputPin, echo bool,
	stepPin, dirPin connection.OutputPin, diagPin connection.InputPin, multiNode bool, fclkHz uint32) (*TMC2209Full, error) {
	m, err := newTMC2209Minimal(conn, rSenseOhm, address, enPin, echo, fclkHz, multiNode)
	if err != nil {
		return nil, err
	}
	return &TMC2209Full{TMC2209Minimal: m, stepPin: stepPin, dirPin: dirPin, diagPin: diagPin}, nil
}

func tmc2209Readable(r uint8) bool {
	switch r {
	case 0x00, 0x01, 0x02, 0x05, 0x06, 0x07, 0x12, 0x41, 0x6A, 0x6B, 0x6C, 0x6F, 0x70, 0x71, 0x72:
		return true
	}
	return false
}

func tmc2209Writable(r uint8) bool {
	switch r {
	case 0x00, 0x01, 0x03, 0x10, 0x11, 0x13, 0x14, 0x22, 0x40, 0x42, 0x6C, 0x70:
		return true
	}
	return false
}

// ReadRegister reads a raw 32-bit register; write-only and reserved
// registers return ErrTMC2209InvalidArgument.
func (d *TMC2209Full) ReadRegister(reg uint8) (uint32, error) {
	if !tmc2209Readable(reg) {
		return 0, ErrTMC2209InvalidArgument
	}
	return d.readReg(reg)
}

// WriteRegister writes a raw 32-bit register; read-only registers, OTP_PROG
// and FACTORY_CONF are rejected.
func (d *TMC2209Full) WriteRegister(reg uint8, value uint32) error {
	if !tmc2209Writable(reg) {
		return ErrTMC2209InvalidArgument
	}
	return d.writeReg(reg, value)
}

// SetHoldCurrent sets the standstill hold current in A RMS.
func (d *TMC2209Full) SetHoldCurrent(rmsA float32) error {
	cs, err := d.currentToCS(rmsA)
	if err != nil {
		return err
	}
	d.holdCS = cs
	return d.writeIholdIrun()
}

// SetCurrentDelay sets IHOLDDELAY (0-15) and TPOWERDOWN (0-255).
func (d *TMC2209Full) SetCurrentDelay(iholdDelay, tpowerdown uint8) error {
	if iholdDelay > 15 {
		return ErrTMC2209InvalidArgument
	}
	d.iholdDelay = iholdDelay
	if err := d.writeIholdIrun(); err != nil {
		return err
	}
	return d.writeReg(tmc2209RegTPowerDown, uint32(tpowerdown))
}

// SetMicrosteps selects the resolution: 1, 2, 4, 8, 16, 32, 64, 128 or 256.
func (d *TMC2209Full) SetMicrosteps(n uint16) error {
	var mres uint32
	switch n {
	case 256:
		mres = 0
	case 128:
		mres = 1
	case 64:
		mres = 2
	case 32:
		mres = 3
	case 16:
		mres = 4
	case 8:
		mres = 5
	case 4:
		mres = 6
	case 2:
		mres = 7
	case 1:
		mres = 8
	default:
		return ErrTMC2209InvalidArgument
	}
	if err := d.modify(tmc2209RegChopConf, 0xF<<24, mres<<24); err != nil {
		return err
	}
	d.microsteps = n
	return nil
}

// SetInterpolation enables 256-microstep interpolation (CHOPCONF.intpol).
func (d *TMC2209Full) SetInterpolation(enabled bool) error {
	return d.modify(tmc2209RegChopConf, 1<<28, boolBits(enabled, 1<<28))
}

// SetChopperMode selects SpreadCycle (true) or StealthChop (false).
func (d *TMC2209Full) SetChopperMode(spreadcycle bool) error {
	return d.modify(tmc2209RegGConf, tmc2209GConfSpreadCycle, boolBits(spreadcycle, tmc2209GConfSpreadCycle))
}

// SetStealthchopThreshold sets TPWMTHRS (20 bit); 0 disables the threshold.
func (d *TMC2209Full) SetStealthchopThreshold(tpwmthrs uint32) error {
	return d.writeReg(tmc2209RegTPwmThrs, tpwmthrs&0xFFFFF)
}

// ConfigureChopper writes the CHOPCONF timing fields; hend is the raw field
// 0-15 (offset -3).
func (d *TMC2209Full) ConfigureChopper(toff, tbl, hstrt, hend uint8) error {
	mask := uint32(0xF | 3<<15 | 7<<4 | 0xF<<7)
	value := uint32(toff&0xF) | uint32(tbl&3)<<15 | uint32(hstrt&7)<<4 | uint32(hend&0xF)<<7
	if toff != 0 {
		d.toff = toff & 0xF
	}
	return d.modify(tmc2209RegChopConf, mask, value)
}

// ConfigureStealthchop writes the PWMCONF StealthChop fields.
func (d *TMC2209Full) ConfigureStealthchop(autoscale, autograd bool, pwmOfs, pwmGrad, pwmFreq, pwmReg, pwmLim uint8) error {
	mask := uint32(0xF<<28 | 0xF<<24 | 1<<19 | 1<<18 | 3<<16 | 0xFF<<8 | 0xFF)
	value := uint32(pwmLim&0xF)<<28 | uint32(pwmReg&0xF)<<24 | boolBits(autograd, 1<<19) |
		boolBits(autoscale, 1<<18) | uint32(pwmFreq&3)<<16 | uint32(pwmGrad)<<8 | uint32(pwmOfs)
	return d.modify(tmc2209RegPwmConf, mask, value)
}

// SetFreewheel selects PWMCONF.freewheel (0-3).
func (d *TMC2209Full) SetFreewheel(mode uint8) error {
	if mode > 3 {
		return ErrTMC2209InvalidArgument
	}
	return d.modify(tmc2209RegPwmConf, 3<<20, uint32(mode)<<20)
}

// SetVsense selects the sense voltage range (true = low 0.18 V); it also
// changes the current conversion.
func (d *TMC2209Full) SetVsense(highSensitivity bool) error {
	if err := d.modify(tmc2209RegChopConf, 1<<17, boolBits(highSensitivity, 1<<17)); err != nil {
		return err
	}
	d.vsense = highSensitivity
	return nil
}

// SetInternalRsense selects the internal sense resistors; SetCurrent then
// fails with ErrTMC2209Unsupported.
func (d *TMC2209Full) SetInternalRsense(enabled bool) error {
	if err := d.modify(tmc2209RegGConf, tmc2209GConfInternalRsens, boolBits(enabled, tmc2209GConfInternalRsens)); err != nil {
		return err
	}
	d.internalRsense = enabled
	return nil
}

// SetAnalogScaling takes the current reference from VREF (true) or the
// internal reference.
func (d *TMC2209Full) SetAnalogScaling(enabled bool) error {
	return d.modify(tmc2209RegGConf, tmc2209GConfIScaleAnalog, boolBits(enabled, tmc2209GConfIScaleAnalog))
}

// SetDirectionInverted inverts the motor direction (GCONF.shaft).
func (d *TMC2209Full) SetDirectionInverted(inverted bool) error {
	return d.modify(tmc2209RegGConf, tmc2209GConfShaft, boolBits(inverted, tmc2209GConfShaft))
}

// SetIndexOutput selects the INDEX output: 0 first microstep, 1 otpw, 2 step pulses.
func (d *TMC2209Full) SetIndexOutput(mode uint8) error {
	var bits uint32
	switch mode {
	case 0:
	case 1:
		bits = tmc2209GConfIndexOtpw
	case 2:
		bits = tmc2209GConfIndexStep
	default:
		return ErrTMC2209InvalidArgument
	}
	return d.modify(tmc2209RegGConf, tmc2209GConfIndexOtpw|tmc2209GConfIndexStep, bits)
}

// SetDriverProtection keeps (true) or disables (false) the low-side-short
// and short-to-GND protections.
func (d *TMC2209Full) SetDriverProtection(shortLS, shortGND bool) error {
	value := boolBits(!shortLS, 1<<31) | boolBits(!shortGND, 1<<30)
	return d.modify(tmc2209RegChopConf, 1<<31|1<<30, value)
}

// SetDoubleEdge makes the chip step on both STEP edges (CHOPCONF.dedge).
func (d *TMC2209Full) SetDoubleEdge(enabled bool) error {
	return d.modify(tmc2209RegChopConf, 1<<29, boolBits(enabled, 1<<29))
}

// SetSendDelay sets NODECONF.SENDDELAY (0-15).
func (d *TMC2209Full) SetSendDelay(n uint8) error {
	if n > 15 {
		return ErrTMC2209InvalidArgument
	}
	return d.writeReg(tmc2209RegNodeConf, uint32(n)<<8)
}

// SetStallThreshold sets SGTHRS (0-255, higher = more sensitive); a stall is
// signalled when SG_RESULT <= 2*sgthrs.
func (d *TMC2209Full) SetStallThreshold(sgthrs uint8) error {
	return d.writeReg(tmc2209RegSgThrs, uint32(sgthrs))
}

// SetCoolstepThreshold sets TCOOLTHRS (20 bit), the lower velocity limit for
// CoolStep and the DIAG stall output; 0 disables.
func (d *TMC2209Full) SetCoolstepThreshold(tcoolthrs uint32) error {
	if tcoolthrs > 0xFFFFF {
		return ErrTMC2209InvalidArgument
	}
	return d.writeReg(tmc2209RegTCoolThrs, tcoolthrs)
}

// ConfigureCoolstep writes COOLCONF: semin 0-15 (0 disables CoolStep), semax
// 0-15, seup 0-3, sedn 0-3, seimin 0 (half IRUN minimum) or 1 (quarter).
func (d *TMC2209Full) ConfigureCoolstep(semin, semax, seup, sedn, seimin uint8) error {
	if semin > 15 || semax > 15 || seup > 3 || sedn > 3 || seimin > 1 {
		return ErrTMC2209InvalidArgument
	}
	return d.writeReg(tmc2209RegCoolConf,
		uint32(semin)|uint32(seup)<<5|uint32(semax)<<8|uint32(sedn)<<13|uint32(seimin)<<15)
}

// ReadStallguard returns SG_RESULT (0-510, higher means less load).
func (d *TMC2209Full) ReadStallguard() (uint16, error) {
	v, err := d.readReg(tmc2209RegSgResult)
	return uint16(v & 0x3FF), err
}

// IsStalled returns the DIAG pin level (stall or driver error). It returns
// ErrTMC2209NoPin when no diagPin was given.
func (d *TMC2209Full) IsStalled() (bool, error) {
	if d.diagPin == nil {
		return false, ErrTMC2209NoPin
	}
	return d.diagPin.IsHigh()
}

// Step emits |count| STEP pulses on the GPIO pin (negative reverses via
// DIR); each pulse is high and low for delay.
func (d *TMC2209Full) Step(count int, delay time.Duration) error {
	if d.stepPin == nil || (count < 0 && d.dirPin == nil) {
		return ErrTMC2209NoPin
	}
	if d.dirPin != nil {
		if err := d.dirPin.Set(count >= 0); err != nil {
			return err
		}
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

// MicrostepPosition returns MSCNT (0-1023).
func (d *TMC2209Full) MicrostepPosition() (uint16, error) {
	v, err := d.readReg(tmc2209RegMsCnt)
	return uint16(v & 0x3FF), err
}

// ReadCurrentScale returns DRV_STATUS.CS_ACTUAL (0-31).
func (d *TMC2209Full) ReadCurrentScale() (uint8, error) {
	v, err := d.readReg(tmc2209RegDrvStatus)
	return uint8(v >> 16 & 0x1F), err
}

// ReadCoilCurrents returns the two signed 9-bit MSCURACT values in register order.
func (d *TMC2209Full) ReadCoilCurrents() (low, high int16, err error) {
	v, err := d.readReg(tmc2209RegMsCurAct)
	return tmc2209SignExtend(v, 9), tmc2209SignExtend(v>>16, 9), err
}

// ReadTstep returns TSTEP (20 bit).
func (d *TMC2209Full) ReadTstep() (uint32, error) {
	v, err := d.readReg(tmc2209RegTStep)
	return v & 0xFFFFF, err
}

// ReadTemperatureFlags returns t120..t157 as bits 0-3.
func (d *TMC2209Full) ReadTemperatureFlags() (uint8, error) {
	v, err := d.readReg(tmc2209RegDrvStatus)
	return uint8(v >> 8 & 0xF), err
}

// ReadStatus decodes DRV_STATUS.
func (d *TMC2209Full) ReadStatus() (TMC2209Status, error) {
	s, err := d.readReg(tmc2209RegDrvStatus)
	return TMC2209Status{
		Standstill:  s>>31&1 == 1,
		Stealth:     s>>30&1 == 1,
		CSActual:    uint8(s >> 16 & 0x1F),
		OTPW:        s&0x01 != 0,
		OT:          s&0x02 != 0,
		ShortGND:    s&0x0C != 0,
		ShortSupply: s&0x30 != 0,
		OpenLoad:    s&0xC0 != 0,
		T120:        s&(1<<8) != 0,
		T143:        s&(1<<9) != 0,
		T150:        s&(1<<10) != 0,
		T157:        s&(1<<11) != 0,
	}, err
}

// ReadGlobalStatus decodes GSTAT; clear writes the flags back.
func (d *TMC2209Full) ReadGlobalStatus(clear bool) (TMC2209GlobalStatus, error) {
	g, err := d.readReg(tmc2209RegGStat)
	if err != nil {
		return TMC2209GlobalStatus{}, err
	}
	if clear {
		if err := d.writeReg(tmc2209RegGStat, 0x07); err != nil {
			return TMC2209GlobalStatus{}, err
		}
	}
	return TMC2209GlobalStatus{Reset: g&1 != 0, DrvErr: g&2 != 0, UVCP: g&4 != 0}, nil
}

// ReadIfcnt returns the IFCNT write counter.
func (d *TMC2209Full) ReadIfcnt() (uint8, error) {
	v, err := d.readReg(tmc2209RegIfcnt)
	return uint8(v), err
}

// ReadIoin returns the raw IOIN word, SEL_A and VERSION.
func (d *TMC2209Full) ReadIoin() (TMC2209Ioin, error) {
	v, err := d.readReg(tmc2209RegIoin)
	return TMC2209Ioin{
		Raw: v, ENN: v&1 != 0, MS1: v&4 != 0, MS2: v&8 != 0, Diag: v&0x10 != 0,
		PdnUart: v&0x40 != 0, Step: v&0x80 != 0, Spread: v&0x100 != 0, Dir: v&0x200 != 0,
		Version: uint8(v >> 24),
	}, err
}

// ReadOtp returns the 24-bit OTP_READ word.
func (d *TMC2209Full) ReadOtp() (uint32, error) {
	v, err := d.readReg(tmc2209RegOtpRead)
	return v & 0xFFFFFF, err
}

// ReadPwmAuto returns the PWM_SCALE / PWM_AUTO results.
func (d *TMC2209Full) ReadPwmAuto() (TMC2209PwmAuto, error) {
	scale, err := d.readReg(tmc2209RegPwmScale)
	if err != nil {
		return TMC2209PwmAuto{}, err
	}
	auto, err := d.readReg(tmc2209RegPwmAuto)
	if err != nil {
		return TMC2209PwmAuto{}, err
	}
	return TMC2209PwmAuto{
		PwmScaleSum:  uint8(scale),
		PwmScaleAuto: tmc2209SignExtend(scale>>16, 9),
		PwmOfsAuto:   uint8(auto),
		PwmGradAuto:  uint8(auto >> 16),
	}, nil
}
