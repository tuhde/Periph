// MMA8451Q driver — NXP MMA8451Q 3-axis accelerometer.
//
// The chip is register-addressed over I2C (plain register-pointer protocol,
// no auto-increment bit). Every configuration register except CTRL_REG1's
// ACTIVE bit and CTRL_REG2's RST bit may only be written while the device is
// in STANDBY; the Full driver wraps those writes internally.
package accelerometer

import (
	"fmt"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// MMA8451Q register addresses.
const (
	mma8451qRegStatus      uint8 = 0x00 // STATUS (F_MODE=0) / F_STATUS (F_MODE>0)
	mma8451qRegOutXMSB     uint8 = 0x01
	mma8451qRegOutXLSB     uint8 = 0x02
	mma8451qRegOutYMSB     uint8 = 0x03
	mma8451qRegOutYLSB     uint8 = 0x04
	mma8451qRegOutZMSB     uint8 = 0x05
	mma8451qRegOutZLSB     uint8 = 0x06
	mma8451qRegFSetup      uint8 = 0x09
	mma8451qRegTrigCfg     uint8 = 0x0A
	mma8451qRegSysMod      uint8 = 0x0B
	mma8451qRegIntSource   uint8 = 0x0C
	mma8451qRegWhoAmI      uint8 = 0x0D
	mma8451qRegXYZDataCfg  uint8 = 0x0E
	mma8451qRegHPFilterCut uint8 = 0x0F
	mma8451qRegPLStatus    uint8 = 0x10
	mma8451qRegPLCfg       uint8 = 0x11
	mma8451qRegPLCount     uint8 = 0x12
	mma8451qRegPLBFZComp   uint8 = 0x13
	mma8451qRegPLThsReg    uint8 = 0x14
	mma8451qRegFFMTCfg     uint8 = 0x15
	mma8451qRegFFMTSrc     uint8 = 0x16
	mma8451qRegFFMTThs     uint8 = 0x17
	mma8451qRegFFMTCount   uint8 = 0x18
	mma8451qRegTransientC  uint8 = 0x1D
	mma8451qRegTransientS  uint8 = 0x1E
	mma8451qRegTransientT  uint8 = 0x1F
	mma8451qRegTransientC2 uint8 = 0x20
	mma8451qRegPulseCfg    uint8 = 0x21
	mma8451qRegPulseSrc    uint8 = 0x22
	mma8451qRegPulseThsX   uint8 = 0x23
	mma8451qRegPulseThsY   uint8 = 0x24
	mma8451qRegPulseThsZ   uint8 = 0x25
	mma8451qRegPulseTMLT   uint8 = 0x26
	mma8451qRegPulseLTCY   uint8 = 0x27
	mma8451qRegPulseWIND   uint8 = 0x28
	mma8451qRegASLPCount   uint8 = 0x29
	mma8451qRegCtrlReg1    uint8 = 0x2A
	mma8451qRegCtrlReg2    uint8 = 0x2B
	mma8451qRegCtrlReg3    uint8 = 0x2C
	mma8451qRegCtrlReg4    uint8 = 0x2D
	mma8451qRegCtrlReg5    uint8 = 0x2E
	mma8451qRegOffX        uint8 = 0x2F
	mma8451qRegOffY        uint8 = 0x30
	mma8451qRegOffZ        uint8 = 0x31
)

// mma8451qWhoAmIValue is the expected content of the WHO_AM_I register.
const mma8451qWhoAmIValue uint8 = 0x1A

// CTRL_REG1 bit masks.
const (
	mma8451qCtrl1AslpRate uint8 = 0xC0 // bits 7:6
	mma8451qCtrl1DR       uint8 = 0x38 // bits 5:3
	mma8451qCtrl1LNoise   uint8 = 0x04 // bit 2
	mma8451qCtrl1FRead    uint8 = 0x02 // bit 1
	mma8451qCtrl1Active   uint8 = 0x01 // bit 0
)

// CTRL_REG2 bit masks.
const (
	mma8451qCtrl2ST    uint8 = 0x80 // bit 7
	mma8451qCtrl2RST   uint8 = 0x40 // bit 6
	mma8451qCtrl2SMODS uint8 = 0x18 // bits 4:3
	mma8451qCtrl2SLPE  uint8 = 0x04 // bit 2
	mma8451qCtrl2MODS  uint8 = 0x03 // bits 1:0
)

// CTRL_REG3 bit masks.
const (
	mma8451qCtrl3FifoGate   uint8 = 0x80 // bit 7
	mma8451qCtrl3WakeTrans  uint8 = 0x40 // bit 6
	mma8451qCtrl3WakeLndPrt uint8 = 0x20 // bit 5
	mma8451qCtrl3WakePulse  uint8 = 0x10 // bit 4
	mma8451qCtrl3WakeFFMT   uint8 = 0x08 // bit 3
	mma8451qCtrl3IPol       uint8 = 0x02 // bit 1
	mma8451qCtrl3PPOD       uint8 = 0x01 // bit 0
)

// MMA8451QMinimal is the MMA8451Q 3-axis accelerometer — minimal interface.
//
// Reads X, Y, Z acceleration in *g* with sensible defaults; no configuration
// is required beyond the connection.
//
// Default configuration (baked in at construction):
// - Range ±2 *g* (4096 counts/g)
// - Output data rate 100 Hz
// - 14-bit output (F_READ = 0)
// - Normal oversampling in WAKE and SLEEP
// - Low-noise mode off, high-pass output off
// - FIFO disabled, all detectors and interrupt routing off
// - Interrupts active-low, push-pull
type MMA8451QMinimal struct {
	conn connection.RegisterConnection

	rangeG      uint8 // 2, 4 or 8
	odrHz       float32
	odrIndex    int
	bits        uint8 // 14 or 8
	oversample  uint8 // MODS
	sleepMode   uint8 // SMODS
	lowNoise    bool
	fifoMode    uint8
	fifoEnabled bool
}

// NewMMA8451QMinimal creates an MMA8451QMinimal and runs the chip's
// initialization sequence: verify WHO_AM_I, software-reset the device,
// configure ±2 *g* at 100 Hz in 14-bit normal-oversampling mode and switch it
// to ACTIVE.
//
// Returns an error if WHO_AM_I does not read 0x1A.
func NewMMA8451QMinimal(conn connection.RegisterConnection) (*MMA8451QMinimal, error) {
	c := &MMA8451QMinimal{
		conn:       conn,
		rangeG:     2,
		odrHz:      100,
		odrIndex:   3,
		bits:       14,
		oversample: 0,
		sleepMode:  0,
	}
	if err := c.init(); err != nil {
		return nil, err
	}
	return c, nil
}

// init runs the chip's power-up / reset sequence.
func (c *MMA8451QMinimal) init() error {
	// 1. The I2C interface needs ~350 us after power-up; wait a comfortable
	//    1 ms before the first transaction.
	time.Sleep(time.Millisecond)

	// 2. Verify the identity register.
	id, err := c.readReg8(mma8451qRegWhoAmI)
	if err != nil {
		return err
	}
	if id != mma8451qWhoAmIValue {
		return fmt.Errorf("MMA8451Q WHO_AM_I: expected 0x%02X, got 0x%02X",
			mma8451qWhoAmIValue, id)
	}

	// 3. Software reset. The reset also resets the I2C interface, so the
	//    write may NACK; retry until CTRL_REG2 reads back 0 (10 ms budget).
	if err := c.reset(); err != nil {
		return err
	}

	// 4. ±2 g, no high-pass output.
	if err := c.writeReg(mma8451qRegXYZDataCfg, 0x00); err != nil {
		return err
	}
	// 5. Normal oversampling in WAKE and SLEEP, Auto-SLEEP off.
	if err := c.writeReg(mma8451qRegCtrlReg2, 0x00); err != nil {
		return err
	}
	// 6. DR = 011 (100 Hz), LNOISE = 0, F_READ = 0, ACTIVE = 1.
	if err := c.writeReg(mma8451qRegCtrlReg1, 0x19); err != nil {
		return err
	}
	// 7. Wait for the STANDBY -> ACTIVE turn-on time (2/ODR + 2 ms).
	c.waitTurnOn()
	return nil
}

// reset performs the CTRL_REG2.RST software reset and waits for the device to
// come back with all registers at their reset values.
func (c *MMA8451QMinimal) reset() error {
	if err := c.writeReg(mma8451qRegCtrlReg2, mma8451qCtrl2RST); err != nil {
		// The reset may NACK the data phase — that is expected.
		_ = err
	}
	time.Sleep(time.Millisecond)
	deadline := time.Now().Add(10 * time.Millisecond)
	for {
		v, err := c.readReg8(mma8451qRegCtrlReg2)
		if err == nil && v == 0x00 {
			return nil
		}
		if time.Now().After(deadline) {
			if err != nil {
				return err
			}
			return fmt.Errorf("MMA8451Q reset: CTRL_REG2 = 0x%02X, want 0x00", v)
		}
		time.Sleep(time.Millisecond)
	}
}

func (c *MMA8451QMinimal) writeReg(reg, val uint8) error {
	return c.conn.WriteReg(uint32(reg), []byte{val})
}

func (c *MMA8451QMinimal) readReg8(reg uint8) (uint8, error) {
	b, err := c.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (c *MMA8451QMinimal) readBurst(reg uint8, n int) ([]byte, error) {
	return c.conn.ReadReg(uint32(reg), n)
}

// countsPerG returns the number of counts per *g* for the current range and
// resolution.
func (c *MMA8451QMinimal) countsPerG() float32 {
	var base float32
	switch c.rangeG {
	case 4:
		base = 2048
	case 8:
		base = 1024
	default:
		base = 4096
	}
	if c.bits == 8 {
		return base / 64
	}
	return base
}

// waitTurnOn sleeps for the documented STANDBY -> ACTIVE turn-on time
// (2/ODR + 2 ms).
func (c *MMA8451QMinimal) waitTurnOn() {
	odr := c.odrHz
	if odr <= 0 {
		odr = 100
	}
	ms := float64(2000.0/odr + 2.0)
	time.Sleep(time.Duration(ms * float64(time.Millisecond)))
}

// Read returns 3-axis linear acceleration as (x, y, z) in *g*.
//
// Reads all six data bytes (0x01..0x06) in one burst so the X, Y, Z samples
// are guaranteed to come from a single measurement. In 8-bit fast-read mode
// only the three MSB registers are read.
func (c *MMA8451QMinimal) Read() (float32, float32, float32, error) {
	x, y, z, err := c.readCounts()
	if err != nil {
		return 0, 0, 0, err
	}
	scale := c.countsPerG()
	return float32(x) / scale, float32(y) / scale, float32(z) / scale, nil
}

// readCounts reads one sample and returns the signed acceleration counts of
// each axis at the current resolution.
func (c *MMA8451QMinimal) readCounts() (int32, int32, int32, error) {
	if c.bits == 8 {
		b, err := c.readBurst(mma8451qRegOutXMSB, 3)
		if err != nil {
			return 0, 0, 0, err
		}
		return int32(int8(b[0])), int32(int8(b[1])), int32(int8(b[2])), nil
	}
	b, err := c.readBurst(mma8451qRegOutXMSB, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	rx := int32(connection.ToSigned(uint32(b[0])<<8|uint32(b[1]), 16)) >> 2
	ry := int32(connection.ToSigned(uint32(b[2])<<8|uint32(b[3]), 16)) >> 2
	rz := int32(connection.ToSigned(uint32(b[4])<<8|uint32(b[5]), 16)) >> 2
	return rx, ry, rz, nil
}
