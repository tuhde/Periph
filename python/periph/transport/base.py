class Transport:
    def write(self, data):
        raise NotImplementedError

    def read(self, n):
        raise NotImplementedError

    def write_read(self, data, n):
        raise NotImplementedError
