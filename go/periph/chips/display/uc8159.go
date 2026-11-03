package display

import (
	"errors"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// UC8159 colour codes (DTM1 nibble and CDI.VBD at DDX = 1).
const (
	UC8159Black  uint8 = 0
	UC8159White  uint8 = 1
	UC8159Green  uint8 = 2
	UC8159Blue   uint8 = 3
	UC8159Red    uint8 = 4
	UC8159Yellow uint8 = 5
	UC8159Orange uint8 = 6
	UC8159Clean  uint8 = 7
)

// UC8159 panel size.
const (
	UC8159Width  = 600
	UC8159Height = 448
)

const uc8159FrameBytes = UC8159Width * UC8159Height / 2

const (
	uc8159CmdPSR  uint8 = 0x00
	uc8159CmdPWR  uint8 = 0x01
	uc8159CmdPOF  uint8 = 0x02
	uc8159CmdPFS  uint8 = 0x03
	uc8159CmdPON  uint8 = 0x04
	uc8159CmdBTST uint8 = 0x06
	uc8159CmdDSLP uint8 = 0x07
	uc8159CmdDTM1 uint8 = 0x10
	uc8159CmdDRF  uint8 = 0x12
	uc8159CmdPLL  uint8 = 0x30
	uc8159CmdTSC  uint8 = 0x40
	uc8159CmdTSE  uint8 = 0x41
	uc8159CmdCDI  uint8 = 0x50
	uc8159CmdLPD  uint8 = 0x51
	uc8159CmdTCON uint8 = 0x60
	uc8159CmdTRES uint8 = 0x61
	uc8159CmdPWS  uint8 = 0xE3
	uc8159CmdFLG  uint8 = 0x71
	uc8159CmdVV   uint8 = 0x81
	uc8159CmdVDCS uint8 = 0x82
)

// ErrUC8159BusyTimeout is returned when BUSY does not go high within the timeout.
var ErrUC8159BusyTimeout = errors.New("uc8159: BUSY did not go high within the timeout")

// UC8159Minimal drives a UC8159 seven-colour e-paper controller (600 x 448
// panels such as the Waveshare 5.65inch e-Paper (F)).
//
// The driver keeps the picture in a framebuffer in wire format (4 bits per
// pixel, 134 400 bytes) and sends it on Show(). Every update is DTM1, PON,
// wait BUSY (active low), DRF, wait, POF, wait; Show blocks for the whole
// refresh (about 30 s).
//
// The connection is a connection.DisplayConn, which owns CS#, D/C#, RES# and
// BUSY (see specs/transport_display.md).
type UC8159Minimal struct {
	conn    connection.DisplayConn
	timeout time.Duration
	fb      []byte
	psr     uint8
	cdi     uint8
	asleep  bool
}

// NewUC8159Minimal runs the initialisation sequence (the vendor demo's
// commands): hardware reset, wait for BUSY, PSR EF 08, PWR 37 00 23 23, PFS 00,
// BTST C7 C7 1D, PLL 3C, TSE 00, CDI 37, TCON 22, TRES 600 x 448, PWS AA. The
// panel picture is left unchanged until Show(). busyTimeout limits every BUSY
// wait (<= 0 waits forever; a refresh takes about 30 s, 60 s is a good default).
func NewUC8159Minimal(conn connection.DisplayConn, busyTimeout time.Duration) (*UC8159Minimal, error) {
	d := &UC8159Minimal{conn: conn, timeout: busyTimeout, psr: 0xEF, cdi: 0x37}
	d.fb = make([]byte, uc8159FrameBytes)
	for i := range d.fb {
		d.fb[i] = 0x11
	}
	if err := d.initSequence(); err != nil {
		return nil, err
	}
	return d, nil
}

// Width returns the panel width in pixels (600).
func (d *UC8159Minimal) Width() int { return UC8159Width }

// Height returns the panel height in pixels (448).
func (d *UC8159Minimal) Height() int { return UC8159Height }

// Clear fills the framebuffer with one colour code 0..7. The panel is
// unchanged until Show().
func (d *UC8159Minimal) Clear(color uint8) {
	c := color & 7
	v := c<<4 | c
	for i := range d.fb {
		d.fb[i] = v
	}
}

// SetPixel sets one framebuffer pixel (colour code 0..7); out-of-range
// coordinates are ignored.
func (d *UC8159Minimal) SetPixel(x, y int, color uint8) {
	if x < 0 || y < 0 || x >= UC8159Width || y >= UC8159Height {
		return
	}
	i := (y*UC8159Width + x) >> 1
	c := color & 7
	if x&1 == 1 {
		d.fb[i] = d.fb[i]&0xF0 | c
	} else {
		d.fb[i] = d.fb[i]&0x0F | c<<4
	}
}

// Show sends the frame and runs a full display update (about 30 s): DTM1 +
// 134 400 bytes, PON, wait BUSY, DRF, wait BUSY, POF, wait BUSY. A sleeping
// controller is woken (hardware reset + initialisation) first. It returns
// ErrUC8159BusyTimeout when a BUSY wait exceeds the timeout.
func (d *UC8159Minimal) Show() error {
	if d.asleep {
		if err := d.wake(); err != nil {
			return err
		}
	}
	if err := d.conn.SendCommand(uc8159CmdDTM1, nil); err != nil {
		return err
	}
	for off := 0; off < uc8159FrameBytes; off += 4096 {
		end := off + 4096
		if end > uc8159FrameBytes {
			end = uc8159FrameBytes
		}
		if err := d.conn.SendData(d.fb[off:end]); err != nil {
			return err
		}
	}
	for _, cmd := range []uint8{uc8159CmdPON, uc8159CmdDRF, uc8159CmdPOF} {
		if err := d.conn.SendCommand(cmd, nil); err != nil {
			return err
		}
		if err := d.waitReady(); err != nil {
			return err
		}
	}
	return nil
}

// Sleep puts the controller into deep sleep (DSLP with check code 0xA5). Only a
// hardware reset restores communication; Show() and Wake() do that.
func (d *UC8159Minimal) Sleep() error {
	if err := d.conn.SendCommand(uc8159CmdDSLP, []byte{0xA5}); err != nil {
		return err
	}
	d.asleep = true
	return nil
}

func (d *UC8159Minimal) waitReady() error {
	ok, err := d.conn.WaitBusy(false, d.timeout)
	if err != nil {
		return err
	}
	if !ok {
		return ErrUC8159BusyTimeout
	}
	return nil
}

func (d *UC8159Minimal) initSequence() error {
	if err := d.conn.Reset(); err != nil {
		return err
	}
	if err := d.waitReady(); err != nil {
		return err
	}
	steps := []struct {
		cmd  uint8
		data []byte
	}{
		{uc8159CmdPSR, []byte{d.psr, 0x08}},
		{uc8159CmdPWR, []byte{0x37, 0x00, 0x23, 0x23}},
		{uc8159CmdPFS, []byte{0x00}},
		{uc8159CmdBTST, []byte{0xC7, 0xC7, 0x1D}},
		{uc8159CmdPLL, []byte{0x3C}},
		{uc8159CmdTSE, []byte{0x00}},
		{uc8159CmdCDI, []byte{d.cdi}},
		{uc8159CmdTCON, []byte{0x22}},
		{uc8159CmdTRES, []byte{0x02, 0x58, 0x01, 0xC0}},
		{uc8159CmdPWS, []byte{0xAA}},
	}
	for _, s := range steps {
		if err := d.conn.SendCommand(s.cmd, s.data); err != nil {
			return err
		}
	}
	return nil
}

func (d *UC8159Minimal) wake() error {
	d.asleep = false
	return d.initSequence()
}

// UC8159Full extends UC8159Minimal with drawing helpers, scan direction, frame
// rate, border/data interval, temperature-sensor source, VCOM_DC and the read
// commands (status, temperature, low power, VCOM).
//
// The Read* methods need a half-duplex wire mode; with a simplex connection
// they return connection.ErrReadUnavailable.
type UC8159Full struct {
	*UC8159Minimal
}

// NewUC8159Full is NewUC8159Minimal returning the full interface.
func NewUC8159Full(conn connection.DisplayConn, busyTimeout time.Duration) (*UC8159Full, error) {
	m, err := NewUC8159Minimal(conn, busyTimeout)
	if err != nil {
		return nil, err
	}
	return &UC8159Full{UC8159Minimal: m}, nil
}

// GetPixel returns the colour code of one framebuffer pixel (UC8159Black when out of range).
func (d *UC8159Full) GetPixel(x, y int) uint8 {
	if x < 0 || y < 0 || x >= UC8159Width || y >= UC8159Height {
		return UC8159Black
	}
	b := d.fb[(y*UC8159Width+x)>>1]
	if x&1 == 1 {
		return b & 7
	}
	return b >> 4 & 7
}

// DrawLine draws a Bresenham line into the framebuffer.
func (d *UC8159Full) DrawLine(x0, y0, x1, y1 int, color uint8) {
	dx := iabs(x1 - x0)
	dy := -iabs(y1 - y0)
	sx, sy := 1, 1
	if x0 >= x1 {
		sx = -1
	}
	if y0 >= y1 {
		sy = -1
	}
	err := dx + dy
	for {
		d.SetPixel(x0, y0, color)
		if x0 == x1 && y0 == y1 {
			break
		}
		e2 := 2 * err
		if e2 >= dy {
			err += dy
			x0 += sx
		}
		if e2 <= dx {
			err += dx
			y0 += sy
		}
	}
}

// DrawRect draws a rectangle outline, or a filled rectangle when fill is true.
func (d *UC8159Full) DrawRect(x, y, w, h int, color uint8, fill bool) {
	if w <= 0 || h <= 0 {
		return
	}
	if fill {
		for j := 0; j < h; j++ {
			for i := 0; i < w; i++ {
				d.SetPixel(x+i, y+j, color)
			}
		}
		return
	}
	d.DrawLine(x, y, x+w-1, y, color)
	d.DrawLine(x, y+h-1, x+w-1, y+h-1, color)
	d.DrawLine(x, y, x, y+h-1, color)
	d.DrawLine(x+w-1, y, x+w-1, y+h-1, color)
}

// Blit copies a 1-bit-per-pixel bitmap (row-major, MSB-first, rows padded to
// whole bytes) into the framebuffer. Set bits are drawn in color, clear bits
// leave the framebuffer unchanged.
func (d *UC8159Full) Blit(x, y int, data []byte, w, h int, color uint8) {
	if w <= 0 || h <= 0 {
		return
	}
	rowBytes := (w + 7) / 8
	for j := 0; j < h; j++ {
		for i := 0; i < w; i++ {
			if data[j*rowBytes+(i>>3)]&(0x80>>uint(i&7)) != 0 {
				d.SetPixel(x+i, y+j, color)
			}
		}
	}
}

// Wake runs a hardware reset and the initialisation sequence (exit from deep
// sleep). The framebuffer is kept.
func (d *UC8159Full) Wake() error { return d.wake() }

// WaitBusy waits for BUSY to go high; the first result is false on timeout.
// timeout <= 0 waits forever.
func (d *UC8159Full) WaitBusy(timeout time.Duration) (bool, error) {
	return d.conn.WaitBusy(false, timeout)
}

// SetFlip mirrors the picture: horizontal clears SHL, vertical clears UD. The
// framebuffer is unchanged.
func (d *UC8159Full) SetFlip(horizontal, vertical bool) error {
	if horizontal {
		d.psr &^= 0x04
	} else {
		d.psr |= 0x04
	}
	if vertical {
		d.psr &^= 0x08
	} else {
		d.psr |= 0x08
	}
	return d.conn.SendCommand(uc8159CmdPSR, []byte{d.psr, 0x08})
}

// SetFrameRate writes the raw PLL code: 0x3C = 50 Hz (default), 0x3A = 100 Hz,
// 0x39 = 200 Hz, 0x00..0x0F = 12.5 .. 200 Hz.
func (d *UC8159Full) SetFrameRate(code uint8) error {
	return d.conn.SendCommand(uc8159CmdPLL, []byte{code})
}

// SetInterval writes CDI: interval cdi 0..15 (0b0111 = 10 hsync), border
// colour 0..7 and data polarity DDX (1 = default; the colour codes assume 1).
func (d *UC8159Full) SetInterval(cdi, border, polarity uint8) error {
	d.cdi = (border&7)<<5 | (polarity&1)<<4 | cdi&0x0F
	return d.conn.SendCommand(uc8159CmdCDI, []byte{d.cdi})
}

// SetTemperatureSensor writes TSE: internal (default) or external LM75 sensor,
// offset -8..+7 degrees Celsius.
func (d *UC8159Full) SetTemperatureSensor(external bool, offset int8) error {
	v := byte(offset) & 0x0F
	if external {
		v |= 0x80
	}
	return d.conn.SendCommand(uc8159CmdTSE, []byte{v})
}

// SetVcomDc writes VDCS: -0.10 .. -4.0 V in 0.05 V steps.
func (d *UC8159Full) SetVcomDc(volts float64) error {
	c := int((-0.10-volts)/0.05 + 2.5)
	if c < 2 {
		c = 2
	}
	if c > 0x50 {
		c = 0x50
	}
	return d.conn.SendCommand(uc8159CmdVDCS, []byte{byte(c)})
}

// SendCommand sends a raw command and parameters (escape hatch).
func (d *UC8159Full) SendCommand(command uint8, data []byte) error {
	return d.conn.SendCommand(command, data)
}

func (d *UC8159Full) read(cmd uint8, n int) ([]byte, error) {
	if err := d.conn.SendCommand(cmd, nil); err != nil {
		return nil, err
	}
	return d.conn.ReadData(n)
}

// ReadStatus reads FLG: bit 5 I2C_ERR, 4 I2C_BUSYN, 3 data_flag, 2 PON, 1 POF, 0 BUSY_N.
func (d *UC8159Full) ReadStatus() (uint8, error) {
	b, err := d.read(uc8159CmdFLG, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// ReadTemperature reads the internal temperature sensor (TSC) in signed
// degrees Celsius.
func (d *UC8159Full) ReadTemperature() (int, error) {
	b, err := d.read(uc8159CmdTSC, 2)
	if err != nil {
		return 0, err
	}
	return int(int8(b[0])), nil
}

// ReadLowPower reads low-power detection (LPD); true when VDD is below 2.5 V.
func (d *UC8159Full) ReadLowPower() (bool, error) {
	b, err := d.read(uc8159CmdLPD, 1)
	if err != nil {
		return false, err
	}
	return b[0]&1 == 0, nil
}

// ReadVcom reads the measured VCOM (VV) in volts: -0.10 V - 0.05 V x (VV - 2).
func (d *UC8159Full) ReadVcom() (float64, error) {
	b, err := d.read(uc8159CmdVV, 1)
	if err != nil {
		return 0, err
	}
	c := int(b[0] & 0x7F)
	if c > 2 {
		c -= 2
	} else {
		c = 0
	}
	return -0.10 - 0.05*float64(c), nil
}
