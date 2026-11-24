"""Send the transport stream over UDP, in the shape a player expects.

`a TV front end` (the studio's BML test hardware) opens a live stream as
`udp://@:1234` and documents **7 packets per datagram** — 7 x 188 = 1316 bytes —
which is also what the IT9308 CLI sends. This module is that sink.

    sender = UdpSender("127.0.0.1", 1234)
    sender.send(chunk)          # any split; datagrams are re-cut to 1316 bytes
    sender.close()

Multicast works the same way: give a group address as the host.
"""
import socket

PACKET = 188
PACKETS_PER_DATAGRAM = 7
DATAGRAM = PACKET * PACKETS_PER_DATAGRAM


class UdpSender:
    def __init__(self, host, port, packets_per_datagram=PACKETS_PER_DATAGRAM,
                 ttl=None):
        self.host = host
        self.port = port
        self.unit = PACKET * packets_per_datagram
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        if ttl is not None:
            self.socket.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, ttl)
        self.address = (socket.gethostbyname(host), port)
        self.sent = 0
        self.datagrams = 0
        self._buffer = b""

    def send(self, data):
        """Queue bytes; whole datagrams go out immediately, the tail waits."""
        self._buffer += data
        while len(self._buffer) >= self.unit:
            self.socket.sendto(self._buffer[:self.unit], self.address)
            self._buffer = self._buffer[self.unit:]
            self.datagrams += 1
            self.sent += self.unit

    def flush(self):
        """Send whatever is left, padded to a full datagram with null packets."""
        if not self._buffer:
            return
        tail = self._buffer
        self._buffer = b""
        padding = self.unit - len(tail)
        if padding:
            tail += b"\xff" * padding
        self.socket.sendto(tail, self.address)
        self.datagrams += 1
        self.sent += len(tail)

    def close(self):
        self.flush()
        try:
            self.socket.close()
        except OSError:
            pass


def parse_target(text):
    """``host:port`` or ``udp://host:port`` (also ``udp://@:port``) -> (host, port)."""
    value = text.strip()
    if value.lower().startswith("udp://"):
        value = value[6:]
    if value.startswith("@"):
        value = value[1:] or "127.0.0.1"
    if ":" not in value:
        raise ValueError("expected host:port, got %r" % text)
    host, _, port = value.rpartition(":")
    host = host or "127.0.0.1"
    return host, int(port)
