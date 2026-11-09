package motor

import (
	"errors"
	"fmt"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// TMC5160 register addresses.
const (
	tmc5160RegGConf       uint8 = 0x00
	tmc5160RegGStat       uint8 = 0x01
	tmc5160RegIfcnt       uint8 = 0x02
	tmc5160RegSlaveConf   uint8 = 0x03
	tmc5160RegIoin        uint8 = 0x04
	tmc5160RegOutput      uint8 = 0x04
	tmc5160RegXCompare    uint8 = 0x05
	tmc5160RegShortConf   uint8 = 0x09
	tmc5160RegDrvConf     uint8 = 0x0A
	tmc5160RegGlobalScale uint8 = 0x0B
	tmc5160RegOffsetRead  uint8 = 0x0C
	tmc5160RegIholdIrun   uint8 = 0x10
	tmc5160RegTPowerDown  uint8 = 0x11
	tmc5160RegTStep       uint8 = 0x12
	tmc5160RegTPwmThrs    uint8 = 0x13
	tmc5160RegTCoolThrs   uint8 = 0x14
	tmc5160RegTHigh       uint8 = 0x15
	tmc5160RegRampMode    uint8 = 0x20
	tmc5160RegXActual     uint8 = 0x21
	tmc5160RegVActual     uint8 = 0x22
	tmc5160RegVStart      uint8 = 0x23
	tmc5160RegA1          uint8 = 0x24
	tmc5160RegV1          uint8 = 0x25
	tmc5160RegAMax        uint8 = 0x26
	tmc5160RegVMax        uint8 = 0x27
	tmc5160RegDMax        uint8 = 0x28
	tmc5160RegD1          uint8 = 0x2A
	tmc5160RegVStop       uint8 = 0x2B
	tmc5160RegTZeroWait   uint8 = 0x2C
	tmc5160RegXTarget     uint8 = 0x2D
	tmc5160RegVDcMin      uint8 = 0x33
	tmc5160RegSwMode      uint8 = 0x34
	tmc5160RegRampStat    uint8 = 0x35
	tmc5160RegXLatch      uint8 = 0x36
	tmc5160RegEncMode     uint8 = 0x38
	tmc5160RegXEnc        uint8 = 0x39
	tmc5160RegEncConst    uint8 = 0x3A
	tmc5160RegEncStatus   uint8 = 0x3B
	tmc5160RegEncLatch    uint8 = 0x3C
	tmc5160RegEncDev      uint8 = 0x3D
	tmc5160RegMslut0      uint8 = 0x60
	tmc5160RegMslutSel    uint8 = 0x68
	tmc5160RegMslutStart  uint8 = 0x69
	tmc5160RegMsCnt       uint8 = 0x6A
	tmc5160RegMsCurAct    uint8 = 0x6B
	tmc5160RegChopConf    uint8 = 0x6C
	tmc5160RegCoolConf    uint8 = 0x6D
	tmc5160RegDcCtrl      uint8 = 0x6E
	tmc5160RegDrvStatus   uint8 = 0x6F
	tmc5160RegPwmConf     uint8 = 0x70
	tmc5160RegPwmScale    uint8 = 0x71
	tmc5160RegPwmAuto     uint8 = 0x72
)

const (
	tmc5160GConfEnPwmMode  uint32 = 1 << 2
	tmc5160GConfShaft      uint32 = 1 << 4
	tmc5160GConfSmallHyst  uint32 = 1 << 14
	tmc5160GConfStopEnable uint32 = 1 << 15
	tmc5160GConfDirectMode uint32 = 1 << 16
	tmc5160SwSgStop        uint32 = 1 << 10
	tmc5160SwSoftStop      uint32 = 1 << 11

	tmc5160Version      uint8   = 0x30
	tmc5160VFS          float64 = 0.325
	tmc5160ChopConfDflt uint32  = 0x000100C3
	tmc5160PwmConfReset uint32  = 0xC40C001E
)

// Fault bitmask returned by ReadFault.
const (
	TMC5160FaultOvertempWarning uint8 = 0x01 // DRV_STATUS.otpw
	TMC5160FaultOvertemp        uint8 = 0x02 // DRV_STATUS.ot
	TMC5160FaultShortGND        uint8 = 0x04 // s2ga / s2gb
	TMC5160FaultShortSupply     uint8 = 0x08 // s2vsa / s2vsb
	TMC5160FaultOpenLoad        uint8 = 0x10 // ola / olb (informative only)
)

// Errors returned by the TMC5160 drivers.
var (
	ErrTMC5160NotFound         = errors.New("TMC5160: device not found")
	ErrTMC5160StepDirMode      = errors.New("TMC5160: chip is strapped for STEP/DIR (SD_MODE = 1)")
	ErrTMC5160NotAcknowledged  = errors.New("TMC5160: write not acknowledged")
	ErrTMC5160InvalidArgument  = errors.New("TMC5160: invalid argument")
	ErrTMC5160UartOnly         = errors.New("TMC5160: operation needs the UART bus")
	ErrTMC5160DirectModeNeeded = errors.New("TMC5160: direct mode required")
	ErrTMC5160Timeout          = errors.New("TMC5160: timed out")
	ErrTMC5160EchoMismatch     = errors.New("TMC5160: UART echo mismatch")
	ErrTMC5160CRC              = errors.New("TMC5160: UART reply CRC error")
	ErrTMC5160Malformed        = errors.New("TMC5160: malformed UART reply")
	ErrTMC5160NoPin            = errors.New("TMC5160: required pin not configured")
)

func tmc5160Readable(r uint8) bool {
	switch r {
	case 0x00, 0x01, 0x02, 0x04, 0x07, 0x0C, 0x12, 0x20, 0x21, 0x22, 0x2D, 0x34, 0x35, 0x36, 0x38, 0x39, 0x3B, 0x3C,
		0x6A, 0x6B, 0x6C, 0x6F, 0x71, 0x72, 0x73:
		return true
	}
	return false
}

func tmc5160Writable(r uint8) bool {
	switch r {
	case 0x00, 0x01, 0x03, 0x04, 0x05, 0x09, 0x0A, 0x0B, 0x10, 0x11, 0x13, 0x14, 0x15, 0x20, 0x21, 0x23, 0x24, 0x25,
		0x26, 0x27, 0x28, 0x2A, 0x2B, 0x2C, 0x2D, 0x33, 0x34, 0x35, 0x38, 0x39, 0x3A, 0x3B, 0x3D, 0x60, 0x61, 0x62,
		0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6C, 0x6D, 0x6E, 0x70:
		return true
	}
	return false
}

// TMC5160CRC8 is the CRC-8 (polynomial 0x07, init 0, LSB first) over a UART datagram body.
func TMC5160CRC8(data []byte) uint8 {
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

func tmc5160SignExtend(v uint32, bits uint) int32 {
	v &= (1 << bits) - 1
	if v&(1<<(bits-1)) != 0 {
		return int32(v) - int32(1<<bits)
	}
	return int32(v)
}

func tmc5160Flag(on bool, mask uint32) uint32 {
	if on {
		return mask
	}
	return 0
}

// TMC5160Status is the decoded DRV_STATUS register.
type TMC5160Status struct {
	Standstill  bool   // stst
	StealthChop bool   // stealth
	FsActive    bool   // full-step operation active
	CSActual    uint8  // actual current scale 0-31
	SGResult    uint16 // StallGuard2 result 0-1023
	StallGuard  bool   // stall detected
	OTPW        bool   // over-temperature pre-warning
	OT          bool   // over-temperature shutdown
	ShortGND    bool   // short to GND, either phase
	ShortSupply bool   // short to supply, either phase
	OpenLoad    bool   // open load, either phase
}

// TMC5160RampStatus is the decoded RAMP_STAT register.
type TMC5160RampStatus struct {
	StatusStopL, StatusStopR, StatusLatchL, StatusLatchR     bool
	EventStopL, EventStopR, EventStopSG, EventPosReached     bool
	VelocityReached, PositionReached, VZero, TZeroWaitActive bool
	SecondMove, StatusSG                                     bool
}

// TMC5160GlobalStatus is the decoded GSTAT register.
type TMC5160GlobalStatus struct {
	Reset  bool // IC was reset since the last read
	DrvErr bool // driver shut down by over-temperature or short
	UVCP   bool // charge-pump undervoltage
}

// TMC5160Ioin is the decoded IOIN register.
type TMC5160Ioin struct {
	Raw                                                    uint32
	Refl, Refr, Encb, Enca, DrvEnn, Encn, SdMode, SwcompIn bool
	Version                                                uint8 // silicon version (0x30)
}

// TMC5160Minimal drives a Trinamic TMC5160 stepper controller/driver over
// SPI (Mode 3) or single-wire UART. It brings the chip up with the internal
// SixPoint ramp generator in positioning mode (256 microsteps, StealthChop
// below TPWMTHRS = 500, 0.5 A RMS run current, 200 full steps/s, 400 full
// steps/s^2) and moves the motor with register writes -- no STEP pulses.
// Positions are in full steps, velocities in full steps per second,
// accelerations in full steps per second squared.
type TMC5160Minimal struct {
	conn      connection.Connection
	enPin     connection.OutputPin
	rSense    float64
	fclk      float64
	uart      bool
	echo      bool
	address   uint8
	multiNode bool

	shadow map[uint8]uint32
	usc    uint32
	toff   uint8

	rampmode   uint8
	speed      float32
	speedReg   uint32
	accel      float32
	irun       uint8
	ihold      uint8
	iholdDelay uint8
	sendDelay  uint8
	lastDist   float32
	lastSpeed  float32
	lastAccel  float32
	writes     uint8
}

// NewTMC5160Minimal creates the driver on SPI (Mode 3), probes the chip
// (IOIN.VERSION 0x30, SD_MODE 0) and applies the default configuration.
// rSenseOhm is the external sense resistor (0.075 ohm on common boards);
// enPin is the optional DRV_ENN output (active low, held high -- driver off --
// until Enable).
func NewTMC5160Minimal(conn connection.Connection, rSenseOhm float64, enPin connection.OutputPin) (*TMC5160Minimal, error) {
	return newTMC5160(conn, rSenseOhm, enPin, false, 0, false, false, 12000000)
}

// NewTMC5160MinimalUART creates the driver on the single-wire UART. address
// is the node address 0-253; echo is true when TX and RX share the wire;
// multiNode writes SLAVECONF.SENDDELAY = 2 first (several chips on one UART).
func NewTMC5160MinimalUART(conn connection.Connection, rSenseOhm float64, address uint8, enPin connection.OutputPin, echo, multiNode bool) (*TMC5160Minimal, error) {
	if address > 253 {
		return nil, ErrTMC5160InvalidArgument
	}
	return newTMC5160(conn, rSenseOhm, enPin, true, address, echo, multiNode, 12000000)
}

func newTMC5160(conn connection.Connection, rSenseOhm float64, enPin connection.OutputPin, uart bool, address uint8, echo, multiNode bool, fclk uint32) (*TMC5160Minimal, error) {
	d := &TMC5160Minimal{
		conn: conn, enPin: enPin, rSense: rSenseOhm, fclk: float64(fclk), uart: uart, echo: echo, address: address,
		multiNode: multiNode, shadow: map[uint8]uint32{}, usc: 256, toff: 3, speed: 200, accel: 400, iholdDelay: 6,
		lastSpeed: 200, lastAccel: 400,
	}
	if err := d.initialise(); err != nil {
		return nil, err
	}
	return d, nil
}

// ---- transport ----

func (d *TMC5160Minimal) uartWrite(reg uint8, value uint32) error {
	dg := []byte{0x05, d.address, reg | 0x80, byte(value >> 24), byte(value >> 16), byte(value >> 8), byte(value), 0}
	dg[7] = TMC5160CRC8(dg[:7])
	if err := d.conn.Write(dg); err != nil {
		return err
	}
	if d.echo {
		echoed, err := d.conn.Read(8)
		if err != nil {
			return err
		}
		if string(echoed) != string(dg) {
			return ErrTMC5160EchoMismatch
		}
	}
	return nil
}

func (d *TMC5160Minimal) uartRead(reg uint8) (uint32, error) {
	req := []byte{0x05, d.address, reg & 0x7F, 0}
	req[3] = TMC5160CRC8(req[:3])
	n := 8
	if d.echo {
		n = 12
	}
	raw, err := d.conn.WriteRead(req, n)
	if err != nil {
		return 0, err
	}
	if len(raw) != n {
		return 0, ErrTMC5160Malformed
	}
	if d.echo {
		if string(raw[:4]) != string(req) {
			return 0, ErrTMC5160EchoMismatch
		}
		raw = raw[4:]
	}
	if raw[0] != 0x05 || raw[1] != 0xFF || raw[2] != reg&0x7F {
		return 0, ErrTMC5160Malformed
	}
	if TMC5160CRC8(raw[:7]) != raw[7] {
		return 0, ErrTMC5160CRC
	}
	return uint32(raw[3])<<24 | uint32(raw[4])<<16 | uint32(raw[5])<<8 | uint32(raw[6]), nil
}

func (d *TMC5160Minimal) spiSecond() (uint32, error) {
	reply, err := d.conn.WriteRead([]byte{tmc5160RegGConf}, 4)
	if err != nil {
		return 0, err
	}
	if len(reply) != 4 {
		return 0, fmt.Errorf("TMC5160: short read (%d bytes)", len(reply))
	}
	return uint32(reply[0])<<24 | uint32(reply[1])<<16 | uint32(reply[2])<<8 | uint32(reply[3]), nil
}

func (d *TMC5160Minimal) writeReg(reg uint8, value uint32) error {
	if d.uart {
		if err := d.uartWrite(reg, value); err != nil {
			time.Sleep(2 * time.Millisecond)
			if err = d.uartWrite(reg, value); err != nil {
				return err
			}
		}
	} else {
		frame := []byte{reg | 0x80, byte(value >> 24), byte(value >> 16), byte(value >> 8), byte(value)}
		if err := d.conn.Write(frame); err != nil {
			return err
		}
	}
	d.writes++
	d.shadow[reg] = value
	return nil
}

func (d *TMC5160Minimal) readReg(reg uint8) (uint32, error) {
	if d.uart {
		v, err := d.uartRead(reg)
		if err != nil {
			time.Sleep(2 * time.Millisecond)
			v, err = d.uartRead(reg)
		}
		return v, err
	}
	if _, err := d.conn.WriteRead([]byte{reg & 0x7F}, 4); err != nil {
		return 0, err
	}
	return d.spiSecond()
}

// writeVerified writes and checks the acknowledge: IFCNT + 1 on UART, a
// follow-up datagram on SPI.
func (d *TMC5160Minimal) writeVerified(reg uint8, value uint32) error {
	if d.uart {
		before, err := d.readReg(tmc5160RegIfcnt)
		if err != nil {
			return err
		}
		if err := d.writeReg(reg, value); err != nil {
			return err
		}
		after, err := d.readReg(tmc5160RegIfcnt)
		if err != nil {
			return err
		}
		if uint8(after) != uint8(before)+1 {
			return ErrTMC5160NotAcknowledged
		}
		return nil
	}
	if err := d.writeReg(reg, value); err != nil {
		return err
	}
	_, err := d.spiSecond()
	return err
}

func (d *TMC5160Minimal) first(reg uint8, reset uint32) {
	if _, ok := d.shadow[reg]; !ok {
		d.shadow[reg] = reset
	}
}

func (d *TMC5160Minimal) modify(reg uint8, mask, value uint32) error {
	return d.writeReg(reg, (d.shadow[reg]&^mask)|(value&mask))
}

func (d *TMC5160Minimal) vReg(v float32) int64 {
	return int64(math.Round(float64(v) * float64(d.usc) * 16777216.0 / d.fclk))
}

func (d *TMC5160Minimal) aReg(a float32) int64 {
	return int64(math.Round(float64(a) * float64(d.usc) * 2199023255552.0 / (d.fclk * d.fclk)))
}

func tmc5160Check(v, limit int64) (uint32, error) {
	if v < 0 || v > limit {
		return 0, ErrTMC5160InvalidArgument
	}
	return uint32(v), nil
}

func (d *TMC5160Minimal) iMax() float64 {
	return tmc5160VFS / (d.rSense * math.Sqrt2)
}

func (d *TMC5160Minimal) applyCurrent(rmsA float32) error {
	r := float64(rmsA) / d.iMax()
	if r > 1.0 || r < 1.0/64 {
		return ErrTMC5160InvalidArgument
	}
	var irun int
	var scaler uint32
	if r >= 0.5 {
		irun, scaler = 31, uint32(math.Round(256*r))
	} else {
		irun = int(math.Round(64*r)) - 1
		if irun < 0 {
			irun = 0
		}
		if irun > 31 {
			irun = 31
		}
		scaler = 128
	}
	if scaler >= 256 {
		scaler = 0
	}
	if err := d.writeReg(tmc5160RegGlobalScale, scaler); err != nil {
		return err
	}
	d.irun, d.ihold, d.iholdDelay = uint8(irun), uint8(irun)/3, 6
	return d.writeIholdIrun()
}

func (d *TMC5160Minimal) writeIholdIrun() error {
	return d.writeReg(tmc5160RegIholdIrun,
		uint32(d.ihold&0x1F)|uint32(d.irun&0x1F)<<8|uint32(d.iholdDelay&0xF)<<16)
}

func (d *TMC5160Minimal) initialise() error {
	if d.enPin != nil {
		if err := d.enPin.Set(true); err != nil {
			return err
		}
	}
	time.Sleep(10 * time.Millisecond)
	var base uint8
	if d.uart {
		v, err := d.readReg(tmc5160RegIfcnt)
		if err != nil {
			return fmt.Errorf("TMC5160: device not responding: %w", err)
		}
		base = uint8(v)
	}
	ioin, err := d.readReg(tmc5160RegIoin)
	if err != nil {
		return fmt.Errorf("TMC5160: device not responding: %w", err)
	}
	if uint8(ioin>>24) != tmc5160Version {
		return ErrTMC5160NotFound
	}
	if ioin&(1<<6) != 0 {
		return ErrTMC5160StepDirMode
	}
	d.writes = 0
	if d.uart && d.multiNode {
		d.sendDelay = 2
		if err := d.writeVerified(tmc5160RegSlaveConf, 2<<8); err != nil {
			return err
		}
		d.writes = 0
	}
	if _, err := d.readReg(tmc5160RegGStat); err != nil {
		return err
	}
	rms := float32(0.5)
	if float64(rms) > d.iMax() {
		rms = float32(d.iMax())
	}
	steps := []func() error{
		func() error { return d.writeReg(tmc5160RegGStat, 0x07) },
		func() error { return d.writeReg(tmc5160RegGlobalScale, 0) },
		func() error { return d.applyCurrent(rms) },
		func() error { return d.writeReg(tmc5160RegTPowerDown, 10) },
		func() error { return d.writeReg(tmc5160RegChopConf, tmc5160ChopConfDflt) },
		func() error { return d.writeReg(tmc5160RegGConf, tmc5160GConfEnPwmMode) },
		func() error { return d.writeReg(tmc5160RegTPwmThrs, 500) },
		func() error { return d.writeReg(tmc5160RegRampMode, 0) },
		func() error { return d.writeReg(tmc5160RegVStart, 0) },
		func() error { return d.writeReg(tmc5160RegV1, 0) },
		func() error { return d.writeReg(tmc5160RegVStop, 10) },
		func() error { return d.writeReg(tmc5160RegTZeroWait, 0) },
		func() error { return d.SetSpeed(200) },
		func() error { return d.SetAcceleration(400) },
	}
	for _, s := range steps {
		if err := s(); err != nil {
			return err
		}
	}
	if d.uart {
		after, err := d.readReg(tmc5160RegIfcnt)
		if err != nil {
			return err
		}
		if uint8(after) != base+d.writes {
			return ErrTMC5160NotAcknowledged
		}
	} else {
		for _, r := range []uint8{tmc5160RegGConf, tmc5160RegChopConf} {
			v, err := d.readReg(r)
			if err != nil {
				return err
			}
			if v != d.shadow[r] {
				return ErrTMC5160NotAcknowledged
			}
		}
	}
	d.rampmode = 0
	return nil
}

// Enable switches the power stage on (DRV_ENN low, or TOFF restored when no
// ENN pin is wired) and waits 150 ms for StealthChop to settle.
func (d *TMC5160Minimal) Enable() error {
	var err error
	if d.enPin != nil {
		err = d.enPin.Set(false)
	} else {
		err = d.writeReg(tmc5160RegChopConf, (d.shadow[tmc5160RegChopConf]&^0xF)|uint32(d.toff))
	}
	time.Sleep(150 * time.Millisecond)
	return err
}

// Disable switches the power stage off; the motor outputs float.
func (d *TMC5160Minimal) Disable() error {
	if d.enPin != nil {
		return d.enPin.Set(true)
	}
	chop := d.shadow[tmc5160RegChopConf]
	if chop&0xF != 0 {
		d.toff = uint8(chop & 0xF)
	}
	return d.writeReg(tmc5160RegChopConf, chop&^0xF)
}

// SetCurrent sets the run current in A RMS (hold becomes one third) by
// programming GLOBALSCALER and IHOLD_IRUN. It returns
// ErrTMC5160InvalidArgument above the sense-resistor maximum or below 1/64 of it.
func (d *TMC5160Minimal) SetCurrent(rmsA float32) error { return d.applyCurrent(rmsA) }

// SetSpeed sets VMAX in full steps per second (applied immediately in positioning mode).
func (d *TMC5160Minimal) SetSpeed(stepsPerS float32) error {
	if stepsPerS <= 0 {
		return ErrTMC5160InvalidArgument
	}
	reg, err := tmc5160Check(d.vReg(stepsPerS), (1<<23)-512)
	if err != nil {
		return err
	}
	d.speed, d.speedReg = stepsPerS, reg
	if d.rampmode == 0 {
		return d.writeReg(tmc5160RegVMax, reg)
	}
	return nil
}

// SetAcceleration sets a single trapezoid: A1 = AMAX = DMAX = D1 (with V1 = 0),
// in full steps per second squared.
func (d *TMC5160Minimal) SetAcceleration(stepsPerS2 float32) error {
	if stepsPerS2 <= 0 {
		return ErrTMC5160InvalidArgument
	}
	reg, err := tmc5160Check(d.aReg(stepsPerS2), 0xFFFF)
	if err != nil || reg < 1 {
		return ErrTMC5160InvalidArgument
	}
	d.accel = stepsPerS2
	for _, r := range []uint8{tmc5160RegA1, tmc5160RegAMax, tmc5160RegDMax, tmc5160RegD1} {
		if err := d.writeReg(r, reg); err != nil {
			return err
		}
	}
	return nil
}

// MoveTo starts a positioning move to an absolute position in full steps (non-blocking).
func (d *TMC5160Minimal) MoveTo(position float32) error {
	target := int32(math.Round(float64(position) * float64(d.usc)))
	cur, err := d.Position()
	if err != nil {
		return err
	}
	d.lastDist = float32(math.Abs(float64(position - cur)))
	d.lastSpeed, d.lastAccel = d.speed, d.accel
	if err := d.writeReg(tmc5160RegVMax, d.speedReg); err != nil {
		return err
	}
	if err := d.writeReg(tmc5160RegRampMode, 0); err != nil {
		return err
	}
	d.rampmode = 0
	return d.writeReg(tmc5160RegXTarget, uint32(target))
}

// MoveSteps is a blocking relative move; it returns when the target is reached.
// speed 0 keeps the configured speed, otherwise it overrides it for this move.
func (d *TMC5160Minimal) MoveSteps(fullSteps float32, speed float32) error {
	saved := d.speed
	if speed > 0 {
		if err := d.SetSpeed(speed); err != nil {
			return err
		}
	}
	cur, err := d.Position()
	if err == nil {
		err = d.MoveTo(cur + fullSteps)
	}
	if err == nil {
		err = d.WaitUntilReached(0)
	}
	if speed > 0 {
		if e := d.SetSpeed(saved); err == nil {
			err = e
		}
	}
	return err
}

// Run enters velocity mode: ramp to a velocity (sign = direction); 0 ramps to standstill.
func (d *TMC5160Minimal) Run(stepsPerS float32) error {
	reg, err := tmc5160Check(d.vReg(float32(math.Abs(float64(stepsPerS)))), (1<<23)-512)
	if err != nil {
		return err
	}
	mode := uint8(1)
	if stepsPerS < 0 {
		mode = 2
	}
	if err := d.writeReg(tmc5160RegRampMode, uint32(mode)); err != nil {
		return err
	}
	d.rampmode = mode
	return d.writeReg(tmc5160RegVMax, reg)
}

// Stop is a controlled stop: velocity mode with VMAX = 0.
func (d *TMC5160Minimal) Stop() error {
	if err := d.writeReg(tmc5160RegRampMode, 1); err != nil {
		return err
	}
	d.rampmode = 1
	return d.writeReg(tmc5160RegVMax, 0)
}

// Position returns XACTUAL in full steps.
func (d *TMC5160Minimal) Position() (float32, error) {
	v, err := d.readReg(tmc5160RegXActual)
	if err != nil {
		return 0, err
	}
	return float32(int32(v)) / float32(d.usc), nil
}

// SetPosition defines the current position without motion (writes XACTUAL and XTARGET).
func (d *TMC5160Minimal) SetPosition(position float32) error {
	v := uint32(int32(math.Round(float64(position) * float64(d.usc))))
	if err := d.writeReg(tmc5160RegXActual, v); err != nil {
		return err
	}
	return d.writeReg(tmc5160RegXTarget, v)
}

// Velocity returns VACTUAL in full steps per second, signed.
func (d *TMC5160Minimal) Velocity() (float32, error) {
	v, err := d.readReg(tmc5160RegVActual)
	if err != nil {
		return 0, err
	}
	return float32(float64(tmc5160SignExtend(v, 24)) * (d.fclk / 2 / 8388608.0) / float64(d.usc)), nil
}

// IsPositionReached returns RAMP_STAT.position_reached.
func (d *TMC5160Minimal) IsPositionReached() (bool, error) {
	v, err := d.readReg(tmc5160RegRampStat)
	return v&(1<<9) != 0, err
}

// WaitUntilReached polls RAMP_STAT every 5 ms until position_reached
// (positioning) or vzero (velocity mode). timeoutS 0 estimates the timeout
// from the last move. It returns ErrTMC5160Timeout on expiry.
func (d *TMC5160Minimal) WaitUntilReached(timeoutS float32) error {
	if timeoutS <= 0 {
		timeoutS = 2*d.lastDist/d.lastSpeed + 2*d.lastSpeed/d.lastAccel + 1
	}
	bit := uint32(1 << 9)
	if d.rampmode != 0 {
		bit = 1 << 10
	}
	time.Sleep(time.Millisecond)
	deadline := time.Now().Add(time.Duration(float64(timeoutS) * float64(time.Second)))
	for {
		v, err := d.readReg(tmc5160RegRampStat)
		if err != nil {
			return err
		}
		if v&bit != 0 {
			return nil
		}
		if time.Now().After(deadline) {
			return ErrTMC5160Timeout
		}
		time.Sleep(5 * time.Millisecond)
	}
}

// IsStandstill returns DRV_STATUS.stst.
func (d *TMC5160Minimal) IsStandstill() (bool, error) {
	v, err := d.readReg(tmc5160RegDrvStatus)
	return v>>31 == 1, err
}

// ReadFault returns a bitmask of TMC5160Fault* constants, 0 when none.
func (d *TMC5160Minimal) ReadFault() (uint8, error) {
	s, err := d.readReg(tmc5160RegDrvStatus)
	if err != nil {
		return 0, err
	}
	var f uint8
	if s&(1<<26) != 0 {
		f |= TMC5160FaultOvertempWarning
	}
	if s&(1<<25) != 0 {
		f |= TMC5160FaultOvertemp
	}
	if s&(3<<27) != 0 {
		f |= TMC5160FaultShortGND
	}
	if s&(3<<12) != 0 {
		f |= TMC5160FaultShortSupply
	}
	if s&(3<<29) != 0 {
		f |= TMC5160FaultOpenLoad
	}
	return f, nil
}

// TMC5160Full extends TMC5160Minimal with raw register access, the full
// SixPoint ramp, reference switches, chopper / StealthChop tuning,
// StallGuard2 / CoolStep, DcStep, the ABN encoder and all status read-backs.
type TMC5160Full struct {
	*TMC5160Minimal
	diag0, diag1 connection.InputPin
}

// NewTMC5160Full creates the Full driver on SPI (Mode 3); diag0 / diag1 are
// optional DIAG0 / DIAG1 inputs (nil if not wired).
func NewTMC5160Full(conn connection.Connection, rSenseOhm float64, enPin connection.OutputPin, diag0, diag1 connection.InputPin) (*TMC5160Full, error) {
	m, err := NewTMC5160Minimal(conn, rSenseOhm, enPin)
	if err != nil {
		return nil, err
	}
	return &TMC5160Full{TMC5160Minimal: m, diag0: diag0, diag1: diag1}, nil
}

// NewTMC5160FullUART creates the Full driver on the single-wire UART (the
// DIAG pins carry the UART and are unavailable).
func NewTMC5160FullUART(conn connection.Connection, rSenseOhm float64, address uint8, enPin connection.OutputPin, echo, multiNode bool) (*TMC5160Full, error) {
	m, err := NewTMC5160MinimalUART(conn, rSenseOhm, address, enPin, echo, multiNode)
	if err != nil {
		return nil, err
	}
	return &TMC5160Full{TMC5160Minimal: m}, nil
}

// ReadRegister reads a raw 32-bit register (readable registers only).
func (d *TMC5160Full) ReadRegister(reg uint8) (uint32, error) {
	if !tmc5160Readable(reg) {
		return 0, ErrTMC5160InvalidArgument
	}
	return d.readReg(reg)
}

// WriteRegister writes a raw 32-bit register; verify adds the SPI mirror / UART IFCNT check.
func (d *TMC5160Full) WriteRegister(reg uint8, value uint32, verify bool) error {
	if !tmc5160Writable(reg) {
		return ErrTMC5160InvalidArgument
	}
	if verify {
		return d.writeVerified(reg, value)
	}
	return d.writeReg(reg, value)
}

// SetHoldCurrent sets an independent hold current in A RMS (not above the run current).
func (d *TMC5160Full) SetHoldCurrent(rmsA float32) error {
	gs := float64(d.shadow[tmc5160RegGlobalScale])
	if gs == 0 {
		gs = 256
	}
	full := gs / 256 * d.iMax()
	ihold := int(math.Round(float64(rmsA)/full*32)) - 1
	if rmsA <= 0 || ihold > int(d.irun) {
		return ErrTMC5160InvalidArgument
	}
	if ihold < 0 {
		ihold = 0
	}
	d.ihold = uint8(ihold)
	return d.writeIholdIrun()
}

// SetGlobalScaler writes GLOBALSCALER: 0 or 32-256 (256 is written as 0).
func (d *TMC5160Full) SetGlobalScaler(value uint16) error {
	if !(value == 0 || (value >= 32 && value <= 256)) {
		return ErrTMC5160InvalidArgument
	}
	if value == 256 {
		value = 0
	}
	return d.writeReg(tmc5160RegGlobalScale, uint32(value))
}

// SetCurrentScale writes the raw IHOLD_IRUN fields: irun 0-31, ihold 0-31, iholdDelay 0-15.
func (d *TMC5160Full) SetCurrentScale(irun, ihold, iholdDelay uint8) error {
	if irun > 31 || ihold > 31 || iholdDelay > 15 {
		return ErrTMC5160InvalidArgument
	}
	d.irun, d.ihold, d.iholdDelay = irun, ihold, iholdDelay
	return d.writeIholdIrun()
}

// SetPowerDownDelay writes TPOWERDOWN (values below 2 break StealthChop auto-tuning).
func (d *TMC5160Full) SetPowerDownDelay(tpowerdown uint8) error {
	return d.writeReg(tmc5160RegTPowerDown, uint32(tpowerdown))
}

// ConfigureRamp programs the full SixPoint ramp in physical units (full steps/s and full steps/s^2).
func (d *TMC5160Full) ConfigureRamp(vstart, a1, v1, amax, vmax, dmax, d1, vstop float32) error {
	raw := []int64{d.vReg(vstart), d.aReg(a1), d.vReg(v1), d.aReg(amax), d.vReg(vmax), d.aReg(dmax), d.aReg(d1), d.vReg(vstop)}
	for _, v := range raw {
		if v < 0 || v > math.MaxUint32 {
			return ErrTMC5160InvalidArgument
		}
	}
	if err := d.SetRampRaw(uint32(raw[0]), uint32(raw[1]), uint32(raw[2]), uint32(raw[3]), uint32(raw[4]),
		uint32(raw[5]), uint32(raw[6]), uint32(raw[7])); err != nil {
		return err
	}
	d.speed, d.accel = vmax, amax
	return nil
}

// SetRampRaw programs the full SixPoint ramp in raw register units.
func (d *TMC5160Full) SetRampRaw(vstart, a1, v1, amax, vmax, dmax, d1, vstop uint32) error {
	if vstart >= 1<<18 || a1 > 0xFFFF || v1 >= 1<<20 || amax > 0xFFFF || vmax > (1<<23)-512 || dmax > 0xFFFF ||
		d1 > 0xFFFF || vstop >= 1<<18 || d1 < 1 || vstop < 1 || vstop < vstart {
		return ErrTMC5160InvalidArgument
	}
	pairs := []struct {
		r uint8
		v uint32
	}{{tmc5160RegVStart, vstart}, {tmc5160RegA1, a1}, {tmc5160RegV1, v1}, {tmc5160RegAMax, amax},
		{tmc5160RegVMax, vmax}, {tmc5160RegDMax, dmax}, {tmc5160RegD1, d1}, {tmc5160RegVStop, vstop}}
	for _, p := range pairs {
		if err := d.writeReg(p.r, p.v); err != nil {
			return err
		}
	}
	d.speedReg = vmax
	return nil
}

// SetZeroWait writes TZEROWAIT in seconds (0 to about 2.8 s at 12 MHz).
func (d *TMC5160Full) SetZeroWait(seconds float32) error {
	v, err := tmc5160Check(int64(math.Round(float64(seconds)*d.fclk/512)), 0xFFFF)
	if err != nil {
		return err
	}
	return d.writeReg(tmc5160RegTZeroWait, v)
}

// SetRampMode writes RAMPMODE 0-3 (3 = hold).
func (d *TMC5160Full) SetRampMode(mode uint8) error {
	if mode > 3 {
		return ErrTMC5160InvalidArgument
	}
	if err := d.writeReg(tmc5160RegRampMode, uint32(mode)); err != nil {
		return err
	}
	d.rampmode = mode
	return nil
}

// Hold keeps the present velocity until a stop event (RAMPMODE 3).
func (d *TMC5160Full) Hold() error { return d.SetRampMode(3) }

// SetTargetRaw writes XTARGET in microsteps (starts positioning in mode 0).
func (d *TMC5160Full) SetTargetRaw(xtarget int32) error {
	return d.writeReg(tmc5160RegXTarget, uint32(xtarget))
}

// ReadRampStatus returns the decoded RAMP_STAT (one read).
func (d *TMC5160Full) ReadRampStatus() (TMC5160RampStatus, error) {
	s, err := d.readReg(tmc5160RegRampStat)
	if err != nil {
		return TMC5160RampStatus{}, err
	}
	b := func(i uint) bool { return s&(1<<i) != 0 }
	return TMC5160RampStatus{StatusStopL: b(0), StatusStopR: b(1), StatusLatchL: b(2), StatusLatchR: b(3),
		EventStopL: b(4), EventStopR: b(5), EventStopSG: b(6), EventPosReached: b(7), VelocityReached: b(8),
		PositionReached: b(9), VZero: b(10), TZeroWaitActive: b(11), SecondMove: b(12), StatusSG: b(13)}, nil
}

// ClearRampEvents writes 1 to the clear-on-write bits of RAMP_STAT.
func (d *TMC5160Full) ClearRampEvents() error {
	return d.writeReg(tmc5160RegRampStat, 1<<2|1<<3|1<<6|1<<7|1<<12)
}

// SetPositionCompare writes X_COMPARE in full steps (DIAG1 goes high while XACTUAL equals it).
func (d *TMC5160Full) SetPositionCompare(position float32) error {
	return d.writeReg(tmc5160RegXCompare, uint32(int32(math.Round(float64(position)*float64(d.usc)))))
}

// ConfigureSwitches writes SW_MODE bits 0-4 and 11 (latch and sg_stop bits are kept).
func (d *TMC5160Full) ConfigureSwitches(stopLeft, stopRight, leftActiveLow, rightActiveLow, swap, softStop bool) error {
	if softStop && d.shadow[tmc5160RegSwMode]&tmc5160SwSgStop != 0 {
		return ErrTMC5160InvalidArgument
	}
	v := tmc5160Flag(stopLeft, 1) | tmc5160Flag(stopRight, 2) | tmc5160Flag(leftActiveLow, 4) |
		tmc5160Flag(rightActiveLow, 8) | tmc5160Flag(swap, 16) | tmc5160Flag(softStop, tmc5160SwSoftStop)
	return d.modify(tmc5160RegSwMode, 0x1F|tmc5160SwSoftStop, v)
}

// ConfigureLatch writes the SW_MODE latch bits 5-9.
func (d *TMC5160Full) ConfigureLatch(leftActive, leftInactive, rightActive, rightInactive, latchEncoder bool) error {
	v := tmc5160Flag(leftActive, 1<<5) | tmc5160Flag(leftInactive, 1<<6) | tmc5160Flag(rightActive, 1<<7) |
		tmc5160Flag(rightInactive, 1<<8) | tmc5160Flag(latchEncoder, 1<<9)
	return d.modify(tmc5160RegSwMode, 0x1F<<5, v)
}

// ReadLatchedPosition returns XLATCH in full steps.
func (d *TMC5160Full) ReadLatchedPosition() (float32, error) {
	v, err := d.readReg(tmc5160RegXLatch)
	return float32(int32(v)) / float32(d.usc), err
}

// ReadSwitches returns (status_stop_l, status_stop_r).
func (d *TMC5160Full) ReadSwitches() (bool, bool, error) {
	s, err := d.readReg(tmc5160RegRampStat)
	return s&1 != 0, s&2 != 0, err
}

// SetMicrosteps sets the microstep resolution 1, 2, 4, ..., 256 (ramp registers are not rewritten).
func (d *TMC5160Full) SetMicrosteps(n uint16) error {
	if n == 0 || n > 256 || n&(n-1) != 0 {
		return ErrTMC5160InvalidArgument
	}
	mres := uint32(0)
	for v := n; v < 256; v <<= 1 {
		mres++
	}
	if err := d.modify(tmc5160RegChopConf, 0xF<<24, mres<<24); err != nil {
		return err
	}
	d.usc = uint32(n)
	return nil
}

// SetInterpolation sets CHOPCONF.intpol (interpolation to 256 microsteps).
func (d *TMC5160Full) SetInterpolation(enabled bool) error {
	return d.modify(tmc5160RegChopConf, 1<<28, tmc5160Flag(enabled, 1<<28))
}

// SetChopperMode selects SpreadCycle only (true) or StealthChop (false); switch at standstill.
func (d *TMC5160Full) SetChopperMode(spreadcycle bool) error {
	return d.modify(tmc5160RegGConf, tmc5160GConfEnPwmMode, tmc5160Flag(!spreadcycle, tmc5160GConfEnPwmMode))
}

// SetStealthchopThreshold writes TPWMTHRS (20 bit); 0 disables the velocity switch.
func (d *TMC5160Full) SetStealthchopThreshold(v uint32) error {
	return d.threshold(tmc5160RegTPwmThrs, v)
}

// SetHighVelocityThreshold writes THIGH (20 bit).
func (d *TMC5160Full) SetHighVelocityThreshold(v uint32) error {
	return d.threshold(tmc5160RegTHigh, v)
}

// SetCoolstepThreshold writes TCOOLTHRS (20 bit); 0 disables.
func (d *TMC5160Full) SetCoolstepThreshold(v uint32) error {
	return d.threshold(tmc5160RegTCoolThrs, v)
}

func (d *TMC5160Full) threshold(reg uint8, v uint32) error {
	if v > 0xFFFFF {
		return ErrTMC5160InvalidArgument
	}
	return d.writeReg(reg, v)
}

// ConfigureChopper sets the SpreadCycle (chm = 0) fields: toff 0-15, tbl 0-3, hstrt 0-7, raw hend 0-15, tpfd 0-15.
func (d *TMC5160Full) ConfigureChopper(toff, tbl, hstrt, hend, tpfd uint8) error {
	if toff > 15 || tbl > 3 || hstrt > 7 || hend > 15 || tpfd > 15 {
		return ErrTMC5160InvalidArgument
	}
	if toff != 0 {
		d.toff = toff
	}
	mask := uint32(0xF | 3<<15 | 7<<4 | 0xF<<7 | 0xF<<20 | 1<<14 | 1<<11)
	v := uint32(toff) | uint32(tbl)<<15 | uint32(hstrt)<<4 | uint32(hend)<<7 | uint32(tpfd)<<20
	return d.modify(tmc5160RegChopConf, mask, v)
}

// ConfigureConstantOffTime sets the constant-off-time (chm = 1) fields: tfd 0-15, offset -3..12.
func (d *TMC5160Full) ConfigureConstantOffTime(toff, tbl, tfd uint8, offset int8, disfdcc bool, tpfd uint8) error {
	if toff > 15 || tbl > 3 || tfd > 15 || offset < -3 || offset > 12 || tpfd > 15 {
		return ErrTMC5160InvalidArgument
	}
	if toff != 0 {
		d.toff = toff
	}
	mask := uint32(0xF | 3<<15 | 7<<4 | 0xF<<7 | 0xF<<20 | 1<<14 | 1<<12 | 1<<11)
	v := uint32(toff) | uint32(tbl)<<15 | uint32(tfd&7)<<4 | uint32(int(offset)+3)<<7 | uint32(tpfd)<<20 |
		1<<14 | tmc5160Flag(disfdcc, 1<<12) | uint32((tfd>>3)&1)<<11
	return d.modify(tmc5160RegChopConf, mask, v)
}

// ConfigureHighVelocity sets CHOPCONF.vhighchm / vhighfs.
func (d *TMC5160Full) ConfigureHighVelocity(vhighchm, vhighfs bool) error {
	return d.modify(tmc5160RegChopConf, 1<<19|1<<18, tmc5160Flag(vhighchm, 1<<19)|tmc5160Flag(vhighfs, 1<<18))
}

// ConfigureStealthchop writes PWMCONF (freewheel kept): pwmFreq 0-3, pwmReg 1-15, pwmLim 0-15.
func (d *TMC5160Full) ConfigureStealthchop(autoscale, autograd bool, pwmOfs, pwmGrad, pwmFreq, pwmReg, pwmLim uint8) error {
	if pwmFreq > 3 || pwmReg < 1 || pwmReg > 15 || pwmLim > 15 {
		return ErrTMC5160InvalidArgument
	}
	d.first(tmc5160RegPwmConf, tmc5160PwmConfReset)
	mask := uint32(0xF<<28 | 0xF<<24 | 1<<19 | 1<<18 | 3<<16 | 0xFF<<8 | 0xFF)
	v := uint32(pwmLim)<<28 | uint32(pwmReg)<<24 | tmc5160Flag(autograd, 1<<19) | tmc5160Flag(autoscale, 1<<18) |
		uint32(pwmFreq)<<16 | uint32(pwmGrad)<<8 | uint32(pwmOfs)
	return d.modify(tmc5160RegPwmConf, mask, v)
}

// SetFreewheel writes PWMCONF.freewheel 0-3.
func (d *TMC5160Full) SetFreewheel(mode uint8) error {
	if mode > 3 {
		return ErrTMC5160InvalidArgument
	}
	d.first(tmc5160RegPwmConf, tmc5160PwmConfReset)
	return d.modify(tmc5160RegPwmConf, 3<<20, uint32(mode)<<20)
}

// SetShortProtection writes CHOPCONF.diss2g / diss2vs; true keeps the protection.
func (d *TMC5160Full) SetShortProtection(shortGND, shortSupply bool) error {
	return d.modify(tmc5160RegChopConf, 1<<30|1<<31, tmc5160Flag(!shortGND, 1<<30)|tmc5160Flag(!shortSupply, 1<<31))
}

// SetDoubleEdge writes CHOPCONF.dedge (external STEP/DIR only).
func (d *TMC5160Full) SetDoubleEdge(enabled bool) error {
	return d.modify(tmc5160RegChopConf, 1<<29, tmc5160Flag(enabled, 1<<29))
}

func (d *TMC5160Full) gconf(mask uint32, on bool) error {
	return d.modify(tmc5160RegGConf, mask, tmc5160Flag(on, mask))
}

// SetDirectionInverted writes GCONF.shaft.
func (d *TMC5160Full) SetDirectionInverted(inverted bool) error {
	return d.gconf(tmc5160GConfShaft, inverted)
}

// SetFastStandstill writes GCONF.faststandstill.
func (d *TMC5160Full) SetFastStandstill(enabled bool) error { return d.gconf(1<<1, enabled) }

// SetRecalibrate writes GCONF.recalibrate.
func (d *TMC5160Full) SetRecalibrate(enabled bool) error { return d.gconf(1, enabled) }

// SetSmallHysteresis writes GCONF.small_hysteresis.
func (d *TMC5160Full) SetSmallHysteresis(enabled bool) error {
	return d.gconf(tmc5160GConfSmallHyst, enabled)
}

// SetEmergencyStop writes GCONF.stop_enable.
func (d *TMC5160Full) SetEmergencyStop(enabled bool) error {
	return d.gconf(tmc5160GConfStopEnable, enabled)
}

// ConfigureDiag writes GCONF bits 7, 8, 12 and 13 (SD_MODE = 0 meanings).
func (d *TMC5160Full) ConfigureDiag(diag0StepOutput, diag1DirOutput, diag0PushPull, diag1PushPull bool) error {
	v := tmc5160Flag(diag0StepOutput, 1<<7) | tmc5160Flag(diag1DirOutput, 1<<8) |
		tmc5160Flag(diag0PushPull, 1<<12) | tmc5160Flag(diag1PushPull, 1<<13)
	return d.modify(tmc5160RegGConf, 1<<7|1<<8|1<<12|1<<13, v)
}

// SetStallThreshold writes COOLCONF.sgt -64..63 (lower = more sensitive); other fields are kept.
func (d *TMC5160Full) SetStallThreshold(sgt int8) error {
	if sgt < -64 || sgt > 63 {
		return ErrTMC5160InvalidArgument
	}
	return d.modify(tmc5160RegCoolConf, 0x7F<<16, (uint32(sgt)&0x7F)<<16)
}

// SetStallguardFilter writes COOLCONF.sfilt.
func (d *TMC5160Full) SetStallguardFilter(enabled bool) error {
	return d.modify(tmc5160RegCoolConf, 1<<24, tmc5160Flag(enabled, 1<<24))
}

// ConfigureCoolstep writes the CoolStep fields of COOLCONF (sgt / sfilt kept).
func (d *TMC5160Full) ConfigureCoolstep(semin, semax, seup, sedn, seimin uint8) error {
	if semin > 15 || semax > 15 || seup > 3 || sedn > 3 || seimin > 1 {
		return ErrTMC5160InvalidArgument
	}
	mask := uint32(0xF | 3<<5 | 0xF<<8 | 3<<13 | 1<<15)
	v := uint32(semin) | uint32(seup)<<5 | uint32(semax)<<8 | uint32(sedn)<<13 | uint32(seimin)<<15
	return d.modify(tmc5160RegCoolConf, mask, v)
}

// EnableStallStop writes SW_MODE.sg_stop: stop the motor on a StallGuard2 event
// (needs TCOOLTHRS; not combinable with soft stop).
func (d *TMC5160Full) EnableStallStop(enabled bool) error {
	if enabled && d.shadow[tmc5160RegSwMode]&tmc5160SwSoftStop != 0 {
		return ErrTMC5160InvalidArgument
	}
	return d.modify(tmc5160RegSwMode, tmc5160SwSgStop, tmc5160Flag(enabled, tmc5160SwSgStop))
}

// ReadStallguard returns SG_RESULT 0-1023.
func (d *TMC5160Full) ReadStallguard() (uint16, error) {
	v, err := d.readReg(tmc5160RegDrvStatus)
	return uint16(v & 0x3FF), err
}

// IsStalled returns DRV_STATUS.StallGuard.
func (d *TMC5160Full) IsStalled() (bool, error) {
	v, err := d.readReg(tmc5160RegDrvStatus)
	return (v>>24)&1 == 1, err
}

// ReadDiag returns the level of DIAG0 (n = 0) or DIAG1 (n = 1); ErrTMC5160NoPin if not wired.
func (d *TMC5160Full) ReadDiag(n uint8) (bool, error) {
	var p connection.InputPin
	switch n {
	case 0:
		p = d.diag0
	case 1:
		p = d.diag1
	default:
		return false, ErrTMC5160InvalidArgument
	}
	if p == nil {
		return false, ErrTMC5160NoPin
	}
	return p.IsHigh()
}

// SetDcstep writes VDCMIN (0 to 2^23-1), DCCTRL.DC_TIME (0-1023) and DC_SG (0-255).
func (d *TMC5160Full) SetDcstep(vdcmin uint32, dcTime uint16, dcSG uint8) error {
	if vdcmin > 0x7FFFFF || dcTime > 1023 {
		return ErrTMC5160InvalidArgument
	}
	if err := d.writeReg(tmc5160RegVDcMin, vdcmin); err != nil {
		return err
	}
	return d.writeReg(tmc5160RegDcCtrl, uint32(dcTime)|uint32(dcSG)<<16)
}

// SetDirectMode writes GCONF.direct_mode (the ramp generator is bypassed while
// set; leaving it rewrites XTARGET).
func (d *TMC5160Full) SetDirectMode(enabled bool) error {
	if err := d.gconf(tmc5160GConfDirectMode, enabled); err != nil {
		return err
	}
	if !enabled {
		return d.writeReg(tmc5160RegXTarget, d.shadow[tmc5160RegXTarget])
	}
	return nil
}

// SetCoilCurrents writes XDIRECT coil currents -255..255 each; requires direct mode.
func (d *TMC5160Full) SetCoilCurrents(coilA, coilB int16) error {
	if d.shadow[tmc5160RegGConf]&tmc5160GConfDirectMode == 0 {
		return ErrTMC5160DirectModeNeeded
	}
	if coilA < -255 || coilA > 255 || coilB < -255 || coilB > 255 {
		return ErrTMC5160InvalidArgument
	}
	return d.writeReg(tmc5160RegXTarget, uint32(coilA)&0x1FF|(uint32(coilB)&0x1FF)<<16)
}

// WriteMicrostepTable programs MSLUT[0..7], MSLUTSEL and MSLUTSTART; call at MSCNT = 0.
func (d *TMC5160Full) WriteMicrostepTable(mslut [8]uint32, mslutsel, mslutstart uint32) error {
	for i, w := range mslut {
		if err := d.writeReg(tmc5160RegMslut0+uint8(i), w); err != nil {
			return err
		}
	}
	if err := d.writeReg(tmc5160RegMslutSel, mslutsel); err != nil {
		return err
	}
	return d.writeReg(tmc5160RegMslutStart, mslutstart)
}

// ConfigureGateDriver writes DRV_CONF -- board-specific, wrong values can destroy the MOSFETs.
func (d *TMC5160Full) ConfigureGateDriver(bbmtime, bbmclks, otselect, drvstrength, filtIsense uint8) error {
	if bbmtime > 24 || bbmclks > 15 || otselect > 3 || drvstrength > 3 || filtIsense > 3 {
		return ErrTMC5160InvalidArgument
	}
	v := uint32(bbmtime) | uint32(bbmclks)<<8 | uint32(otselect)<<16 | uint32(drvstrength)<<18 | uint32(filtIsense)<<20
	return d.writeReg(tmc5160RegDrvConf, v)
}

// ConfigureShortDetection writes SHORT_CONF -- board-specific: s2vsLevel 4-15, s2gLevel 2-15, shortfilter 0-3, shortdelay 0|1.
func (d *TMC5160Full) ConfigureShortDetection(s2vsLevel, s2gLevel, shortfilter, shortdelay uint8) error {
	if s2vsLevel < 4 || s2vsLevel > 15 || s2gLevel < 2 || s2gLevel > 15 || shortfilter > 3 || shortdelay > 1 {
		return ErrTMC5160InvalidArgument
	}
	v := uint32(s2vsLevel) | uint32(s2gLevel)<<8 | uint32(shortfilter)<<16 | uint32(shortdelay)<<18
	return d.writeReg(tmc5160RegShortConf, v)
}

// ConfigureEncoder writes ENCMODE; flags are in register bit order: pol_a, pol_b, pol_n, ignore_ab, clr_cont,
// clr_once, pos_edge, neg_edge, clr_enc_x, latch_x_act, decimal.
func (d *TMC5160Full) ConfigureEncoder(flags [11]bool) error {
	var v uint32
	for i, f := range flags {
		v |= tmc5160Flag(f, 1<<uint(i))
	}
	return d.writeReg(tmc5160RegEncMode, v)
}

// SetEncoderConstant writes ENC_CONST: signed 16.16, or integer + 4-digit decimal fraction when ENCMODE.enc_sel_decimal is set.
func (d *TMC5160Full) SetEncoderConstant(value float64) error {
	if math.Abs(value) > 32767.9999 {
		return ErrTMC5160InvalidArgument
	}
	var raw uint32
	if d.shadow[tmc5160RegEncMode]&(1<<10) != 0 {
		a := math.Abs(value)
		whole := math.Floor(a)
		raw = uint32(whole)<<16 | uint32(math.Round((a-whole)*10000))
		if value < 0 {
			raw = -raw
		}
	} else {
		raw = uint32(int32(math.Round(value * 65536)))
	}
	return d.writeReg(tmc5160RegEncConst, raw)
}

// SetEncoderPosition writes X_ENC.
func (d *TMC5160Full) SetEncoderPosition(counts int32) error {
	return d.writeReg(tmc5160RegXEnc, uint32(counts))
}

// ReadEncoderPosition returns X_ENC.
func (d *TMC5160Full) ReadEncoderPosition() (int32, error) {
	v, err := d.readReg(tmc5160RegXEnc)
	return int32(v), err
}

// ReadEncoderLatch returns ENC_LATCH.
func (d *TMC5160Full) ReadEncoderLatch() (int32, error) {
	v, err := d.readReg(tmc5160RegEncLatch)
	return int32(v), err
}

// SetEncoderDeviation writes ENC_DEVIATION 0 to 2^20-1 (0 = off).
func (d *TMC5160Full) SetEncoderDeviation(steps uint32) error {
	return d.threshold(tmc5160RegEncDev, steps)
}

// ReadEncoderStatus returns (n_event, deviation_warn).
func (d *TMC5160Full) ReadEncoderStatus() (bool, bool, error) {
	s, err := d.readReg(tmc5160RegEncStatus)
	return s&1 != 0, s&2 != 0, err
}

// ClearEncoderStatus writes 1 to the ENC_STATUS event bits.
func (d *TMC5160Full) ClearEncoderStatus() error { return d.writeReg(tmc5160RegEncStatus, 3) }

// SetSlaveAddress writes SLAVECONF.SLAVEADDR 0-253 (UART only); later datagrams use the new address.
func (d *TMC5160Full) SetSlaveAddress(addr uint8) error {
	if !d.uart {
		return ErrTMC5160UartOnly
	}
	if addr > 253 {
		return ErrTMC5160InvalidArgument
	}
	if err := d.writeReg(tmc5160RegSlaveConf, uint32(addr)|uint32(d.sendDelay)<<8); err != nil {
		return err
	}
	d.address = addr
	return nil
}

// SetSendDelay writes SLAVECONF.SENDDELAY 0-15 (UART only; at least 2 on multi-node buses).
func (d *TMC5160Full) SetSendDelay(sd uint8) error {
	if !d.uart {
		return ErrTMC5160UartOnly
	}
	if sd > 15 {
		return ErrTMC5160InvalidArgument
	}
	d.sendDelay = sd
	return d.writeReg(tmc5160RegSlaveConf, d.shadow[tmc5160RegSlaveConf]&0xFF|uint32(sd)<<8)
}

// SetNAOPolarity writes OUTPUT bit 0, the NAO polarity (UART only).
func (d *TMC5160Full) SetNAOPolarity(level bool) error {
	if !d.uart {
		return ErrTMC5160UartOnly
	}
	return d.writeReg(tmc5160RegOutput, tmc5160Flag(level, 1))
}

// ReadIfcnt returns the interface transmission counter (UART only).
func (d *TMC5160Full) ReadIfcnt() (uint8, error) {
	if !d.uart {
		return 0, ErrTMC5160UartOnly
	}
	v, err := d.readReg(tmc5160RegIfcnt)
	return uint8(v), err
}

// MicrostepPosition returns MSCNT 0-1023.
func (d *TMC5160Full) MicrostepPosition() (uint16, error) {
	v, err := d.readReg(tmc5160RegMsCnt)
	return uint16(v & 0x3FF), err
}

// ReadCurrentScale returns DRV_STATUS.CS_ACTUAL 0-31.
func (d *TMC5160Full) ReadCurrentScale() (uint8, error) {
	v, err := d.readReg(tmc5160RegDrvStatus)
	return uint8((v >> 16) & 0x1F), err
}

// ReadCoilCurrents returns the signed (CUR_A, CUR_B) from MSCURACT.
func (d *TMC5160Full) ReadCoilCurrents() (int16, int16, error) {
	v, err := d.readReg(tmc5160RegMsCurAct)
	return int16(tmc5160SignExtend(v>>16, 9)), int16(tmc5160SignExtend(v, 9)), err
}

// ReadTStep returns TSTEP (20 bit).
func (d *TMC5160Full) ReadTStep() (uint32, error) {
	v, err := d.readReg(tmc5160RegTStep)
	return v & 0xFFFFF, err
}

// ReadPwmScale returns (PWM_SCALE_SUM, PWM_SCALE_AUTO).
func (d *TMC5160Full) ReadPwmScale() (uint8, int16, error) {
	v, err := d.readReg(tmc5160RegPwmScale)
	return uint8(v), int16(tmc5160SignExtend(v>>16, 9)), err
}

// ReadPwmAuto returns (PWM_OFS_AUTO, PWM_GRAD_AUTO).
func (d *TMC5160Full) ReadPwmAuto() (uint8, uint8, error) {
	v, err := d.readReg(tmc5160RegPwmAuto)
	return uint8(v), uint8(v >> 16), err
}

// ReadOffset returns the signed (phase A, phase B) offsets from OFFSET_READ.
func (d *TMC5160Full) ReadOffset() (int8, int8, error) {
	v, err := d.readReg(tmc5160RegOffsetRead)
	return int8(v >> 8), int8(v), err
}

// ReadStatus returns the decoded DRV_STATUS (one read).
func (d *TMC5160Full) ReadStatus() (TMC5160Status, error) {
	s, err := d.readReg(tmc5160RegDrvStatus)
	if err != nil {
		return TMC5160Status{}, err
	}
	return TMC5160Status{
		Standstill: s>>31 == 1, StealthChop: s&(1<<14) != 0, FsActive: s&(1<<15) != 0,
		CSActual: uint8((s >> 16) & 0x1F), SGResult: uint16(s & 0x3FF), StallGuard: s&(1<<24) != 0,
		OTPW: s&(1<<26) != 0, OT: s&(1<<25) != 0, ShortGND: s&(3<<27) != 0, ShortSupply: s&(3<<12) != 0,
		OpenLoad: s&(3<<29) != 0,
	}, nil
}

// ReadGlobalStatus returns the decoded GSTAT (does not clear it).
func (d *TMC5160Full) ReadGlobalStatus() (TMC5160GlobalStatus, error) {
	g, err := d.readReg(tmc5160RegGStat)
	return TMC5160GlobalStatus{Reset: g&1 != 0, DrvErr: g&2 != 0, UVCP: g&4 != 0}, err
}

// ClearGlobalStatus writes 0x07 to GSTAT.
func (d *TMC5160Full) ClearGlobalStatus() error { return d.writeReg(tmc5160RegGStat, 7) }

// ReadIoin returns the decoded IOIN register.
func (d *TMC5160Full) ReadIoin() (TMC5160Ioin, error) {
	r, err := d.readReg(tmc5160RegIoin)
	return TMC5160Ioin{Raw: r, Refl: r&1 != 0, Refr: r&2 != 0, Encb: r&4 != 0, Enca: r&8 != 0, DrvEnn: r&16 != 0,
		Encn: r&32 != 0, SdMode: r&64 != 0, SwcompIn: r&128 != 0, Version: uint8(r >> 24)}, err
}
