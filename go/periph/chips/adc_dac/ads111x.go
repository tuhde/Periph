package adcdac

import (
	"errors"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// Shared implementation for the ADS1113 / ADS1114 / ADS1115 16-bit ΔΣ ADCs.
//
// The three chips share one register map and one Config-register layout and
// differ only in which Config fields the silicon honours (ADS1113: fixed
// ±2.048 V, no comparator, no mux; ADS1114: PGA + comparator; ADS1115: PGA +
// comparator + 4-input mux). ads111xBase holds the whole-word Config builder,
// the single-shot sequence and every feature as unexported methods; the
// per-chip types publish exactly the subset their silicon supports.
//
// Registers are 16-bit big-endian behind a write-only Address Pointer. Every
// Config write is a whole word — never read-modify-write.

const (
	ads111xRegConversion uint8 = 0x00
	ads111xRegConfig     uint8 = 0x01
	ads111xRegLoThresh   uint8 = 0x02
	ads111xRegHiThresh   uint8 = 0x03

	ads111xConfigReset uint16 = 0x8583
	ads111xLoReset     uint16 = 0x8000
	ads111xHiReset     uint16 = 0x7FFF
	ads111xCfgOS       uint16 = 0x8000
	ads111xCfgModeShot uint16 = 0x0100
	ads111xQueDisabled uint8  = 3
)

// ADS111xSourceConversionReady is the interrupt source for a finished
// conversion (ALERT/RDY in conversion-ready mode).
const ADS111xSourceConversionReady uint8 = 0x01

// ADS111xSourceComparator is the interrupt source for a result beyond the
// comparator thresholds.
const ADS111xSourceComparator uint8 = 0x02

// Errors returned by the ADS111x drivers.
var (
	// ErrADS111xInvalidArgument reports an argument outside the supported
	// range (data rate, gain, mux, comparator setting) or thresholds with
	// high <= low.
	ErrADS111xInvalidArgument = errors.New("ADS111x: invalid argument")
	// ErrADS111xTimeout reports that OS never returned to 1 after a
	// single-shot conversion.
	ErrADS111xTimeout = errors.New("ADS111x: conversion timeout")
)

// ads111xFSR is the full-scale range in volts, indexed by PGA[2:0].
var ads111xFSR = [6]float32{6.144, 4.096, 2.048, 1.024, 0.512, 0.256}

// ads111xRates is the sample rate in SPS, indexed by DR[2:0].
var ads111xRates = [8]uint16{8, 16, 32, 64, 128, 250, 475, 860}

type ads111xBase struct {
	conn connection.RegisterConnection

	mux, pga, dr                        uint8
	compMode, compPol, compLat, compQue uint8
	queue                               uint8
	continuous                          bool
	loRaw, hiRaw                        int16
	source                              uint8

	mu          sync.Mutex
	callback    func(uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
	lastStatus  uint8
}

func newADS111xBase(conn connection.RegisterConnection) *ads111xBase {
	b := &ads111xBase{conn: conn}
	b.resetState()
	return b
}

func (b *ads111xBase) resetState() {
	b.mux, b.pga, b.dr = 0, 2, 4
	b.compMode, b.compPol, b.compLat, b.compQue = 0, 0, 0, ads111xQueDisabled
	b.queue = 0
	b.continuous = false
	b.loRaw, b.hiRaw = -32768, 32767
	b.source = 0
}

func (b *ads111xBase) readReg(reg uint8) (uint16, error) {
	raw, err := b.conn.ReadReg(uint32(reg), 2)
	if err != nil {
		return 0, err
	}
	return uint16(raw[0])<<8 | uint16(raw[1]), nil
}

func (b *ads111xBase) writeReg(reg uint8, value uint16) error {
	return b.conn.WriteReg(uint32(reg), []byte{byte(value >> 8), byte(value)})
}

func (b *ads111xBase) configWord(osBit bool, mux uint8) uint16 {
	w := uint16(mux)<<12 | uint16(b.pga)<<9 | uint16(b.dr)<<5 |
		uint16(b.compMode)<<4 | uint16(b.compPol)<<3 | uint16(b.compLat)<<2 | uint16(b.compQue)
	if osBit {
		w |= ads111xCfgOS
	}
	if !b.continuous {
		w |= ads111xCfgModeShot
	}
	return w
}

func (b *ads111xBase) writeConfig(osBit bool) error {
	return b.writeReg(ads111xRegConfig, b.configWord(osBit, b.mux))
}

// convert runs a single-shot conversion on mux and returns the signed code.
func (b *ads111xBase) convert(mux uint8) (int16, error) {
	b.continuous = false
	if err := b.writeReg(ads111xRegConfig, b.configWord(true, mux)); err != nil {
		return 0, err
	}
	rate := time.Duration(ads111xRates[b.dr])
	time.Sleep(time.Second/rate + time.Millisecond)
	for i := 0; i < 200; i++ {
		cfg, err := b.readReg(ads111xRegConfig)
		if err != nil {
			return 0, err
		}
		if cfg&ads111xCfgOS != 0 {
			raw, err := b.readReg(ads111xRegConversion)
			return int16(raw), err
		}
		time.Sleep(time.Millisecond)
	}
	return 0, ErrADS111xTimeout
}

func (b *ads111xBase) setDataRate(dr uint8) error {
	if dr > 7 {
		return ErrADS111xInvalidArgument
	}
	b.dr = dr
	if b.continuous {
		return b.writeConfig(false)
	}
	return nil
}

func (b *ads111xBase) setGain(pga uint8) error {
	if pga > 5 {
		return ErrADS111xInvalidArgument
	}
	b.pga = pga
	if b.continuous {
		return b.writeConfig(false)
	}
	return nil
}

func (b *ads111xBase) startContinuous(mux uint8) error {
	if mux > 7 {
		return ErrADS111xInvalidArgument
	}
	b.mux = mux
	b.continuous = true
	return b.writeConfig(false)
}

func (b *ads111xBase) readContinuousRaw() (int16, error) {
	raw, err := b.readReg(ads111xRegConversion)
	return int16(raw), err
}

func (b *ads111xBase) stopContinuous() error {
	b.continuous = false
	return b.writeConfig(false)
}

func (b *ads111xBase) isConverting() (bool, error) {
	cfg, err := b.readReg(ads111xRegConfig)
	return cfg&ads111xCfgOS == 0, err
}

func (b *ads111xBase) fullScale() float32 { return ads111xFSR[b.pga] }

func (b *ads111xBase) lsb() float32 { return ads111xFSR[b.pga] / 32768 }

func (b *ads111xBase) reset(withThresholds bool) error {
	if err := b.writeReg(ads111xRegConfig, ads111xConfigReset); err != nil {
		return err
	}
	if withThresholds {
		if err := b.writeReg(ads111xRegLoThresh, ads111xLoReset); err != nil {
			return err
		}
		if err := b.writeReg(ads111xRegHiThresh, ads111xHiReset); err != nil {
			return err
		}
	}
	b.resetState()
	return nil
}

func (b *ads111xBase) configureComparator(mode, polarity uint8, latch bool, queue uint8) error {
	if mode > 1 || polarity > 1 || queue > 2 || b.hiRaw <= b.loRaw {
		return ErrADS111xInvalidArgument
	}
	b.compMode, b.compPol, b.queue, b.compQue = mode, polarity, queue, queue
	b.compLat = 0
	if latch {
		b.compLat = 1
	}
	b.source = ADS111xSourceComparator
	return b.writeConfig(false)
}

func (b *ads111xBase) disableComparator() error {
	b.compQue = ads111xQueDisabled
	b.source = 0
	return b.writeConfig(false)
}

func (b *ads111xBase) setLowThresholdRaw(raw int16) error {
	b.loRaw = raw
	return b.writeReg(ads111xRegLoThresh, uint16(raw))
}

func (b *ads111xBase) setHighThresholdRaw(raw int16) error {
	b.hiRaw = raw
	return b.writeReg(ads111xRegHiThresh, uint16(raw))
}

func (b *ads111xBase) voltsToCode(volts float32) int16 {
	steps := volts / b.lsb()
	var code int32
	if steps >= 0 {
		code = int32(steps + 0.5)
	} else {
		code = -int32(-steps + 0.5)
	}
	if code > 32767 {
		code = 32767
	}
	if code < -32768 {
		code = -32768
	}
	return int16(code)
}

func (b *ads111xBase) setThresholds(low, high float32) error {
	if high <= low {
		return ErrADS111xInvalidArgument
	}
	if err := b.setLowThresholdRaw(b.voltsToCode(low)); err != nil {
		return err
	}
	return b.setHighThresholdRaw(b.voltsToCode(high))
}

func (b *ads111xBase) getThresholdsRaw() (int16, int16, error) {
	lo, err := b.readReg(ads111xRegLoThresh)
	if err != nil {
		return 0, 0, err
	}
	hi, err := b.readReg(ads111xRegHiThresh)
	return int16(lo), int16(hi), err
}

func (b *ads111xBase) clearAlert() error {
	_, err := b.readReg(ads111xRegConversion)
	return err
}

func (b *ads111xBase) enableInterrupt(source uint8) error {
	switch source {
	case ADS111xSourceConversionReady:
		if err := b.writeReg(ads111xRegHiThresh, 0x8000); err != nil {
			return err
		}
		if err := b.writeReg(ads111xRegLoThresh, 0x0000); err != nil {
			return err
		}
		b.compQue = 0
		b.source = ADS111xSourceConversionReady
		return b.writeConfig(false)
	case ADS111xSourceComparator:
		if err := b.setLowThresholdRaw(b.loRaw); err != nil {
			return err
		}
		if err := b.setHighThresholdRaw(b.hiRaw); err != nil {
			return err
		}
		return b.configureComparator(b.compMode, b.compPol, b.compLat == 1, b.queue)
	}
	return ErrADS111xInvalidArgument
}

// pollInterrupt returns the enabled source if ALERT/RDY is asserted, else 0.
func (b *ads111xBase) pollInterrupt() (uint8, error) {
	if b.source == 0 {
		return 0, nil
	}
	var asserted bool
	if pin := b.conn.IntPin(); pin != nil {
		high, err := pin.IsHigh()
		if err != nil {
			return 0, err
		}
		asserted = high == (b.compPol == 1)
	} else if b.source == ADS111xSourceConversionReady {
		cfg, err := b.readReg(ads111xRegConfig)
		if err != nil {
			return 0, err
		}
		asserted = cfg&ads111xCfgOS != 0
	}
	if !asserted {
		return 0, nil
	}
	if b.source == ADS111xSourceComparator && b.compLat == 1 {
		if err := b.clearAlert(); err != nil {
			return 0, err
		}
	}
	return b.source, nil
}

func (b *ads111xBase) onInterrupt(callback func(status uint8)) error {
	b.mu.Lock()
	if b.unsubscribe != nil {
		b.unsubscribe()
		b.unsubscribe = nil
	}
	if b.pollPin != nil {
		_ = b.pollPin.Close()
		b.pollPin = nil
	}
	b.callback = callback
	b.mu.Unlock()

	trigger := connection.Falling
	pin := b.conn.IntPin()
	if pin == nil {
		status, err := b.pollInterrupt()
		if err != nil {
			return err
		}
		poll := connection.NewDefaultPollingInputPin()
		b.mu.Lock()
		b.lastStatus = status
		b.pollPin = poll
		b.mu.Unlock()
		pin = poll
	} else if b.compPol == 1 {
		trigger = connection.Rising
	}
	unsub := pin.OnEdge(trigger, func() { b.handleEdge() })
	b.mu.Lock()
	b.unsubscribe = unsub
	b.mu.Unlock()
	return nil
}

func (b *ads111xBase) offInterrupt() error {
	b.mu.Lock()
	unsub := b.unsubscribe
	b.unsubscribe = nil
	b.callback = nil
	poll := b.pollPin
	b.pollPin = nil
	b.mu.Unlock()
	if unsub != nil {
		unsub()
	}
	if poll != nil {
		return poll.Close()
	}
	return nil
}

func (b *ads111xBase) handleEdge() {
	status, err := b.pollInterrupt()
	if err != nil {
		return
	}
	b.mu.Lock()
	cb := b.callback
	// A real ALERT edge is always an event; the polling fallback ticks
	// continuously, so it only reports changes of the status.
	fire := b.pollPin == nil || status != b.lastStatus
	b.lastStatus = status
	b.mu.Unlock()
	if fire && cb != nil {
		cb(status)
	}
}
