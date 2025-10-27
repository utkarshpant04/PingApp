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


class UDPPingServer:
    def __init__(self, port=SERVER_PORT):
        self.port = port
        self.running = False
        self.clients = {}
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    def start(self):
        """Start UDP listener."""
        self.socket.bind(("0.0.0.0", self.port))
        self.running = True
        threading.Thread(target=self.listen, daemon=True).start()
        print(f"Server started on UDP port {self.port}")

    def stop(self):
        """Stop server."""
        self.running = False
        self.socket.close()
        print("Server stopped")

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
                    rtt = int(time.time() * 1000) - msg.get("sent_timestamp", 0)
                    logger.info(f"ACK from {addr} seq={seq} RTT={rtt}ms")
                else:
                    #logger.warning(f"Unknown message from {addr}: {msg_type}")
            except Exception as e:
                logger.error(f"Error: {e}")

    def handle_ready(self, msg, addr):
        """Handle client readiness."""
        client_id = msg.get("client_id", "unknown")
        # Don't use listener_port from message - use the actual source address!
        client_addr = addr  # ← Use the address the packet came FROM
        #logger.info(f"READY from {client_id} at {client_addr}")

        thread = threading.Thread(target=self.ping_client, args=(client_id, client_addr), daemon=True)
        thread.start()

    def ping_client(self, client_id, client_addr):
        """Send pings every 1s for 30s."""
        logger.info(f"Starting ping session for {client_id} (30s, 1s interval)")
        start_time = time.time()
        seq = 0

        while time.time() - start_time < SESSION_DURATION and self.running:
            seq += 1
            timestamp = int(time.time() * 1000)
            ping_msg = json.dumps({
                "message": "PING",
                "sequence": seq,
                "sent_timestamp": timestamp
            })
            self.socket.sendto(ping_msg.encode(), client_addr)
            #logger.info(f"PING → {client_id} seq={seq}")
            time.sleep(PING_INTERVAL)

        print(f"Session complete for {client_id}")


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