"""ClefForge artifact server — serves built JARs over HTTP."""
import http.server
import socketserver
import os

ARTIFACT_DIR = "/forge/artifacts"
PORT = 8080


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ARTIFACT_DIR, **kwargs)


def main():
    os.makedirs(ARTIFACT_DIR, exist_ok=True)
    with socketserver.TCPServer(("0.0.0.0", PORT), Handler) as httpd:
        print(f"ClefForge serving artifacts on :{PORT}")
        httpd.serve_forever()


if __name__ == "__main__":
    main()
