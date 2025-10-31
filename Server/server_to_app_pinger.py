#!/usr/bin/env python3
import socket
import json
import time
import threading
import logging

# Logging setup
logging.basicConfig(
    level=logging.INFO,
    format='[%(asctime)s] %(levelname)s - %(message)s',
    datefmt='%H:%M:%S'
)
logger = logging.getLogger(__name__)

PING_INTERVAL = 1          # seconds between pings
SESSION_DURATION = 30      # total seconds per client
SERVER_PORT = 50003
BUFFER_SIZE = 4096
PING_TIMEOUT_MS = 5000     # 🔹 consider ping lost after 5 seconds


class UDPPingServer:
    def __init__(self, port=SERVER_PORT):
        self.port = port
        self.running = False
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

        # 🔹 Key: (client_addr, seq) → send_time_ms
        self.ping_times = {}
        self.lock = threading.Lock()

    def start(self):
        """Start UDP listener."""
        self.socket.bind(("0.0.0.0", self.port))
        self.running = True

        # 🔹 Start listener and cleanup threads
        threading.Thread(target=self.listen, daemon=True).start()
        threading.Thread(target=self.cleanup_timeouts, daemon=True).start()

        logger.info(f"Server started on UDP port {self.port}")

    def stop(self):
        """Stop server."""
        self.running = False
        self.socket.close()
        logger.info("Server stopped")

    def listen(self):
        """Main listener loop."""
        while self.running:
            try:
                data, addr = self.socket.recvfrom(BUFFER_SIZE)
                msg = json.loads(data.decode())
                msg_type = msg.get("message")

                if msg_type == "READY_FOR_PINGS":
                    self.handle_ready(msg, addr)

                elif msg_type == "ACK":
                    seq = msg.get("sequence")
                    key = (addr, seq)

                    # 🔹 Compute RTT and delete key
                    with self.lock:
                        if key in self.ping_times:
                            send_time = self.ping_times.pop(key)
                            rtt = int(time.time() * 1000) - send_time
                            logger.info(f"ACK from {addr} seq={seq} RTT={rtt}ms")
                        else:
                            logger.warning(f"ACK from {addr} seq={seq} (no matching ping)")

            except Exception as e:
                logger.error(f"Error: {e}")

    def handle_ready(self, msg, addr):
        """Handle client readiness."""
        client_id = msg.get("client_id", "unknown")
        logger.info(f"READY from {client_id} at {addr}")

        thread = threading.Thread(target=self.ping_client, args=(client_id, addr), daemon=True)
        thread.start()

    def ping_client(self, client_id, client_addr):
        """Send pings every 1s for 30s."""
        logger.info(f"Starting ping session for {client_id} (30s, 1s interval)")
        start_time = time.time()
        seq = 0

        while time.time() - start_time < SESSION_DURATION and self.running:
            seq += 1
            ping_msg = json.dumps({
                "message": "PING",
                "sequence": seq
            })

            send_time = int(time.time() * 1000)
            with self.lock:
                self.ping_times[(client_addr, seq)] = send_time

            self.socket.sendto(ping_msg.encode(), client_addr)
            logger.info(f"PING → {client_id} seq={seq}")

            time.sleep(PING_INTERVAL)

        # 🔹 Clean up any leftover keys for this client
        with self.lock:
            before = len(self.ping_times)
            self.ping_times = {k: v for k, v in self.ping_times.items() if k[0] != client_addr}
            after = len(self.ping_times)
        logger.info(f"Session complete for {client_id} (removed {before - after} pending pings)")

    def cleanup_timeouts(self):
        """Periodically remove timed-out ping entries."""
        while self.running:
            time.sleep(1)
            now = int(time.time() * 1000)
            with self.lock:
                expired = [k for k, t in self.ping_times.items() if now - t > PING_TIMEOUT_MS]
                for k in expired:
                    self.ping_times.pop(k, None)
                if expired:
                    logger.warning(f"Removed {len(expired)} timed-out pings")



def main():
    server = UDPPingServer()
    try:
        server.start()
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        server.stop()


if __name__ == "__main__":
    main()
