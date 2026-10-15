package display

import (
	"errors"
	"fmt"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// IL0373 colours.
const (
	IL0373Black uint8 = 0
	IL0373White uint8 = 1
)

// IL0373 maximum panel size.
const (
	IL0373MaxWidth  = 160
	IL0373MaxHeight = 296
)

// IL0373 default panel size (Waveshare 2.13inch e-Paper (D)).
const (
	IL0373DefaultWidth  = 104
	IL0373DefaultHeight = 212
)

const (
	il0373CmdPSR   uint8 = 0x00
	il0373CmdPWR   uint8 = 0x01
	il0373CmdPOF   uint8 = 0x02
	il0373CmdPON   uint8 = 0x04
	il0373CmdBTST  uint8 = 0x06
	il0373CmdDSLP  uint8 = 0x07
	il0373CmdDTM1  uint8 = 0x10
	il0373CmdDRF   uint8 = 0x12
	il0373CmdDTM2  uint8 = 0x13
	il0373CmdLUTC  uint8 = 0x20
	il0373CmdPLL   uint8 = 0x30
	il0373CmdTSC   uint8 = 0x40
	il0373CmdTSE   uint8 = 0x41
	il0373CmdCDI   uint8 = 0x50
	il0373CmdLPD   uint8 = 0x51
	il0373CmdTRES  uint8 = 0x61
	il0373CmdREV   uint8 = 0x70
	il0373CmdFLG   uint8 = 0x71
	il0373CmdVV    uint8 = 0x81
	il0373CmdVDCS  uint8 = 0x82
	il0373CmdPTL   uint8 = 0x90
	il0373CmdPTIN  uint8 = 0x91
	il0373CmdPTOUT uint8 = 0x92
	il0373CmdROTP  uint8 = 0xA2
)

// ErrIL0373BusyTimeout is returned when BUSY does not go high within the timeout.
var ErrIL0373BusyTimeout = errors.New("il0373: BUSY did not go high within the timeout")

// ErrIL0373InvalidWindow is returned by ShowRegion for an empty or off-panel window.
var ErrIL0373InvalidWindow = errors.New("il0373: window is empty or outside the panel")

// il0373PLLHz is the frame rate in Hz for PLL M (1..7) and N (1..7).
var il0373PLLHz = [7][7]int{
	{29, 14, 10, 7, 6, 5, 4},
	{57, 29, 19, 14, 11, 10, 8},
	{86, 43, 29, 21, 17, 14, 12},
	{114, 57, 38, 29, 23, 19, 16},
	{150, 72, 48, 36, 29, 24, 20},
	{171, 86, 57, 43, 34, 29, 24},
	{200, 100, 67, 50, 40, 33, 29},
}

func il0373Code(value, base, step float64, maxCode int) uint8 {
	c := int((value-base)/step + 0.5)
	if c < 0 {
		c = 0
	}
	if c > maxCode {
		c = maxCode
	}
	return uint8(c)
}

func il0373Invert(b []byte) []byte {
	out := make([]byte, len(b))
	for i, v := range b {
		out[i] = ^v
	}
	return out
}

// IL0373Minimal drives an IL0373 black/white e-paper controller (104 x 212
// panels such as the Waveshare 2.13inch e-Paper (D)).
//
// The driver keeps the picture in a framebuffer and sends it on Show(). Every
// update follows the vendor reference flow: PON (if the booster is off),
// DTM1 + DTM2, DRF and wait on BUSY (active low), CDI border floating, POF and
// wait; Show blocks for the whole refresh.
//
// The connection is a connection.DisplayConn, which owns CS#, D/C#, RES# and
// BUSY (see specs/transport_display.md).
//
// Pixel polarity: bit 1 = white, bit 0 = black (CDI.DDX[0] = 1, KW mode). The
// controller drives a pixel only when its NEW and OLD bits differ, so the
// driver sends OLD = NOT NEW: every pixel is driven to its final state and a
// refresh does not depend on the previous picture.
type IL0373Minimal struct {
	conn     connection.DisplayConn
	width    int
	height   int
	rowBytes int
	size     int
	timeout  time.Duration
	fb       []byte
	psr      uint8
	powered  bool
	asleep   bool
}

// NewIL0373Minimal runs the initialisation sequence (vendor OTP reference
// flow): hardware reset, wait for BUSY, BTST 17 17 17, PON, wait, PSR 0x1F
// (KW, LUT from OTP, scan up, shift right, booster on), TRES and CDI 0x97. The
// panel picture is left unchanged until Show().
//
// width is a multiple of 8 in 8..160 (IL0373DefaultWidth), height in 1..296
// (IL0373DefaultHeight); busyTimeout limits every BUSY wait (<= 0 waits
// forever; the panel datasheet gives no refresh time, 30 s is a safe default).
func NewIL0373Minimal(conn connection.DisplayConn, width, height int, busyTimeout time.Duration) (*IL0373Minimal, error) {
	if width < 8 || width > IL0373MaxWidth || width%8 != 0 {
		return nil, fmt.Errorf("il0373: width %d must be a multiple of 8 in 8..%d", width, IL0373MaxWidth)
	}
	if height < 1 || height > IL0373MaxHeight {
		return nil, fmt.Errorf("il0373: height %d must be in 1..%d", height, IL0373MaxHeight)
	}
	d := &IL0373Minimal{
		conn:     conn,
		width:    width,
		height:   height,
		rowBytes: width / 8,
		size:     width / 8 * height,
		timeout:  busyTimeout,
		psr:      0x1F,
	}
	d.fb = make([]byte, d.size)
	for i := range d.fb {
		d.fb[i] = 0xFF
	}
	if err := d.initSequence(); err != nil {
		return nil, err
	}
	return d, nil
}

// Width returns the panel width in pixels.
func (d *IL0373Minimal) Width() int { return d.width }

// Height returns the panel height in pixels.
func (d *IL0373Minimal) Height() int { return d.height }

// Clear fills the framebuffer with one colour (IL0373White or IL0373Black).
// The panel is unchanged until Show().
func (d *IL0373Minimal) Clear(color uint8) {
	fill := byte(0x00)
	if color == IL0373White {
		fill = 0xFF
	}
	for i := range d.fb {
		d.fb[i] = fill
	}
}

// SetPixel sets one framebuffer pixel; out-of-range coordinates are ignored.
func (d *IL0373Minimal) SetPixel(x, y int, color uint8) {
	if x < 0 || y < 0 || x >= d.width || y >= d.height {
		return
	}
	index := y*d.rowBytes + (x >> 3)
	mask := byte(0x80 >> uint(x&7))
	if color == IL0373White {
		d.fb[index] |= mask
	} else {
		d.fb[index] &^= mask
	}
}

// Show writes OLD and NEW data to the controller and runs a full display
// update: PON (if the booster is off), DTM1 (OLD = NOT NEW), DTM2 (NEW), DRF,
// wait BUSY, CDI 0xF7 (border floating), POF, wait BUSY. A sleeping controller
// is woken (hardware reset + initialisation) first. It returns
// ErrIL0373BusyTimeout when a BUSY wait exceeds the timeout.
func (d *IL0373Minimal) Show() error {
	if d.asleep {
		if err := d.wake(); err != nil {
			return err
		}
	}
	if err := d.ensurePower(); err != nil {
		return err
	}
	if err := d.writePlanes(); err != nil {
		return err
	}
	return d.refreshPowerOff()
}

// Sleep puts the controller into deep sleep (border floating, POF, DSLP with
// check code 0xA5). Only a hardware reset restores communication; Show() and
// Wake() do that. The panel keeps its image.
func (d *IL0373Minimal) Sleep() error {
	if d.powered {
		if err := d.powerOff(); err != nil {
			return err
		}
	}
	if err := d.conn.SendCommand(il0373CmdDSLP, []byte{0xA5}); err != nil {
		return err
	}
	d.asleep = true
	return nil
}

func (d *IL0373Minimal) waitReady() error {
	ok, err := d.conn.WaitBusy(false, d.timeout)
	if err != nil {
		return err
	}
	if !ok {
		return ErrIL0373BusyTimeout
	}
	return nil
}

func (d *IL0373Minimal) initSequence() error {
	if err := d.conn.Reset(); err != nil {
		return err
	}
	if err := d.waitReady(); err != nil {
		return err
	}
	if err := d.conn.SendCommand(il0373CmdBTST, []byte{0x17, 0x17, 0x17}); err != nil {
		return err
	}
	if err := d.conn.SendCommand(il0373CmdPON, nil); err != nil {
		return err
	}
	if err := d.waitReady(); err != nil {
		return err
	}
	d.powered = true
	if err := d.conn.SendCommand(il0373CmdPSR, []byte{d.psr}); err != nil {
		return err
	}
	tres := []byte{byte(d.width & 0xF8), byte((d.height >> 8) & 1), byte(d.height & 0xFF)}
	if err := d.conn.SendCommand(il0373CmdTRES, tres); err != nil {
		return err
	}
	return d.conn.SendCommand(il0373CmdCDI, []byte{0x97})
}

func (d *IL0373Minimal) wake() error {
	d.asleep = false
	d.powered = false
	return d.initSequence()
}

func (d *IL0373Minimal) ensurePower() error {
	if d.powered {
		return nil
	}
	if err := d.conn.SendCommand(il0373CmdPON, nil); err != nil {
		return err
	}
	if err := d.waitReady(); err != nil {
		return err
	}
	d.powered = true
	return d.conn.SendCommand(il0373CmdCDI, []byte{0x97})
}

func (d *IL0373Minimal) powerOff() error {
	if err := d.conn.SendCommand(il0373CmdCDI, []byte{0xF7}); err != nil {
		return err
	}
	if err := d.conn.SendCommand(il0373CmdPOF, nil); err != nil {
		return err
	}
	d.powered = false
	return d.waitReady()
}

func (d *IL0373Minimal) refreshPowerOff() error {
	if err := d.conn.SendCommand(il0373CmdDRF, nil); err != nil {
		return err
	}
	if err := d.waitReady(); err != nil {
		return err
	}
	return d.powerOff()
}

func (d *IL0373Minimal) writePlanes() error {
	if err := d.conn.SendCommand(il0373CmdDTM1, nil); err != nil {
		return err
	}
	if err := d.conn.SendData(il0373Invert(d.fb)); err != nil {
		return err
	}
	if err := d.conn.SendCommand(il0373CmdDTM2, nil); err != nil {
		return err
	}
	return d.conn.SendData(d.fb)
}

// IL0373Full extends IL0373Minimal with drawing helpers, partial-window
// updates, scan direction, power and VCOM settings, frame rate, user LUTs and
// the read commands (status, temperature, revision, VCOM, low power, OTP).
//
// The Read* methods need a half-duplex wire mode; with a simplex connection
// they return connection.ErrReadUnavailable.
type IL0373Full struct {
	*IL0373Minimal
}

// NewIL0373Full is NewIL0373Minimal returning the full interface.
func NewIL0373Full(conn connection.DisplayConn, width, height int, busyTimeout time.Duration) (*IL0373Full, error) {
	m, err := NewIL0373Minimal(conn, width, height, busyTimeout)
	if err != nil {
		return nil, err
	}
	return &IL0373Full{IL0373Minimal: m}, nil
}

// GetPixel returns the colour of one framebuffer pixel (IL0373Black when out of range).
func (d *IL0373Full) GetPixel(x, y int) uint8 {
	if x < 0 || y < 0 || x >= d.width || y >= d.height {
		return IL0373Black
	}
	index := y*d.rowBytes + (x >> 3)
	mask := byte(0x80 >> uint(x&7))
	if d.fb[index]&mask != 0 {
		return IL0373White
	}
	return IL0373Black
}

// DrawLine draws a Bresenham line into the framebuffer.
func (d *IL0373Full) DrawLine(x0, y0, x1, y1 int, color uint8) {
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
func (d *IL0373Full) DrawRect(x, y, w, h int, color uint8, fill bool) {
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
func (d *IL0373Full) Blit(x, y int, data []byte, w, h int, color uint8) {
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

// ShowRegion updates only a window: PTIN, PTL (window widened to whole 8-pixel
// columns), the window's bytes of both planes, DRF, power off, PTOUT. It saves
// SPI time; the waveform is the OTP full waveform, so the panel still flashes.
// It returns ErrIL0373InvalidWindow for an empty or off-panel window.
func (d *IL0373Full) ShowRegion(x, y, w, h int) error {
	if w <= 0 || h <= 0 {
		return ErrIL0373InvalidWindow
	}
	if x < 0 {
		w += x
		x = 0
	}
	if y < 0 {
		h += y
		y = 0
	}
	if x >= d.width || y >= d.height || w <= 0 || h <= 0 {
		return ErrIL0373InvalidWindow
	}
	x0 := x &^ 7
	x1 := (x + w - 1) | 7
	if x1 >= d.width {
		x1 = d.width - 1
	}
	y0 := y
	y1 := y + h - 1
	if y1 >= d.height {
		y1 = d.height - 1
	}
	if d.asleep {
		if err := d.wake(); err != nil {
			return err
		}
	}
	if err := d.ensurePower(); err != nil {
		return err
	}
	if err := d.conn.SendCommand(il0373CmdPTIN, nil); err != nil {
		return err
	}
	ptl := []byte{
		byte(x0 & 0xF8), byte((x1 & 0xF8) | 0x07),
		byte((y0 >> 8) & 1), byte(y0 & 0xFF),
		byte((y1 >> 8) & 1), byte(y1 & 0xFF),
		0x00, // PT_SCAN = 0: scan only inside the window
	}
	if err := d.conn.SendCommand(il0373CmdPTL, ptl); err != nil {
		return err
	}
	winBytes := (x1 - x0 + 1) / 8
	for _, p := range []struct {
		cmd    uint8
		invert bool
	}{{il0373CmdDTM1, true}, {il0373CmdDTM2, false}} {
		if err := d.conn.SendCommand(p.cmd, nil); err != nil {
			return err
		}
		for row := y0; row <= y1; row++ {
			start := row*d.rowBytes + (x0 >> 3)
			chunk := d.fb[start : start+winBytes]
			if p.invert {
				chunk = il0373Invert(chunk)
			}
			if err := d.conn.SendData(chunk); err != nil {
				return err
			}
		}
	}
	refreshErr := d.refreshPowerOff()
	if err := d.conn.SendCommand(il0373CmdPTOUT, nil); err != nil && refreshErr == nil {
		return err
	}
	return refreshErr
}

// Wake runs a hardware reset and the initialisation sequence (exit from deep
// sleep). The framebuffer is kept.
func (d *IL0373Full) Wake() error { return d.wake() }

// WaitBusy waits for BUSY to go high; the first result is false on timeout.
// timeout <= 0 waits forever.
func (d *IL0373Full) WaitBusy(timeout time.Duration) (bool, error) {
	return d.conn.WaitBusy(false, timeout)
}

func (d *IL0373Full) writePSR() error {
	return d.conn.SendCommand(il0373CmdPSR, []byte{d.psr})
}

// SetFlip mirrors the picture: horizontal clears SHL, vertical clears UD. The
// framebuffer is unchanged.
func (d *IL0373Full) SetFlip(horizontal, vertical bool) error {
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
	return d.writePSR()
}

// SetPower writes PWR (internal DC-DC).
//
// Advanced: the values must match the panel vendor's; a wrong set can drive
// the panel outside its rating. The driver does not write PWR by default.
// vdh and vdl (magnitude) are 6.4..15.0 V in 0.2 V steps; vgh is 20, 19, 18
// or 17 V (VGL = -VGH).
func (d *IL0373Full) SetPower(vdh, vdl, vgh float64) error {
	if vdl < 0 {
		vdl = -vdl
	}
	lv := int(20.0 - vgh + 0.5)
	if lv < 0 {
		lv = 0
	}
	if lv > 3 {
		lv = 3
	}
	return d.conn.SendCommand(il0373CmdPWR, []byte{
		0x03, // VDS_EN | VDG_EN
		byte(lv),
		il0373Code(vdh, 6.4, 0.2, 0x2B),
		il0373Code(vdl, 6.4, 0.2, 0x2B),
		0x03, // VDHR (red): unused in KW mode
	})
}

// SetVcomDc writes VDCS: -0.10 .. -3.00 V in 0.05 V steps.
func (d *IL0373Full) SetVcomDc(volts float64) error {
	return d.conn.SendCommand(il0373CmdVDCS, []byte{il0373Code(-0.10-volts, 0.0, 0.05, 0x3A)})
}

// SetFrameRate writes PLL with the nearest rate of the M/N table (4..200 Hz).
func (d *IL0373Full) SetFrameRate(hz int) error {
	best := byte(0x3C) // M = 7, N = 4 (50 Hz)
	bestDiff := 1 << 30
	for m := 1; m <= 7; m++ {
		for n := 1; n <= 7; n++ {
			diff := iabs(il0373PLLHz[m-1][n-1] - hz)
			if diff < bestDiff {
				bestDiff = diff
				best = byte(m<<3 | n)
			}
		}
	}
	return d.conn.SendCommand(il0373CmdPLL, []byte{best})
}

// SetSoftStart writes BTST (booster soft start phases A, B, C).
func (d *IL0373Full) SetSoftStart(a, b, c uint8) error {
	return d.conn.SendCommand(il0373CmdBTST, []byte{a, b, c})
}

// SetInterval writes CDI: interval cdi 0..15 (0b0111 = 10 hsync), border VBD
// 0..3 (2 as at initialisation) and data polarity DDX 0..3. The framebuffer
// mapping assumes polarity 1; changing it is the caller's responsibility.
func (d *IL0373Full) SetInterval(cdi, border, polarity uint8) error {
	return d.conn.SendCommand(il0373CmdCDI, []byte{(border&3)<<6 | (polarity&3)<<4 | cdi&0x0F})
}

// SetTemperatureSensor writes TSE: internal (default) or external LM75 sensor,
// offset -8..+7 degrees Celsius.
func (d *IL0373Full) SetTemperatureSensor(external bool, offset int8) error {
	v := byte(offset) & 0x0F
	if external {
		v |= 0x80
	}
	return d.conn.SendCommand(il0373CmdTSE, []byte{v})
}

// WriteLUT loads one user waveform table and selects register LUTs
// (PSR.REG = 1). table 0 = VCOM, 1 = W-W, 2 = B-W, 3 = W-B, 4 = B-B (commands
// 0x20..0x24). The chip datasheet gives 44 (VCOM) / 42 bytes per table, the
// panel datasheet 60 / 36 / 60 / 36 / 36; the bytes are sent as given.
func (d *IL0373Full) WriteLUT(table int, data []byte) error {
	if table < 0 || table > 4 {
		return fmt.Errorf("il0373: LUT table %d must be 0..4", table)
	}
	if err := d.conn.SendCommand(il0373CmdLUTC+uint8(table), data); err != nil {
		return err
	}
	d.psr |= 0x20
	return d.writePSR()
}

// SendCommand sends a raw command and parameters (escape hatch).
func (d *IL0373Full) SendCommand(command uint8, data []byte) error {
	return d.conn.SendCommand(command, data)
}

func (d *IL0373Full) read(cmd uint8, n int) ([]byte, error) {
	if err := d.conn.SendCommand(cmd, nil); err != nil {
		return nil, err
	}
	return d.conn.ReadData(n)
}

// ReadStatus reads FLG: bit 6 PTL_FLAG, 5 I2C_ERR, 4 I2C_BUSYN, 3 data_flag,
// 2 PON, 1 POF, 0 BUSY.
func (d *IL0373Full) ReadStatus() (uint8, error) {
	b, err := d.read(il0373CmdFLG, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// ReadTemperature reads the internal temperature sensor (TSC) in signed
// degrees Celsius.
func (d *IL0373Full) ReadTemperature() (int, error) {
	b, err := d.read(il0373CmdTSC, 2)
	if err != nil {
		return 0, err
	}
	return int(int8(b[0])), nil
}

// ReadRevision reads REV (LUT_REV, one byte).
func (d *IL0373Full) ReadRevision() (uint8, error) {
	b, err := d.read(il0373CmdREV, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// ReadVcom reads the measured VCOM (VV) in volts.
func (d *IL0373Full) ReadVcom() (float64, error) {
	b, err := d.read(il0373CmdVV, 1)
	if err != nil {
		return 0, err
	}
	return -0.10 - 0.05*float64(b[0]&0x3F), nil
}

// ReadLowPower reads low-power detection (LPD); true when the supply is low.
func (d *IL0373Full) ReadLowPower() (bool, error) {
	b, err := d.read(il0373CmdLPD, 1)
	if err != nil {
		return false, err
	}
	return b[0]&1 == 0, nil
}

// ReadOTP reads length OTP bytes from address 0 (ROTP; the dummy byte is dropped).
func (d *IL0373Full) ReadOTP(length int) ([]byte, error) {
	if err := d.conn.SendCommand(il0373CmdROTP, nil); err != nil {
		return nil, err
	}
	if _, err := d.conn.ReadData(1); err != nil {
		return nil, err
	}
	return d.conn.ReadData(length)
}
