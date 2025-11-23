#!/usr/bin/env python3
import socket
import json
import time
import threading
import logging
import sqlite3
import uuid
import datetime
import sys

# Logging setup
logging.basicConfig(
    level=logging.INFO,
    format='[%(asctime)s] %(levelname)s - %(message)s',
    datefmt='%H:%M:%S'
)
logger = logging.getLogger(__name__)

DB_NAME = 'ping_data2.db'
PING_INTERVAL = 0.05        # seconds between pings
SESSION_DURATION = 15      # total seconds per client
SERVER_PORT = 50003
BUFFER_SIZE = 4096
PING_TIMEOUT_MS = 5000     # 🔹 consider ping lost after 5 seconds


class UDPPingServer:
    def __init__(self, port=SERVER_PORT):
        self.port = port
        self.running = False
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

        # 🔹 Key: (client_addr, seq) → (send_time_ms, session_id)
        self.ping_times = {}
        # 🔹 Key: session_id → {"packets_received": int, "results": {seq: {...}}}
        self.session_stats = {}
        # 🔹 Separate locks for in-memory state and DB writes
        self.state_lock = threading.Lock()
        self.db_lock = threading.Lock()

        # 🔹 Initialize and connect to the database
        try:
            self.conn = sqlite3.connect(DB_NAME, check_same_thread=False)
            self.init_tables()
        except sqlite3.Error as e:
            logger.error(f"FATAL: Database connection failed: {e}")
            sys.exit(1)

    def init_tables(self):
        """Create database tables if they don't exist."""
        with self.db_lock:
            try:
                cursor = self.conn.cursor()
                # Table 1: Session Summary
                cursor.execute('''
                    CREATE TABLE IF NOT EXISTS ping_sessions_server (
                        session_id TEXT PRIMARY KEY,
                        client_id TEXT,
                        start_time TEXT,
                        end_time TEXT,
                        duration_seconds INTEGER,
                        packets_sent INTEGER,
                        packets_received INTEGER,
                        total_bytes BIGINT
                    )
                ''')
                # Table 2: Individual Ping Results
                cursor.execute('''
                    CREATE TABLE IF NOT EXISTS ping_results_server (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        session_id TEXT,
                        timestamp TEXT,
                        sequence_number INTEGER,
                        success INTEGER,  -- 0=false, 1=true
                        sent_timestamp_ms BIGINT,
                        received_timestamp_ms BIGINT, -- NULL if success=0
                        FOREIGN KEY (session_id) REFERENCES ping_sessions_server (session_id)
                    )
                ''')
                self.conn.commit()
                logger.info(f"Database '{DB_NAME}' tables initialized.")
            except sqlite3.Error as e:
                logger.error(f"Failed to initialize tables: {e}")
                self.conn.rollback()

    def start(self):
        """Start UDP listener."""
        self.socket.bind(("0.0.0.0", self.port))
        self.running = True

        # 🔹 Start listener and cleanup threads
        threading.Thread(target=self.listen, daemon=True).start()
        threading.Thread(target=self.cleanup_timeouts, daemon=True).start()

        logger.info(f"Server started on UDP port {self.port}")

    def stop(self):
        """Stop server and close DB connection."""
        self.running = False
        self.socket.close()
        if self.conn:
            self.conn.close()
        logger.info("Server stopped, DB connection closed")

    def listen(self):
        """Main listener loop."""
        while self.running:
            try:
                data, addr = self.socket.recvfrom(BUFFER_SIZE)
                received_time_ms = int(time.time() * 1000) # 🔹 Capture time immediately
                msg = json.loads(data.decode())
                msg_type = msg.get("message")

                if msg_type == "READY_FOR_PINGS":
                    self.handle_ready(msg, addr)

                elif msg_type == "ACK":
                    seq = msg.get("sequence")
                    key = (addr, seq)

                    send_time_ms, session_id = None, None
                    with self.state_lock:
                        if key in self.ping_times:
                            # Pop the ping info
                            send_time_ms, session_id = self.ping_times.pop(key)

                            # 🔹 Increment session ACK count and update in-memory result
                            if session_id in self.session_stats:
                                self.session_stats[session_id]["packets_received"] += 1
                                if seq in self.session_stats[session_id]["results"]:
                                    self.session_stats[session_id]["results"][seq]["rcvd_ms"] = received_time_ms

                    if send_time_ms and session_id:
                        rtt = received_time_ms - send_time_ms
                        logger.info(f"ACK from {addr} seq={seq} RTT={rtt}ms (Session: {session_id[:8]}...)")

                        # 🔹 DB LOGGING REMOVED: No more real-time DB updates
                    else:
                        logger.warning(f"Late/Dupe ACK from {addr} seq={seq} (no matching ping)")

            except Exception as e:
                logger.error(f"Error in listen(): {e}")

    def handle_ready(self, msg, addr):
        """Handle client readiness."""
        client_id = msg.get("client_id", "unknown")
        # 🔹 CHANGE: Get session_id from client message
        session_id = msg.get("session_id")

        if not session_id:
            logger.error(f"READY from {client_id} at {addr} missing 'session_id'. Ignoring.")
            return

        logger.info(f"READY from {client_id} at {addr}. Starting session: {session_id}")

        # 🔹 Initialize stats and results buffer for this session
        if session_id in self.session_stats:
            return  # Session already exists, ignore duplicate READY

        with self.state_lock:
            self.session_stats[session_id] = {"packets_received": 0, "results": {}}

        thread = threading.Thread(target=self.ping_client, args=(client_id, addr, session_id), daemon=True)
        thread.start()

    def ping_client(self, client_id, client_addr, session_id):
        """Send pings every 1s for 30s and log session."""
        logger.info(f"Starting ping session {session_id[:8]}... for {client_id}")

        start_time_obj = time.time()
        start_time_iso = datetime.datetime.now().isoformat()
        seq = 0
        total_bytes_sent = 0

        while time.time() - start_time_obj < SESSION_DURATION and self.running:
            seq += 1
            ping_msg = json.dumps({
                "message": "PING",
                "sequence": seq
            })
            msg_bytes = ping_msg.encode()
            send_time_ms = int(time.time() * 1000)
            current_time_iso = datetime.datetime.now().isoformat()

            with self.state_lock:
                # Store for ACK matching
                self.ping_times[(client_addr, seq)] = (send_time_ms, session_id)
                # 🔹 Store in-memory buffer for final batch write
                if session_id in self.session_stats:
                    self.session_stats[session_id]["results"][seq] = {
                        "sent_ms": send_time_ms,
                        "rcvd_ms": None,
                        "iso_time": current_time_iso
                    }

            # 🔹 DB LOGGING REMOVED: No call to log_ping_send()

            self.socket.sendto(msg_bytes, client_addr)
            total_bytes_sent += len(msg_bytes)
            logger.info(f"PING → {client_id} seq={seq} (Session: {session_id[:8]}...)")

            time.sleep(PING_INTERVAL)

        # 🔹 Session sending is over
        logger.info(f"Session {session_id[:8]}... sending complete for {client_id}.")
        logger.info(f"  > Waiting {PING_TIMEOUT_MS}ms for final ACKs...")
        # 🔹 Wait for straggling ACKs to be processed by the listen thread
        time.sleep(PING_TIMEOUT_MS / 1000.0)

        end_time_iso = datetime.datetime.now().isoformat()
        duration = int(time.time() - start_time_obj)
        packets_sent = seq

        # 🔹 Safely get final stats and clean up state
        with self.state_lock:
            stats = self.session_stats.pop(session_id, {"packets_received": 0, "results": {}})
            packets_received = stats["packets_received"]
            ping_results_data = stats["results"]

            # Clean up any leftover keys from the ACK-matching map
            before = len(self.ping_times)
            self.ping_times = {k: v for k, v in self.ping_times.items() if k[0] != client_addr}
            after = len(self.ping_times)

        logger.info(f"Session {session_id[:8]}... logging complete for {client_id}")
        logger.info(f"  > Removed {before - after} pending pings from ACK map")

        # 🔹 Step 1 (as requested): Log the session summary first
        self.log_session_summary(session_id, client_id, start_time_iso, end_time_iso,
                                 duration, packets_sent, packets_received, total_bytes_sent)

        # 🔹 Step 2: Bulk-log all individual ping results
        self.log_ping_results_batch(session_id, ping_results_data)


    def cleanup_timeouts(self):
        """Periodically remove timed-out ping entries from in-memory ACK map."""
        while self.running:
            time.sleep(1)
            now = int(time.time() * 1000)

            with self.state_lock:
                # Find keys where send_time < (now - timeout)
                expired = [k for k, (t, s) in self.ping_times.items() if now - t > PING_TIMEOUT_MS]

                # Remove them
                for k in expired:
                    self.ping_times.pop(k, None)

                if expired:
                    # This just cleans the ACK map, it doesn't affect DB logging
                    logger.warning(f"Removed {len(expired)} timed-out pings from ACK map")

    # --- Database Helper Methods ---

    def log_session_summary(self, session_id, client_id, start, end, duration, sent, rcvd, bytes_sent):
        """Logs the entire session summary to the sessions table."""
        sql = """
            INSERT INTO ping_sessions_server
                (session_id, client_id, start_time, end_time, duration_seconds, packets_sent, packets_received, total_bytes)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """
        params = (session_id, client_id, start, end, duration, sent, rcvd, bytes_sent)
        with self.db_lock:
            try:
                self.conn.execute(sql, params)
                self.conn.commit()
                logger.info(f"DB: Logged summary for session {session_id[:8]}...")
            except sqlite3.Error as e:
                logger.error(f"DBError on log_session_summary: {e}")
                self.conn.rollback()

    def log_ping_results_batch(self, session_id, results_dict):
        """Bulk-inserts all ping results for a session using executemany."""
        batch_data = []
        for seq, data in results_dict.items():
            sent_ms = data["sent_ms"]
            rcvd_ms = data["rcvd_ms"]
            iso_time = data["iso_time"] # This is the "send" timestamp
            success = 1 if rcvd_ms is not None else 0

            # (session_id, timestamp, seq, success, sent_ms, rcvd_ms)
            batch_data.append(
                (session_id, iso_time, seq, success, sent_ms, rcvd_ms)
            )

        if not batch_data:
            logger.warning(f"No ping results to log for session {session_id[:8]}...")
            return

        sql = """
            INSERT INTO ping_results_server
                (session_id, timestamp, sequence_number, success, sent_timestamp_ms, received_timestamp_ms)
            VALUES (?, ?, ?, ?, ?, ?)
        """
        with self.db_lock:
            try:
                self.conn.executemany(sql, batch_data)
                self.conn.commit()
                logger.info(f"DB: Logged {len(batch_data)} ping results for session {session_id[:8]}...")
            except sqlite3.Error as e:
                logger.error(f"DBError on log_ping_results_batch: {e}")
                self.conn.rollback()


def main():
    server = UDPPingServer()
    try:
        server.start()
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        logger.info("CTRL+C detected. Shutting down...")
    finally:
        server.stop()


if __name__ == "__main__":
    main()