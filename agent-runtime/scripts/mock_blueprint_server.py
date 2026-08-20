import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

BLUEPRINT_PATH = Path(
    "/Users/haoli/webproject/VibeSales/sales-customer-agent/src/main/resources/blueprints/yjiyuncom.test.json"
)
BLUEPRINT = json.loads(BLUEPRINT_PATH.read_text(encoding="utf-8"))


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        return

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path != "/api/v1/blueprints/published":
            self.respond(404, {"success": False, "errorCode": "not_found", "error": "path not found"})
            return
        query = parse_qs(parsed.query)
        client_code = (query.get("clientCode") or [""])[0]
        cluster = (query.get("cluster") or [""])[0]
        scene_code = (query.get("sceneCode") or [""])[0]
        if client_code != "yjiyuncom":
            self.respond(
                404,
                {
                    "success": False,
                    "errorCode": "blueprint_not_found",
                    "error": "client not found",
                },
            )
            return
        matched_cluster = "test" if cluster == "test" else ""
        match_level = "exact" if cluster == "test" else ("tenant_default" if not cluster else "cluster_fallback")
        self.respond(
            200,
            {
                "success": True,
                "data": {
                    "sourceId": f"mock-remote:{client_code}/{matched_cluster or 'default'}",
                    "matchLevel": match_level,
                    "matchedCluster": matched_cluster,
                    "sceneCode": scene_code,
                    "blueprint": BLUEPRINT,
                },
            },
        )

    def respond(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    HTTPServer(("127.0.0.1", 18088), Handler).serve_forever()
