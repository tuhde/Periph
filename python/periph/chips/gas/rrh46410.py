"""RRH46410 digital gas sensor module for indoor air quality (Renesas).

The RRH46410 combines a ZMOD4410 metal-oxide sensing element with an ASIC
that runs the Renesas algorithms on-chip. The host talks to it with a
request/response command protocol over I²C (address ``0x38``):

- request: one write ``[command, params..., checksum]``,
- response: one separate read ``[status, params..., checksum]``,

where ``checksum = ~(sum of the other bytes) & 0xFF``. The module stretches
the I²C clock; the bus master must support clock stretching.
"""

import time


def _now():
    """Monotonic time in seconds (millisecond resolution on MicroPython)."""
    if hasattr(time, 'ticks_ms'):
        return time.ticks_ms() / 1000.0
    return time.time()


def _delay_ms(ms):
    """Sleep for `ms` milliseconds."""
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(ms)
    else:
        time.sleep(ms / 1000.0)


class RRH46410Error(OSError):
    """RRH46410 protocol or status error.

    Attributes:
        status: The status byte of the failing response, or ``None`` for a
            checksum / timeout / identity error.
    """

    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


def _checksum(data):
    return (~sum(data)) & 0xFF


class RRH46410Minimal:
    """RRH46410 indoor air quality sensor — minimal interface.

    One call returns the IAQ index, TVOC, ethanol equivalent, estimated CO₂
    and relative IAQ of the IAQ 2nd Gen algorithm.

    Default configuration (written by :meth:`init`):
        - operation mode IAQ 2nd Gen (``0x01``): one sample every 3 s
        - ambient humidity 50 %RH (module default, not written)
        - GPIOs unused

    Results are fixed during the warm-up of 100 samples (about 5 minutes);
    ``warmed_up`` in the result tells when they are valid.

    Args:
        connection: Plain I²C ``Connection`` bound to address 0x38.
    """

    CMD_GET_PRODUCT_ID = 0x00
    CMD_GET_TRACKING_NUMBER = 0x01
    CMD_GET_OPERATION_MODE = 0x10
    CMD_SET_OPERATION_MODE = 0x11
    CMD_SET_HUMIDITY = 0x12
    CMD_GET_MEASUREMENT_RESULTS = 0x18
    CMD_CONFIG_GPIO = 0x20
    CMD_GET_GPIO = 0x21
    CMD_SET_GPIO = 0x22
    CMD_CLEAR_GPIO = 0x23
    CMD_RESET = 0x8F

    MODE_SUSPEND = 0x00
    MODE_IAQ_2ND_GEN = 0x01
    MODE_ULP = 0x02
    MODE_PBAQ = 0x05
    MODE_CLEANING = 0x80

    STATUS_INVALID_COMMAND = 0x80
    STATUS_CHECKSUM_ERROR = 0x40
    STATUS_SENSOR_NACK = 0x20
    STATUS_NOT_COMPLETE = 0x10
    STATUS_MODE_ERROR_MASK = 0x0F

    PRODUCT_ID = 0x2310

    _INIT_TIMEOUT = 1.0     # s, wait for the module after power-up
    _POLL_MS = 200          # result polling interval while waiting for a sample

    def __init__(self, connection):
        self._connection = connection
        self._mode = self.MODE_SUSPEND
        self._last_status = 0
        self._last_counter = None
        self._samples = 0
        self._last_result = None
        self._lock = None

    def init(self):
        """Wait for the module, verify the product ID and start IAQ 2nd Gen mode.

        Raises:
            RRH46410Error: If the module does not answer within 1 s or the
                product ID is not 0x2310.
        """
        self._wait_product_id()
        self._set_mode(self.MODE_IAQ_2ND_GEN)

    def read(self):
        """Wait for the next sample and return it.

        Returns:
            dict: ``{iaq, tvoc_mg_m3, etoh_ppm, eco2_ppm, relative_iaq, sample,
            warmed_up}`` — IAQ index (UBA level 1.0–5.0), TVOC in mg/m³, ethanol
            equivalent in ppm, estimated CO₂ in ppm, relative IAQ, the sample
            counter (0–255) and whether the warm-up is over.

        Raises:
            RRH46410Error: If no new sample arrives within the sample period
                plus margin, or the module reports an error.
        """
        if not self._wait_sample(self._period(self._mode) + 2.0):
            raise RRH46410Error('RRH46410 sample timeout')
        return self._last_result

    # --- protocol ------------------------------------------------------------

    def _transact(self, command, params=b'', response_len=0, check=True):
        """Send a request and read its response; returns (status, params)."""
        request = bytes([command]) + bytes(params)
        request += bytes([_checksum(request)])
        lock = self._lock
        if lock is not None:
            lock.acquire()
        try:
            self._connection.write(request)
            response = bytes(self._connection.read(response_len + 2))
        finally:
            if lock is not None:
                lock.release()
        if (sum(response) & 0xFF) != 0xFF:
            raise RRH46410Error('RRH46410 response checksum error (command 0x%02X)' % command)
        status = response[0]
        self._last_status = status
        if check and status:
            raise RRH46410Error('RRH46410 status 0x%02X (command 0x%02X)' % (status, command), status)
        return status, response[1:1 + response_len]

    def _wait_product_id(self):
        deadline = _now() + self._INIT_TIMEOUT
        while True:
            try:
                _, data = self._transact(self.CMD_GET_PRODUCT_ID, b'', 5)
                break
            except OSError:
                if _now() > deadline:
                    raise RRH46410Error('RRH46410 not responding')
                _delay_ms(50)
        pid = (data[0] << 8) | data[1]
        if pid != self.PRODUCT_ID:
            raise RRH46410Error('Unexpected RRH46410 product ID 0x%04X, expected 0x2310' % pid)
        return data

    def _set_mode(self, mode):
        self._transact(self.CMD_SET_OPERATION_MODE, bytes([mode]))
        self._mode = mode
        self._last_counter = None
        self._samples = 0

    @staticmethod
    def _period(mode):
        if mode == RRH46410Minimal.MODE_IAQ_2ND_GEN:
            return 3.0
        if mode == RRH46410Minimal.MODE_ULP:
            return 90.0
        if mode == RRH46410Minimal.MODE_PBAQ:
            return 5.0
        return 0.0

    @staticmethod
    def _warmup(mode):
        if mode == RRH46410Minimal.MODE_IAQ_2ND_GEN:
            return 100
        if mode == RRH46410Minimal.MODE_ULP:
            return 10
        if mode == RRH46410Minimal.MODE_PBAQ:
            return 60
        return 0

    def _fetch(self):
        """Read the raw result; returns the parameter bytes or None before the first sample."""
        n = 5 if self._mode == self.MODE_PBAQ else 9
        status, data = self._transact(self.CMD_GET_MEASUREMENT_RESULTS, b'', n, check=False)
        if status & self.STATUS_NOT_COMPLETE and not status & ~self.STATUS_NOT_COMPLETE & 0xFF:
            return None
        if status:
            raise RRH46410Error('RRH46410 status 0x%02X (command 0x18)' % status, status)
        return data

    def _decode(self, data):
        counter = data[0]
        if self._last_counter is None:
            self._samples = counter
        else:
            self._samples += (counter - self._last_counter) & 0xFF
        self._last_counter = counter
        warmed_up = self._samples >= self._warmup(self._mode)
        if self._mode == self.MODE_PBAQ:
            return {
                'tvoc_mg_m3': ((data[1] << 8) | data[2]) / 1000,
                'etoh_ppm': ((data[3] << 8) | data[4]) / 1000,
                'sample': counter,
                'warmed_up': warmed_up,
            }
        return {
            'iaq': data[1] / 10,
            'tvoc_mg_m3': ((data[2] << 8) | data[3]) / 100,
            'etoh_ppm': ((data[4] << 8) | data[5]) / 100,
            'eco2_ppm': (data[6] << 8) | data[7],
            'relative_iaq': data[8] * 10,
            'sample': counter,
            'warmed_up': warmed_up,
        }

    def _wait_sample(self, timeout):
        """Poll until the sample counter changes; stores the decoded result."""
        deadline = _now() + timeout
        pin = getattr(self._connection, 'int_pin', None)
        last = self._last_counter
        while True:
            if pin is None or pin.is_high():
                data = self._fetch()
                if data is not None and (last is None or data[0] != last):
                    self._last_result = self._decode(data)
                    return True
            if _now() > deadline:
                return False
            _delay_ms(self._POLL_MS)


class RRH46410Full(RRH46410Minimal):
    """RRH46410 full interface — extends RRH46410Minimal with identity, operation
    modes (IAQ 2nd Gen, ULP, PBAQ, cleaning), humidity compensation, GPIOs,
    status, reset, raw commands and the INT interrupt.

    ``INT`` is active high, push-pull: high when a new result is available,
    low after ``Get Measurement Results`` or ``Set Operation Mode``.

    Args:
        connection: Plain I²C ``Connection`` bound to address 0x38.
    """

    _CLEAN_TIMEOUT = 90.0   # s; cleaning takes one minute

    def __init__(self, connection):
        super().__init__(connection)
        self._int_callback = None
        self._int_pin = None
        self._int_handler = None
        self._poll_stop = None

    # --- identity --------------------------------------------------------------

    def product_id(self):
        """Read the product ID and firmware version.

        Returns:
            tuple: ``(product_id, (major, minor, patch))`` — product ID 0x2310.
        """
        _, data = self._transact(self.CMD_GET_PRODUCT_ID, b'', 5)
        return (data[0] << 8) | data[1], (data[2], data[3], data[4])

    def tracking_number(self):
        """Read the ASIC tracking number.

        Returns:
            bytes: 6 bytes.
        """
        return bytes(self._transact(self.CMD_GET_TRACKING_NUMBER, b'', 6)[1])

    # --- operation mode ----------------------------------------------------------

    def get_mode(self):
        """Read the current operation mode.

        Returns:
            int: One of the MODE_* constants (other values behave as SUSPEND).
        """
        mode = self._transact(self.CMD_GET_OPERATION_MODE, b'', 1)[1][0]
        self._mode = mode
        return mode

    def set_mode(self, mode):
        """Change the operation mode.

        Stops a running measurement, restarts the warm-up and sets INT low.
        Use :meth:`clean_sensor` for the cleaning mode.

        Args:
            mode: MODE_SUSPEND, MODE_IAQ_2ND_GEN, MODE_ULP or MODE_PBAQ.

        Raises:
            ValueError: If mode is not one of these.
        """
        if mode not in (self.MODE_SUSPEND, self.MODE_IAQ_2ND_GEN, self.MODE_ULP, self.MODE_PBAQ):
            raise ValueError('mode must be MODE_SUSPEND, MODE_IAQ_2ND_GEN, MODE_ULP or MODE_PBAQ')
        self._set_mode(mode)

    def suspend(self):
        """Stop measuring (operation mode SUSPEND, about 0.7 µA)."""
        self._set_mode(self.MODE_SUSPEND)

    # --- measurement ---------------------------------------------------------------

    def read_results(self):
        """Read and decode the latest result without waiting.

        Returns:
            dict: The decoded result for the current mode (PBAQ has only
            ``tvoc_mg_m3``, ``etoh_ppm``, ``sample`` and ``warmed_up``), or
            ``None`` before the first sample of the mode exists.
        """
        data = self._fetch()
        if data is None:
            return None
        self._last_result = self._decode(data)
        return self._last_result

    def wait_for_sample(self, timeout):
        """Wait until the sample counter changes (or INT goes high).

        Args:
            timeout: Maximum wait in seconds.

        Returns:
            bool: True when a new sample arrived (it is then the result of
            :meth:`read_results`'s next call).
        """
        return self._wait_sample(timeout)

    def set_humidity(self, percent):
        """Supply the ambient relative humidity for compensation.

        Args:
            percent: Relative humidity in % (0–100); sent as
                ``round(percent / 100 * 255)``.

        Raises:
            ValueError: If percent is not 0–100.
        """
        if not 0 <= percent <= 100:
            raise ValueError('percent must be 0..100')
        self._transact(self.CMD_SET_HUMIDITY, bytes([int(round(percent / 100 * 255))]))

    def sample_period(self, mode=None):
        """Return the sample period of a mode.

        Args:
            mode: Operation mode, or None for the current mode.

        Returns:
            float: Seconds: 3 (IAQ 2nd Gen), 90 (ULP), 5 (PBAQ), 0 otherwise.
        """
        return self._period(self._mode if mode is None else mode)

    def warmup_samples(self, mode=None):
        """Return the number of warm-up samples of a mode.

        Args:
            mode: Operation mode, or None for the current mode.

        Returns:
            int: 100 (IAQ 2nd Gen), 10 (ULP), 60 (PBAQ), 0 otherwise.
        """
        return self._warmup(self._mode if mode is None else mode)

    @staticmethod
    def air_quality_label(iaq):
        """Return the UBA rating text for an IAQ index.

        Args:
            iaq: IAQ index (1.0–5.0).

        Returns:
            str: "very good" (< 2.0), "good" (< 3.0), "medium" (< 4.0),
            "poor" (< 5.0) or "bad" (≥ 5.0).
        """
        if iaq < 2.0:
            return 'very good'
        if iaq < 3.0:
            return 'good'
        if iaq < 4.0:
            return 'medium'
        if iaq < 5.0:
            return 'poor'
        return 'bad'

    def clean_sensor(self, confirm=False):
        """Run the one-time 1-minute sensor cleaning and wait for it to finish.

        Execute it at most once in the sensor's lifetime and do not interrupt
        it (no power loss). A repeated start returns straight to SUSPEND.

        Args:
            confirm: Must be True; guards against accidental use.

        Raises:
            ValueError: If confirm is not True.
            RRH46410Error: If the module does not return to SUSPEND within 90 s.
        """
        if confirm is not True:
            raise ValueError('clean_sensor runs once per sensor lifetime; pass confirm=True')
        self._set_mode(self.MODE_CLEANING)
        deadline = _now() + self._CLEAN_TIMEOUT
        while self.get_mode() == self.MODE_CLEANING:
            if _now() > deadline:
                raise RRH46410Error('RRH46410 cleaning did not finish')
            _delay_ms(1000)
        self._mode = self.MODE_SUSPEND

    # --- GPIO --------------------------------------------------------------------------

    def config_gpio(self, directions):
        """Configure GPIO1/GPIO2 as outputs.

        Args:
            directions: Bit 0 = GPIO1 output, bit 1 = GPIO2 output (0–3).
        """
        self._transact(self.CMD_CONFIG_GPIO, bytes([self._mask(directions)]))

    def get_gpio(self):
        """Read the GPIO levels.

        Returns:
            int: Bits 0–3 = GPIO1–GPIO4.
        """
        return self._transact(self.CMD_GET_GPIO, b'', 1)[1][0]

    def set_gpio(self, mask):
        """Drive GPIO outputs high.

        Args:
            mask: Bit 0 = GPIO1, bit 1 = GPIO2 (0–3).
        """
        self._transact(self.CMD_SET_GPIO, bytes([self._mask(mask)]))

    def clear_gpio(self, mask):
        """Drive GPIO outputs low.

        Args:
            mask: Bit 0 = GPIO1, bit 1 = GPIO2 (0–3).
        """
        self._transact(self.CMD_CLEAR_GPIO, bytes([self._mask(mask)]))

    @staticmethod
    def _mask(value):
        if not 0 <= value <= 3:
            raise ValueError('GPIO mask must be 0..3')
        return value

    # --- status, reset, raw ------------------------------------------------------------

    def status(self):
        """Decode the status byte of the last response.

        Returns:
            dict: ``{raw, invalid_command, checksum_error, sensor_nack,
            not_complete, mode_error}`` — ``mode_error`` is the bits 3:0 code
            (0 OK, 0xA sensor damaged).
        """
        s = self._last_status
        return {
            'raw': s,
            'invalid_command': bool(s & self.STATUS_INVALID_COMMAND),
            'checksum_error': bool(s & self.STATUS_CHECKSUM_ERROR),
            'sensor_nack': bool(s & self.STATUS_SENSOR_NACK),
            'not_complete': bool(s & self.STATUS_NOT_COMPLETE),
            'mode_error': s & self.STATUS_MODE_ERROR_MASK,
        }

    def reset(self):
        """Reset the module, wait 500 ms and verify the product ID again.

        The module restarts in SUSPEND mode.
        """
        request = bytes([self.CMD_RESET, _checksum(bytes([self.CMD_RESET]))])
        try:
            self._connection.write(request)
        except OSError:
            pass  # the module may not acknowledge while resetting
        _delay_ms(500)
        self._wait_product_id()
        self._mode = self.MODE_SUSPEND
        self._last_counter = None
        self._samples = 0

    def send_command(self, command, params=b'', response_len=0):
        """Send a raw request and return the response parameters.

        The checksums are added and verified; the status byte is available
        from :meth:`status`. The Reset command (0x8F) has no response.

        Args:
            command: Command byte.
            params: Request parameter bytes.
            response_len: Number of response parameter bytes.

        Returns:
            bytes: The response parameters (empty for Reset).

        Raises:
            RRH46410Error: On a response checksum error.
        """
        if command == self.CMD_RESET:
            request = bytes([command]) + bytes(params)
            self._connection.write(request + bytes([_checksum(request)]))
            return b''
        return bytes(self._transact(command, params, response_len, check=False)[1])

    # --- interrupt -----------------------------------------------------------------------

    def poll_interrupt(self):
        """Check for a new sample without consuming it.

        Returns:
            bool: True if the INT pin is high (pin available), otherwise if the
            sample counter changed since the last decoded result.
        """
        pin = getattr(self._connection, 'int_pin', None)
        if pin is not None:
            return bool(pin.is_high())
        data = self._fetch()
        return data is not None and data[0] != self._last_counter

    def _dispatch_int(self):
        callback = self._int_callback
        if callback is None:
            return
        try:
            data = self._fetch()
        except OSError:
            return
        if data is None or data[0] == self._last_counter:
            return
        result = self._decode(data)
        self._last_result = result
        callback(result)

    def on_interrupt(self, callback, int_pin=None):
        """Call ``callback(result)`` for every new sample.

        Uses ``int_pin`` (or the connection's ``int_pin``) on its rising edge
        when present; otherwise a 200 ms polling thread comparing the sample
        counter. The driver reads the result (which sets INT low) and passes the
        decoded dict.

        Args:
            callback: Function called with the decoded result dict.
            int_pin: Optional InputPin wired to INT.
        """
        self.off_interrupt()
        self._int_callback = callback
        pin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        if pin is not None and hasattr(pin, 'on_edge'):
            self._int_pin = pin
            self._int_handler = lambda *a: self._dispatch_int()
            pin.on_edge(self._int_handler, getattr(pin, 'RISING', 1))
        else:
            self._start_polling()

    def _start_polling(self):
        try:
            import _thread
        except ImportError:
            return
        if self._lock is None:
            self._lock = _thread.allocate_lock()
        stop = [False]
        self._poll_stop = stop

        def _loop():
            while not stop[0]:
                self._dispatch_int()
                _delay_ms(self._POLL_MS)

        _thread.start_new_thread(_loop, ())

    def off_interrupt(self):
        """Unsubscribe from the interrupt."""
        self._int_callback = None
        pin = self._int_pin
        handler = self._int_handler
        self._int_pin = None
        self._int_handler = None
        if pin is not None and handler is not None and hasattr(pin, 'off_edge'):
            pin.off_edge(handler)
        if self._poll_stop is not None:
            self._poll_stop[0] = True
            self._poll_stop = None
