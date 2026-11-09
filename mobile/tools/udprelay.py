# Host-side relay for testing live view on the emulator against the REAL camera:
# the camera sends UDP to the host's LAN address (the emulator's NAT source), the emulator's `redir add udp:49199:49199`
# listens on host loopback only, so copy datagrams from <lan-ip>:49199 to 127.0.0.1:49199.   udprelay.py 10.0.0.129 [port]
import socket, sys, time
lan = sys.argv[1]; port = int(sys.argv[2]) if len(sys.argv) > 2 else 49199
rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); rx.bind((lan, port))
tx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
n = 0; t0 = time.time()
while True:
    d, a = rx.recvfrom(65535)
    n += 1
    tx.sendto(d, ("127.0.0.1", port))
    if n == 1 or n % 100 == 0: print("relay", n, "datagrams, last", len(d), "bytes from", a, flush=True)
