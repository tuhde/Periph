package adcdac

import (
	"errors"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// HY3131 register addresses (see specs/adc_dac/hy3131.md).
const (
	hyRegAD1    uint8 = 0x00
	hyRegAD2    uint8 = 0x03
	hyRegLPF    uint8 = 0x06
	hyRegRMS    uint8 = 0x09
	hyRegPKHMIN uint8 = 0x0E
	hyRegPKHMAX uint8 = 0x11
	hyRegCTSTA  uint8 = 0x14
	hyRegCTC    uint8 = 0x15
	hyRegCTB    uint8 = 0x18
	hyRegCTA    uint8 = 0x1B
	hyRegINTF   uint8 = 0x1E
	hyRegINTE   uint8 = 0x1F
	hyRegR20    uint8 = 0x20
	hyRegR22    uint8 = 0x22
	hyRegR23    uint8 = 0x23
	hyRegR24    uint8 = 0x24
	hyRegR25    uint8 = 0x25
	hyRegR26    uint8 = 0x26
	hyRegR27    uint8 = 0x27
	hyRegR28    uint8 = 0x28
	hyRegR29    uint8 = 0x29
	hyRegR2A    uint8 = 0x2A
	hyRegR2F    uint8 = 0x2F
	hyRegR30    uint8 = 0x30
	hyRegR31    uint8 = 0x31
	hyRegR33    uint8 = 0x33
	hyRegR34    uint8 = 0x34
	hyRegR35    uint8 = 0x35
	hyRegR37    uint8 = 0x37
)

// HY3131 INTF flag bits.
const (
	HY3131FlagCTF  uint8 = 0x01
	HY3131FlagAD2F uint8 = 0x02
	HY3131FlagAD1F uint8 = 0x04
	HY3131FlagLPFF uint8 = 0x08
	HY3131FlagRMSF uint8 = 0x10
	HY3131FlagBORF uint8 = 0x80
)

var (
	hyAD1Gains = [4]float64{0.9, 1.8, 2.7, 3.6}
	hyDCOS     = [8]float64{0, 0.25, 0.5, 0.75, 0, -0.25, -0.5, -0.75}
)

const hyTimeout = 2 * time.Second

// ErrHY3131Timeout is returned when no conversion completes within 2 s.
var ErrHY3131Timeout = errors.New("hy3131: conversion timeout")

func hySext(value int64, bits uint) int64 {
	sign := int64(1) << (bits - 1)
	if value&sign != 0 {
		return value - (sign << 1)
	}
	return value
}

// HY3131Minimal reads AD1 of the HY3131 DMM analog front end as a differential voltage.
//
// The SPI framing is non-standard (7-bit address + R/W bit, one blank clock on
// reads, auto-increment), so the driver uses the bare Connection's Write and
// WriteRead.
type HY3131Minimal struct {
	conn  connection.Connection
	vref  float64
	flags uint8
	ig    int
	rg    uint8
	dcos  float64
}

// NewHY3131Minimal resets the chip and applies the Minimal defaults (AD1 on
// PB2 vs AGND, REFO = vref from the internal bandgap, 5 Hz output rate).
func NewHY3131Minimal(conn connection.Connection, vref float64) (*HY3131Minimal, error) {
	s := &HY3131Minimal{conn: conn, vref: vref}
	if err := s.init(); err != nil {
		return nil, err
	}
	return s, nil
}

func (s *HY3131Minimal) init() error {
	steps := []struct {
		reg   uint8
		data  []byte
		sleep time.Duration
	}{
		{hyRegR37, []byte{0x60}, 5 * time.Millisecond},
		{hyRegR33, []byte{0x28}, 10 * time.Millisecond},
		{hyRegR31, []byte{0xC0}, 0},
		{hyRegR30, []byte{0x00}, 5 * time.Millisecond},
		{hyRegR2F, []byte{0x80}, 0},
		{hyRegR2A, make([]byte, 5), 0},
		{0x32, []byte{0}, 0}, {hyRegR20, []byte{0}, 0}, {0x21, []byte{0}, 0},
		{hyRegR34, []byte{0}, 0}, {hyRegR35, []byte{0}, 0}, {0x36, []byte{0}, 0},
		{hyRegR29, []byte{0}, 0}, {hyRegR26, []byte{0}, 0}, {hyRegR27, []byte{0}, 0},
		{hyRegINTE, []byte{0}, 0},
		{hyRegR24, []byte{0xA3}, 0},
		{hyRegR28, []byte{0x11}, 0},
		{hyRegR25, []byte{0x00}, 0},
		{hyRegR22, []byte{0x07}, 0},
		{hyRegR23, []byte{0x80}, 0},
	}
	for _, st := range steps {
		if err := s.WriteRegister(st.reg, st.data); err != nil {
			return err
		}
		if st.sleep > 0 {
			time.Sleep(st.sleep)
		}
	}
	s.ig, s.rg, s.dcos = 0, 0, 0
	if _, err := s.ReadRegister(hyRegINTF, 1); err != nil {
		return err
	}
	s.flags = 0
	return nil
}

// ReadRegister reads n consecutive registers starting at reg (auto-increment).
func (s *HY3131Minimal) ReadRegister(reg uint8, n int) ([]byte, error) {
	rx, err := s.conn.WriteRead([]byte{((reg & 0x7F) << 1) | 1}, n+1)
	if err != nil {
		return nil, err
	}
	out := make([]byte, n)
	for i := 0; i < n; i++ {
		out[i] = (rx[i] << 1) | (rx[i+1] >> 7)
	}
	return out, nil
}

// WriteRegister writes data starting at reg (auto-increment).
func (s *HY3131Minimal) WriteRegister(reg uint8, data []byte) error {
	return s.conn.Write(append([]byte{(reg & 0x7F) << 1}, data...))
}

func (s *HY3131Minimal) waitFlag(mask uint8) error {
	deadline := time.Now().Add(hyTimeout)
	for {
		b, err := s.ReadRegister(hyRegINTF, 1)
		if err != nil {
			return err
		}
		s.flags |= b[0]
		if s.flags&mask != 0 {
			s.flags &^= mask
			return nil
		}
		if time.Now().After(deadline) {
			return ErrHY3131Timeout
		}
		time.Sleep(time.Millisecond)
	}
}

func (s *HY3131Minimal) read24(reg uint8) (int64, error) {
	b, err := s.ReadRegister(reg, 3)
	if err != nil {
		return 0, err
	}
	return int64(b[0]) | int64(b[1])<<8 | int64(b[2])<<16, nil
}

// DataReady reports whether a new AD1 conversion is pending (latches INTF).
func (s *HY3131Minimal) DataReady() (bool, error) {
	b, err := s.ReadRegister(hyRegINTF, 1)
	if err != nil {
		return false, err
	}
	s.flags |= b[0]
	return s.flags&HY3131FlagAD1F != 0, nil
}

// ReadRaw waits for a conversion and returns the signed 24-bit AD1 code.
func (s *HY3131Minimal) ReadRaw() (int32, error) {
	if err := s.waitFlag(HY3131FlagAD1F); err != nil {
		return 0, err
	}
	v, err := s.read24(hyRegAD1)
	if err != nil {
		return 0, err
	}
	return int32(hySext(v, 24)), nil
}

// ReadVoltage returns the AD1 differential input voltage in volts.
func (s *HY3131Minimal) ReadVoltage() (float64, error) {
	code, err := s.ReadRaw()
	if err != nil {
		return 0, err
	}
	rg := 1.0
	if s.rg != 0 {
		rg = 0.3333333333
	}
	return (float64(code)/8388608.0*rg - s.dcos) * s.vref / hyAD1Gains[s.ig], nil
}

// HY3131Full adds AD1/AD2/AD3 configuration, LPF, RMS, peak hold, the
// frequency counter, interrupt flags and raw register access.
type HY3131Full struct {
	*HY3131Minimal
}

// NewHY3131Full resets the chip and applies the Minimal defaults.
func NewHY3131Full(conn connection.Connection, vref float64) (*HY3131Full, error) {
	m, err := NewHY3131Minimal(conn, vref)
	if err != nil {
		return nil, err
	}
	return &HY3131Full{m}, nil
}

func (f *HY3131Full) rd1(reg uint8) (uint8, error) {
	b, err := f.ReadRegister(reg, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (f *HY3131Full) wr1(reg, v uint8) error { return f.WriteRegister(reg, []byte{v}) }

func (f *HY3131Full) clearFlags() error {
	if _, err := f.rd1(hyRegINTF); err != nil {
		return err
	}
	f.flags = 0
	return nil
}

// Reset software-resets the chip and re-applies the Minimal defaults.
func (f *HY3131Full) Reset() error { return f.init() }

// ConfigureAD1 sets osr 0–7 (OSR1 256…32768), chop 0–3, inputGain 0–3
// (0.9/1.8/2.7/3.6), refGain 0/1 (1.0/0.333), offset 0–7 (DCOS code) and a
// 4-bit buffers mask (bit3 RH, bit2 RL, bit1 IP, bit0 IN).
func (f *HY3131Full) ConfigureAD1(osr, chop, inputGain, refGain, offset, buffers uint8) error {
	r25, err := f.rd1(hyRegR25)
	if err != nil {
		return err
	}
	if err := f.wr1(hyRegR25, (r25&0xCF)|((inputGain&3)<<4)); err != nil {
		return err
	}
	if err := f.wr1(hyRegR22, ((offset&7)<<5)|((chop&3)<<3)|(osr&7)); err != nil {
		return err
	}
	if err := f.wr1(hyRegR23, 0x80|((refGain&1)<<4)|(buffers&0x0F)); err != nil {
		return err
	}
	f.ig, f.rg, f.dcos = int(inputGain&3), refGain&1, hyDCOS[offset&7]
	return f.clearFlags()
}

// SetAD1Input selects pos 0–15 (SAD1FP), neg 0–7 (SAD1FN) and filterR 0–3 (SFT1).
func (f *HY3131Full) SetAD1Input(pos, neg, filterR uint8) error {
	if err := f.wr1(hyRegR24, ((pos&15)<<4)|(neg&7)); err != nil {
		return err
	}
	r33, err := f.rd1(hyRegR33)
	if err != nil {
		return err
	}
	return f.wr1(hyRegR33, (r33&0xF3)|((filterR&3)<<2))
}

// SetAD1Reference selects high 0–6 (SAD1RH) and low 0–6 (SAD1RL).
func (f *HY3131Full) SetAD1Reference(high, low uint8) error {
	return f.wr1(hyRegR28, ((high&7)<<4)|(low&7))
}

// ReadTemperatureCode returns (AD1[SAD1I=11] − AD1[SAD1I=10]) / 2.
func (f *HY3131Full) ReadTemperatureCode() (int32, error) {
	r33, err := f.rd1(hyRegR33)
	if err != nil {
		return 0, err
	}
	var codes [2]int32
	for i, sel := range []uint8{2, 3} {
		if err := f.wr1(hyRegR33, (r33&0xFC)|sel); err != nil {
			return 0, err
		}
		if err := f.clearFlags(); err != nil {
			return 0, err
		}
		if err := f.waitFlag(HY3131FlagAD1F); err != nil { // discard settling sample
			return 0, err
		}
		if codes[i], err = f.ReadRaw(); err != nil {
			return 0, err
		}
	}
	if err := f.wr1(hyRegR33, r33); err != nil {
		return 0, err
	}
	f.flags = 0
	return (codes[1] - codes[0]) / 2, nil
}

// ReadTemperature returns the die temperature in °C; tcode25 is
// ReadTemperatureCode() measured at 25 °C.
func (f *HY3131Full) ReadTemperature(tcode25 int32) (float64, error) {
	const tOs = -3.15
	g := float64(tcode25) / (25 + 273.15 + tOs)
	tc, err := f.ReadTemperatureCode()
	if err != nil {
		return 0, err
	}
	return float64(tc)/g - 273.15 - tOs, nil
}

// EnableAD2 enables AD2 (and AD3 when useAD3).
func (f *HY3131Full) EnableAD2(osr, inputGain, refGain uint8, chop, clkDiv4 bool, source uint8, useAD3 bool) error {
	r25, err := f.rd1(hyRegR25)
	if err != nil {
		return err
	}
	if err := f.wr1(hyRegR25, (r25&0x3F)|((inputGain&3)<<6)); err != nil {
		return err
	}
	if err := f.wr1(hyRegR27, ((source&3)<<6)|0x15); err != nil {
		return err
	}
	var chopBit, divBit uint8
	if chop {
		chopBit = 0x20
	}
	if clkDiv4 {
		divBit = 0x08
	}
	if err := f.wr1(hyRegR26, 0x80|chopBit|((refGain&1)<<4)|divBit|(osr&7)); err != nil {
		return err
	}
	if useAD3 {
		if err := f.wr1(hyRegR35, ((source&3)<<6)|(1<<4)|((inputGain&3)<<2)); err != nil {
			return err
		}
		if err := f.wr1(hyRegR34, 0x80|chopBit|((refGain&1)<<4)|0x04); err != nil {
			return err
		}
	} else if err := f.wr1(hyRegR34, 0x00); err != nil {
		return err
	}
	return f.clearFlags()
}

// ReadAD2Raw waits for AD2 (or AD3) and returns the signed 19-bit code.
func (f *HY3131Full) ReadAD2Raw() (int32, error) {
	if err := f.waitFlag(HY3131FlagAD2F); err != nil {
		return 0, err
	}
	v, err := f.read24(hyRegAD2)
	return int32(hySext(v, 24)), err
}

// EnableLPF enables the low-pass filter; bw 0–7 → OSR4 128…16384.
func (f *HY3131Full) EnableLPF(bw uint8) error {
	r29, err := f.rd1(hyRegR29)
	if err != nil {
		return err
	}
	return f.wr1(hyRegR29, (r29&0x87)|0x40|((bw&7)<<3))
}

// ReadLPFRaw waits for the LPF and returns the signed 19-bit code.
func (f *HY3131Full) ReadLPFRaw() (int32, error) {
	if err := f.waitFlag(HY3131FlagLPFF); err != nil {
		return 0, err
	}
	v, err := f.read24(hyRegLPF)
	return int32(hySext(v, 24)), err
}

// EnableRMS enables the RMS converter (requires AD2 and the LPF).
func (f *HY3131Full) EnableRMS() error {
	r29, err := f.rd1(hyRegR29)
	if err != nil {
		return err
	}
	return f.wr1(hyRegR29, r29|0x80)
}

// ReadRMS waits for RMS and returns sqrt(RMS accumulator) in AD2 LSB units.
func (f *HY3131Full) ReadRMS() (float64, error) {
	if err := f.waitFlag(HY3131FlagRMSF); err != nil {
		return 0, err
	}
	b, err := f.ReadRegister(hyRegRMS, 5)
	if err != nil {
		return 0, err
	}
	raw := int64(b[0]) | int64(b[1])<<8 | int64(b[2])<<16 | int64(b[3])<<24 | int64(b[4])<<32
	v := hySext(raw, 40)
	if v < 0 {
		v = 0
	}
	return math.Sqrt(float64(v)), nil
}

// EnablePeakHold enables peak hold; source 0–3 → AD2, AD1<23:5>, LPF, LPF.
// Re-enabling resets min/max.
func (f *HY3131Full) EnablePeakHold(source uint8) error {
	r29, err := f.rd1(hyRegR29)
	if err != nil {
		return err
	}
	if err := f.wr1(hyRegR29, r29&^0x07); err != nil {
		return err
	}
	return f.wr1(hyRegR29, (r29&^0x07)|0x04|(source&3))
}

// ReadPeak returns the signed 19-bit peak-hold minimum and maximum codes.
func (f *HY3131Full) ReadPeak() (int32, int32, error) {
	lo, err := f.read24(hyRegPKHMIN)
	if err != nil {
		return 0, 0, err
	}
	hi, err := f.read24(hyRegPKHMAX)
	if err != nil {
		return 0, 0, err
	}
	return int32(hySext(lo, 24)), int32(hySext(hi, 24)), nil
}

// StartCounter starts the frequency counter; gatePreload (0–0xFFFF) goes to CTA<23:8>.
func (f *HY3131Full) StartCounter(gatePreload uint16) error {
	r20, err := f.rd1(hyRegR20)
	if err != nil {
		return err
	}
	if err := f.wr1(hyRegR20, r20&^0x02); err != nil {
		return err
	}
	if err := f.WriteRegister(hyRegCTA+1, []byte{byte(gatePreload), byte(gatePreload >> 8)}); err != nil {
		return err
	}
	if err := f.clearFlags(); err != nil {
		return err
	}
	return f.wr1(hyRegR20, r20|0x0A)
}

// ReadCounter waits for the counter and returns CTA, CTB, CTC and the CTB overflow flag.
func (f *HY3131Full) ReadCounter() (cta, ctb, ctc uint32, overflow bool, err error) {
	if err = f.waitFlag(HY3131FlagCTF); err != nil {
		return
	}
	var st uint8
	if st, err = f.rd1(hyRegCTSTA); err != nil {
		return
	}
	overflow = st&0x01 != 0
	var v int64
	if v, err = f.read24(hyRegCTA); err != nil {
		return
	}
	cta = uint32(v)
	if v, err = f.read24(hyRegCTB); err != nil {
		return
	}
	ctb = uint32(v)
	if v, err = f.read24(hyRegCTC); err != nil {
		return
	}
	ctc = uint32(v)
	return
}

// ReadFlags returns INTF (clearing it on the chip); latched flags are included and cleared.
func (f *HY3131Full) ReadFlags() (uint8, error) {
	b, err := f.rd1(hyRegINTF)
	if err != nil {
		return 0, err
	}
	out := f.flags | b
	f.flags = 0
	return out, nil
}

// EnableInterrupts writes INTE (RMSIE 4, LPFIE 3, AD1IE 2, AD2IE 1, CTIE 0).
func (f *HY3131Full) EnableInterrupts(mask uint8) error { return f.wr1(hyRegINTE, mask&0x1F) }
