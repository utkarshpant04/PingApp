#!/usr/bin/env python3
"""
REST API Server for Ping App with Simple Always-Send Instructions
Sends ping instructions on every heartbeat request
"""
prob = 0.9
from http.server import HTTPServer, BaseHTTPRequestHandler
import json
import logging
import sqlite3
from datetime import datetime
import urllib.parse
import os
import threading
import random

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

class PingDataServer:
    def __init__(self, db_path="ping_data.db"):
        self.db_path = db_path
        self.lock = threading.Lock()
        self.ping_instructions = self.load_default_ping_instructions()
        self.init_database()

    def load_default_ping_instructions(self):
        return [
            {"host": "google.com", "protocol": "TCP", "duration_seconds": 45, "interval_ms": 500},
        ]

    def init_database(self):
        """Initialize SQLite database"""
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()

            # Table for client/device information
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS clients (
                    client_id TEXT PRIMARY KEY,
                    device_id TEXT,
                    device_model TEXT,
                    android_version TEXT,
                    app_version TEXT,
                    first_seen TEXT,
                    last_seen TEXT,
                    last_location TEXT,
                    total_sessions INTEGER DEFAULT 0
                )
            ''')

            # Table for ping sessions
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS ping_sessions (
                    session_id TEXT PRIMARY KEY,
                    client_id TEXT,
                    host TEXT,
                    protocol TEXT,
                    start_time TEXT,
                    end_time TEXT,
                    duration_seconds INTEGER,
                    packets_sent INTEGER,
                    packets_received INTEGER,
                    packet_loss_percent REAL,
                    avg_rtt_ms REAL,
                    min_rtt_ms REAL,
                    max_rtt_ms REAL,
                    total_bytes BIGINT,
                    avg_bandwidth_bps REAL,
                    start_location TEXT,
                    end_location TEXT,
                    settings_json TEXT,
                    FOREIGN KEY (client_id) REFERENCES clients (client_id)
                )
            ''')

            # Table for individual ping results
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS ping_results (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT,
                    timestamp TEXT,
                    sequence_number INTEGER,
                    success BOOLEAN,
                    rtt_ms REAL,
                    location TEXT,
                    error_message TEXT,
                    FOREIGN KEY (session_id) REFERENCES ping_sessions (session_id)
                )
            ''')

            # Table for heartbeat logs
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS heartbeats (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    client_id TEXT,
                    device_id TEXT,
                    app_status TEXT,
                    location TEXT,
                    timestamp TEXT,
                    instruction_sent TEXT,
                    FOREIGN KEY (client_id) REFERENCES clients (client_id)
                )
            ''')

            conn.commit()
            conn.close()
            logger.info("Database initialized successfully")

        except Exception as e:
            logger.error(f"Database initialization error: {e}")

    def register_or_update_client(self, device_info):
        """Register new client or update existing one"""
        with self.lock:
            try:
                conn = sqlite3.connect(self.db_path)
                cursor = conn.cursor()

                client_id = f"{device_info['device_id']}_{device_info.get('device_model', '').replace(' ', '_')}"
                current_time = datetime.now().isoformat()
                location = device_info.get('location', 'N/A')

                # Check if client exists
                cursor.execute('SELECT client_id FROM clients WHERE client_id = ?', (client_id,))
                result = cursor.fetchone()

                if result:
                    # Update existing client
                    cursor.execute('''
                        UPDATE clients SET
                        last_seen = ?,
                        device_model = ?,
                        android_version = ?,
                        app_version = ?,
                        last_location = ?
                        WHERE client_id = ?
                    ''', (current_time, device_info.get('device_model', ''),
                         device_info.get('android_version', ''),
                         device_info.get('app_version', ''),
                         location, client_id))
                else:
                    # Insert new client
                    cursor.execute('''
                        INSERT INTO clients
                        (client_id, device_id, device_model, android_version, app_version,
                         first_seen, last_seen, last_location, total_sessions)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                    ''', (client_id, device_info['device_id'], device_info.get('device_model', ''),
                         device_info.get('android_version', ''), device_info.get('app_version', ''),
                         current_time, current_time, location))

                conn.commit()
                conn.close()
                return client_id

            except Exception as e:
                logger.error(f"Error registering client: {e}")
                return None

    def get_ping_instruction(self):
        """Get a ping instruction - randomly select from available instructions"""
        if self.ping_instructions:
            return random.choice(self.ping_instructions)
        return None

    def store_heartbeat(self, heartbeat_data):
        """Store heartbeat and always return a ping instruction"""
        with self.lock:
            try:
                conn = sqlite3.connect(self.db_path)
                cursor = conn.cursor()

                # Get ping instruction
                instruction = self.get_ping_instruction()
                instruction_json = json.dumps(instruction) if instruction else ""

                cursor.execute('''
                    INSERT INTO heartbeats
                    (client_id, device_id, app_status, location, timestamp, instruction_sent)
                    VALUES (?, ?, ?, ?, ?, ?)
                ''', (
                    heartbeat_data.get('client_id', ''),
                    heartbeat_data.get('device_id', ''),
                    heartbeat_data.get('app_status', 'unknown'),
                    heartbeat_data.get('location', 'N/A'),
                    datetime.now().isoformat(),
                    instruction_json
                ))

                # Update client's last location
                client_id = heartbeat_data.get('client_id', '')
                if client_id:
                    cursor.execute('''
                        UPDATE clients SET
                        last_seen = ?,
                        last_location = ?
                        WHERE client_id = ?
                    ''', (datetime.now().isoformat(),
                         heartbeat_data.get('location', 'N/A'),
                         client_id))

                conn.commit()
                conn.close()

                return instruction

            except Exception as e:
                logger.error(f"Error storing heartbeat: {e}")
                return None

    def store_ping_session(self, session_data):
        """Store complete ping session data"""
        with self.lock:
            try:
                conn = sqlite3.connect(self.db_path)
                cursor = conn.cursor()

                # Insert session data
                cursor.execute('''
                    INSERT INTO ping_sessions
                    (session_id, client_id, host, protocol, start_time, end_time,
                     duration_seconds, packets_sent, packets_received, packet_loss_percent,
                     avg_rtt_ms, min_rtt_ms, max_rtt_ms, total_bytes, avg_bandwidth_bps,
                     start_location, end_location, settings_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ''', (
                    session_data['session_id'], session_data['client_id'], session_data['host'],
                    session_data['protocol'], session_data['start_time'], session_data['end_time'],
                    session_data['duration_seconds'], session_data['packets_sent'],
                    session_data['packets_received'], session_data['packet_loss_percent'],
                    session_data.get('avg_rtt_ms', 0), session_data.get('min_rtt_ms', 0),
                    session_data.get('max_rtt_ms', 0), session_data.get('total_bytes', 0),
                    session_data.get('avg_bandwidth_bps', 0), session_data.get('start_location', 'N/A'),
                    session_data.get('end_location', 'N/A'), json.dumps(session_data.get('settings', {}))
                ))

                # Update client session count
                cursor.execute('''
                    UPDATE clients SET
                    total_sessions = total_sessions + 1,
                    last_location = ?
                    WHERE client_id = ?
                ''', (session_data.get('end_location', 'N/A'), session_data['client_id']))

                # Store individual ping results if provided
                if 'ping_results' in session_data:
                    for result in session_data['ping_results']:
                        cursor.execute('''
                            INSERT INTO ping_results
                            (session_id, timestamp, sequence_number, success, rtt_ms, location, error_message)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                        ''', (
                            session_data['session_id'], result.get('timestamp', ''),
                            result.get('sequence', 0), result.get('success', False),
                            result.get('rtt_ms', 0), result.get('location', 'N/A'),
                            result.get('error_message', '')
                        ))

                conn.commit()
                conn.close()
                return True

            except Exception as e:
                logger.error(f"Error storing session data: {e}")
                return False

    def get_client_stats(self, client_id=None):
        """Get statistics for specific client or all clients"""
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()

            if client_id:
                cursor.execute('''
                    SELECT c.*, COUNT(s.session_id) as session_count
                    FROM clients c
                    LEFT JOIN ping_sessions s ON c.client_id = s.client_id
                    WHERE c.client_id = ?
                    GROUP BY c.client_id
                ''', (client_id,))
                result = cursor.fetchone()
                if result:
                    columns = [desc[0] for desc in cursor.description]
                    return dict(zip(columns, result))
            else:
                cursor.execute('''
                    SELECT c.*, COUNT(s.session_id) as session_count
                    FROM clients c
                    LEFT JOIN ping_sessions s ON c.client_id = s.client_id
                    GROUP BY c.client_id
                ''')
                results = cursor.fetchall()
                columns = [desc[0] for desc in cursor.description]
                return [dict(zip(columns, row)) for row in results]

            conn.close()
            return None

        except Exception as e:
            logger.error(f"Error getting client stats: {e}")
            return None

# Global database instance
db = PingDataServer()

class PingRestApiHandler(BaseHTTPRequestHandler):

    def do_OPTIONS(self):
        """Handle preflight CORS requests"""
        self.send_response(200)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type, Authorization')
        self.end_headers()

    def do_GET(self):
        """Handle GET requests"""
        parsed_path = urllib.parse.urlparse(self.path)
        path = parsed_path.path

        if path == '/' or path == '/api':
            self.handle_api_info()
        elif path == '/api/status':
            self.handle_status()
        elif path == '/api/ping':
            self.handle_ping()
        elif path == '/api/clients':
            self.handle_get_clients()
        elif path.startswith('/api/clients/'):
            client_id = path.split('/')[-1]
            self.handle_get_client_data(client_id)
        else:
            self.send_json_error(404, "Endpoint not found")

    def do_POST(self):
        """Handle POST requests"""
        parsed_path = urllib.parse.urlparse(self.path)
        path = parsed_path.path

        if path == '/api/connect':
            self.handle_connect()
        elif path == '/api/heartbeat':
            self.handle_heartbeat()
        elif path == '/api/upload-session':
            self.handle_upload_session()
        else:
            self.send_json_error(404, "Endpoint not found")

    def send_json_response(self, status_code, data):
        """Send JSON response with proper headers"""
        response_json = json.dumps(data, indent=2)

        self.send_response(status_code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Content-Length', str(len(response_json.encode())))
        self.end_headers()
        self.wfile.write(response_json.encode())

    def send_json_error(self, status_code, message):
        """Send JSON error response"""
        error_data = {
            "error": True,
            "status_code": status_code,
            "message": message,
            "timestamp": datetime.now().isoformat()
        }
        self.send_json_response(status_code, error_data)

    def parse_json_body(self):
        """Parse JSON from request body"""
        try:
            content_length = int(self.headers.get('Content-Length', 0))
            if content_length == 0:
                return {}

            body = self.rfile.read(content_length)
            return json.loads(body.decode('utf-8'))
        except json.JSONDecodeError:
            raise ValueError("Invalid JSON in request body")
        except Exception as e:
            raise ValueError(f"Error reading request body: {str(e)}")

    def handle_api_info(self):
        """API information endpoint"""
        api_info = {
            "name": "Simple Ping App REST API",
            "version": "4.0.0",
            "description": "Simple REST API that sends ping instructions on every heartbeat",
            "timestamp": datetime.now().isoformat(),
            "endpoints": {
                "GET /api": "API information",
                "GET /api/status": "Server status",
                "GET /api/ping": "Simple ping test",
                "GET /api/clients": "List all clients",
                "GET /api/clients/{id}": "Get specific client data",
                "POST /api/connect": "Connect with device info",
                "POST /api/heartbeat": "Send heartbeat and get ping instruction",
                "POST /api/upload-session": "Upload ping session results"
            },
            "features": [
                "Always sends ping instructions on heartbeat",
                "Simple instruction selection",
                "Basic data storage",
                "Location tracking"
            ]
        }

        self.send_json_response(200, api_info)
        logger.info(f"API info requested from {self.client_address[0]}")

    def handle_status(self):
        """Server status endpoint"""
        status = {
            "status": "online",
            "message": "Server is running - always sends ping instructions",
            "timestamp": datetime.now().isoformat(),
            "database": "SQLite",
            "database_file": db.db_path,
            "client_ip": self.client_address[0],
            "instruction_mode": "always_send"
        }

        self.send_json_response(200, status)
        logger.info(f"Status check from {self.client_address[0]}")

    def handle_ping(self):
        """Simple ping test endpoint"""
        ping_response = {
            "ping": "pong",
            "timestamp": datetime.now().isoformat(),
            "client_ip": self.client_address[0]
        }

        self.send_json_response(200, ping_response)
        logger.info(f"Ping test from {self.client_address[0]}")

    def handle_connect(self):
        """Handle device connection with registration"""
        try:
            request_data = self.parse_json_body()

            # Register or update client
            client_id = db.register_or_update_client(request_data)

            if client_id:
                location = request_data.get('location', 'N/A')
                logger.info(f"Client connected: {client_id} at location: {location}")

                response = {
                    "status": "connected",
                    "message": "Device registered successfully - will receive ping instructions on every heartbeat",
                    "client_id": client_id,
                    "location_recorded": location,
                    "timestamp": datetime.now().isoformat(),
                    "instruction_mode": "always_send"
                }
                self.send_json_response(200, response)
            else:
                self.send_json_error(500, "Failed to register client")

        except ValueError as e:
            logger.error(f"Connection error: {e}")
            self.send_json_error(400, str(e))
        except Exception as e:
            logger.error(f"Unexpected connection error: {e}")
            self.send_json_error(500, "Internal server error")

    def handle_heartbeat(self):
        """Handle heartbeat signals and always send ping instructions"""
        try:
            request_data = self.parse_json_body()
            client_id = request_data.get('client_id', '')
            location = request_data.get('location', 'N/A')

            # Store heartbeat and get instruction (always returns one)
            instruction = db.store_heartbeat(request_data)

            logger.info(f"Heartbeat from client: {client_id} at location: {location}")

            # Build response with ping instruction
            response = {
                "heartbeat": "acknowledged",
                "server_status": "online",
                "location_recorded": location,
                "timestamp": datetime.now().isoformat(),
                "next_heartbeat_in_seconds": 30,
                "send_ping": True
            }

            # Add ping instruction (always available)
            if instruction and random.random() < prob:  # 90% chance to send instruction
                response.update({
                    "ping_host": instruction["host"],
                    "ping_protocol": instruction["protocol"],
                    "ping_duration_seconds": instruction["duration_seconds"],
                    "ping_interval_ms": instruction["interval_ms"],
                    "instruction_id": f"inst_{datetime.now().strftime('%Y%m%d_%H%M%S')}"
                })
                logger.info(f"Sent ping instruction to {client_id}: {instruction['host']} ({instruction['protocol']})")
            else:
                # Fallback (should not happen)
                response.update({
                    "send_ping": False,
                    "message": "No ping instruction available"
                })

            self.send_json_response(200, response)

        except Exception as e:
            logger.error(f"Heartbeat error: {e}")
            self.send_json_error(500, "Internal server error")

    def handle_upload_session(self):
        """Handle ping session data upload"""
        try:
            session_data = self.parse_json_body()

            # Validate required fields
            required_fields = ['session_id', 'client_id', 'host', 'protocol',
                             'start_time', 'end_time', 'packets_sent', 'packets_received']

            for field in required_fields:
                if field not in session_data:
                    self.send_json_error(400, f"Missing required field: {field}")
                    return

            # Store session data
            success = db.store_ping_session(session_data)

            if success:
                start_loc = session_data.get('start_location', 'N/A')
                end_loc = session_data.get('end_location', 'N/A')

                logger.info(f"Session data stored: {session_data['session_id']} from {session_data['client_id']} (Location: {start_loc} -> {end_loc})")

                response = {
                    "status": "success",
                    "message": "Session data uploaded successfully",
                    "session_id": session_data['session_id'],
                    "start_location": start_loc,
                    "end_location": end_loc,
                    "timestamp": datetime.now().isoformat()
                }
                self.send_json_response(200, response)
            else:
                self.send_json_error(500, "Failed to store session data")

        except ValueError as e:
            logger.error(f"Upload error: {e}")
            self.send_json_error(400, str(e))
        except Exception as e:
            logger.error(f"Unexpected upload error: {e}")
            self.send_json_error(500, "Internal server error")

    def handle_get_clients(self):
        """Get all clients data"""
        try:
            clients = db.get_client_stats()

            response = {
                "status": "success",
                "clients": clients,
                "total_clients": len(clients) if clients else 0,
                "timestamp": datetime.now().isoformat()
            }

            self.send_json_response(200, response)
            logger.info(f"Clients data requested from {self.client_address[0]}")

        except Exception as e:
            logger.error(f"Error getting clients: {e}")
            self.send_json_error(500, "Internal server error")

    def handle_get_client_data(self, client_id):
        """Get specific client data"""
        try:
            client_data = db.get_client_stats(client_id)

            if client_data:
                response = {
                    "status": "success",
                    "client": client_data,
                    "timestamp": datetime.now().isoformat()
                }
                self.send_json_response(200, response)
            else:
                self.send_json_error(404, f"Client {client_id} not found")

        except Exception as e:
            logger.error(f"Error getting client data: {e}")
            self.send_json_error(500, "Internal server error")

    def log_message(self, format, *args):
        """Override to use our logger"""
        logger.info(f"{self.client_address[0]} - {format % args}")

def run_server(port=8080):
    """Start the simplified REST API server"""
    server_address = ('', port)
    httpd = HTTPServer(server_address, PingRestApiHandler)

    logger.info(f"Starting Simple Ping REST API Server on port {port}")
    logger.info("Available REST endpoints:")
    logger.info("  GET  /api                - API information")
    logger.info("  GET  /api/status         - Server status")
    logger.info("  GET  /api/ping           - Simple ping test")
    logger.info("  GET  /api/clients        - List all clients")
    logger.info("  GET  /api/clients/{id}   - Get client data")
    logger.info("  POST /api/connect        - Connect device")
    logger.info("  POST /api/heartbeat      - Send heartbeat and get ping instruction")
    logger.info("  POST /api/upload-session - Upload session results")
    logger.info(f"Database: {db.db_path}")
    logger.info("Mode: ALWAYS SEND PING INSTRUCTIONS ON HEARTBEAT")

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        logger.info("Server shutting down...")
        httpd.shutdown()

if __name__ == '__main__':
    import argparse

    parser = argparse.ArgumentParser(description='Simple Ping REST API Server')
    parser.add_argument('--port', type=int, default=8080, help='Server port (default: 8080)')
    parser.add_argument('--db', type=str, default='ping_data.db', help='Database file path')
    args = parser.parse_args()

    db = PingDataServer(args.db)
    run_server(args.port)