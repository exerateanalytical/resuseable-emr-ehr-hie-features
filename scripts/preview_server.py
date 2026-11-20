#!/usr/bin/env python3
"""
OPES HealthCore SDK — Lightweight Local Preview Server (Port 3000)
Serves live module inventory, 8 isolated Industry Capability Packs, 28 versioned
JSON/YAML workflow definitions, Markdown documentation, and /health endpoint
without adding any Node.js/TypeScript build artifacts to the Pure Java 25 repo.
"""

from __future__ import annotations

import html
import json
import os
import pathlib
import re
import urllib.parse
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = pathlib.Path(__file__).resolve().parent.parent
PORT = int(os.environ.get("DEFAULT_APP_PORT", "3000"))
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def load_catalog() -> dict:
    pom_root = ET.parse(ROOT / "pom.xml").getroot()
    modules = [el.text.strip() for el in pom_root.findall("m:modules/m:module", NS) if el.text]

    packs = []
    packs_dir = ROOT / "packs"
    if packs_dir.is_dir():
        for pack_dir in sorted(p for p in packs_dir.iterdir() if p.is_dir()):
            manifest = pack_dir / "src/main/resources/META-INF/healthcore/pack-manifest.json"
            if manifest.is_file():
                try:
                    packs.append(json.loads(manifest.read_text(encoding="utf-8")))
                except Exception:
                    packs.append({"packId": pack_dir.name})

    core_workflows = []
    core_dir = ROOT / "healthcore-starter-workflow-engine/src/main/resources/workflows/definitions"
    if core_dir.is_dir():
        for jf in sorted(core_dir.glob("*.json")):
            data = json.loads(jf.read_text(encoding="utf-8"))
            core_workflows.append({
                "file": jf.name,
                "scope": "core",
                "workflowId": data.get("workflowId"),
                "version": data.get("version"),
                "startingEvent": data.get("startingEvent"),
                "domain": data.get("applicableDomain"),
                "requiredModules": data.get("requiredModules", []),
            })

    module_workflows = []
    docs_defs_dir = ROOT / "docs/workflows/definitions"
    if docs_defs_dir.is_dir():
        for jf in sorted(docs_defs_dir.glob("*.json")):
            data = json.loads(jf.read_text(encoding="utf-8"))
            module_workflows.append({
                "file": jf.name,
                "scope": "module",
                "workflowId": data.get("workflowId"),
                "version": data.get("version"),
                "startingEvent": data.get("startingEvent"),
                "domain": data.get("applicableDomain"),
                "requiredModules": data.get("requiredModules", []),
            })

    workflow_docs = sorted(
        p.name for p in (ROOT / "docs/workflows").glob("*.md")
    ) if (ROOT / "docs/workflows").is_dir() else []

    return {
        "name": "OPES HealthCore SDK",
        "version": "1.0.0",
        "runtime": "Pure Java 25 / Spring Boot 4.0 / Maven / Liquibase",
        "moduleCount": len(modules),
        "modules": modules,
        "packCount": len(packs),
        "packs": packs,
        "coreWorkflowCount": len(core_workflows),
        "moduleWorkflowCount": len(module_workflows),
        "coreWorkflows": core_workflows,
        "moduleWorkflows": module_workflows,
        "workflowDocs": workflow_docs,
    }


def render_dashboard(catalog: dict) -> str:
    pack_cards = []
    for p in catalog["packs"]:
        pid = html.escape(str(p.get("packId") or p.get("id") or "pack"))
        title = html.escape(str(p.get("displayName") or p.get("name") or pid))
        req = len(p.get("requiredModules", []))
        forb = len(p.get("forbiddenTransitiveModules", []))
        pack_cards.append(
            f'<div class="card">'
            f'<div class="kicker">ISOLATED CAPABILITY PACK</div>'
            f'<h3>{title}</h3>'
            f'<div class="mono">{pid}</div>'
            f'<div class="meta">{req} required modules &middot; {forb} forbidden leaks enforced</div>'
            f'</div>'
        )

    wf_rows = []
    for w in catalog["coreWorkflows"] + catalog["moduleWorkflows"]:
        badge = "CORE" if w["scope"] == "core" else "MODULE"
        wf_id = html.escape(str(w["workflowId"]))
        ver = html.escape(str(w["version"]))
        ev = html.escape(str(w["startingEvent"]))
        dom = html.escape(str(w["domain"]))
        href = f'/api/workflows/{urllib.parse.quote(w["scope"])}/{urllib.parse.quote(w["file"])}'
        wf_rows.append(
            f'<tr>'
            f'<td><span class="tag tag-{w["scope"]}">{badge}</span></td>'
            f'<td class="mono">{wf_id} <span class="muted">v{ver}</span></td>'
            f'<td class="mono">{ev}</td>'
            f'<td>{dom}</td>'
            f'<td><button class="btn-link" onclick="openViewer(\'{href}\', \'{wf_id} v{ver}\')">Inspect JSON</button></td>'
            f'</tr>'
        )

    doc_links = []
    for d in catalog["workflowDocs"]:
        esc = html.escape(d)
        href = f'/api/docs/workflows/{urllib.parse.quote(d)}'
        doc_links.append(
            f'<button class="doc-item" onclick="openViewer(\'{href}\', \'{esc}\')">{esc}</button>'
        )
    doc_links.append(
        '<button class="doc-item" onclick="openViewer(\'/api/workflows/module/workflow-registry.v1.yaml\', \'workflow-registry.v1.yaml\')">workflow-registry.v1.yaml</button>'
    )

    mod_items = "".join(
        f'<div class="mod-cell mono">{html.escape(m)}</div>' for m in catalog["modules"]
    )

    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>OPES HealthCore SDK — Architecture &amp; Workflow Explorer</title>
  <style>
    :root {{
      --bg: #f6f8fa; --surface: #ffffff; --ink: #0f172a; --muted: #475569;
      --border: #e2e8f0; --accent: #0f766e; --accent-soft: #f0fdfa; --core: #1d4ed8;
    }}
    * {{ box-sizing: border-box; }}
    body {{
      margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      background: var(--bg); color: var(--ink); line-height: 1.5;
    }}
    header {{
      background: #0b1324; color: #f8fafc; padding: 28px 32px; border-bottom: 1px solid #1e293b;
    }}
    header h1 {{ margin: 0 0 6px; font-size: 24px; letter-spacing: -0.02em; }}
    header p {{ margin: 0; color: #cbd5e1; font-size: 14px; }}
    .stats {{
      display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 14px; margin-top: 20px;
    }}
    .stat {{
      background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.12);
      border-radius: 8px; padding: 12px 16px;
    }}
    .stat .val {{ font-size: 22px; font-weight: 700; color: #5eead4; }}
    .stat .lbl {{ font-size: 12px; color: #cbd5e1; text-transform: uppercase; letter-spacing: 0.04em; }}
    main {{ max-width: 1280px; margin: 0 auto; padding: 24px 32px 64px; }}
    section {{ margin-top: 28px; }}
    h2 {{ font-size: 18px; margin: 0 0 12px; color: var(--ink); border-bottom: 1px solid var(--border); padding-bottom: 8px; }}
    .grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 14px; }}
    .card {{
      background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 16px;
    }}
    .kicker {{ font-size: 11px; font-weight: 600; color: var(--accent); letter-spacing: 0.05em; }}
    .card h3 {{ margin: 4px 0; font-size: 15px; }}
    .meta {{ margin-top: 8px; font-size: 12px; color: var(--muted); }}
    .mono {{ font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 12px; }}
    .muted {{ color: var(--muted); }}
    .docs-grid {{ display: flex; flex-wrap: wrap; gap: 8px; }}
    .doc-item {{
      background: var(--surface); border: 1px solid var(--border); border-radius: 6px;
      padding: 8px 12px; font-size: 13px; color: var(--ink); cursor: pointer;
    }}
    .doc-item:hover {{ border-color: var(--accent); background: var(--accent-soft); }}
    .table-wrap {{ background: var(--surface); border: 1px solid var(--border); border-radius: 8px; overflow-x: auto; }}
    table {{ width: 100%; border-collapse: collapse; font-size: 13px; }}
    th, td {{ text-align: left; padding: 10px 12px; border-bottom: 1px solid var(--border); vertical-align: top; }}
    th {{ background: #f8fafc; font-weight: 600; color: var(--muted); font-size: 12px; text-transform: uppercase; }}
    .tag {{ display: inline-block; padding: 2px 7px; border-radius: 4px; font-size: 11px; font-weight: 600; }}
    .tag-core {{ background: #dbeafe; color: #1e40af; }}
    .tag-module {{ background: #ccfbf1; color: #115e59; }}
    .btn-link {{
      background: none; border: 1px solid var(--border); border-radius: 5px; padding: 4px 9px;
      font-size: 12px; color: var(--accent); cursor: pointer; font-weight: 600;
    }}
    .btn-link:hover {{ background: var(--accent-soft); border-color: var(--accent); }}
    .mod-grid {{ display: grid; grid-template-columns: repeat(auto-fill, minmax(270px, 1fr)); gap: 8px; }}
    .mod-cell {{ background: var(--surface); border: 1px solid var(--border); border-radius: 6px; padding: 8px 10px; }}
    .modal-backdrop {{
      position: fixed; inset: 0; background: rgba(15,23,42,0.55); display: none;
      align-items: center; justify-content: center; padding: 24px; z-index: 100;
    }}
    .modal-backdrop.open {{ display: flex; }}
    .modal {{
      background: var(--surface); width: min(980px, 100%); max-height: 86vh; border-radius: 10px;
      display: flex; flex-direction: column; overflow: hidden; box-shadow: 0 20px 50px rgba(0,0,0,0.3);
    }}
    .modal-head {{
      display: flex; align-items: center; justify-content: space-between; padding: 14px 18px;
      border-bottom: 1px solid var(--border); background: #f8fafc;
    }}
    .modal-body {{
      margin: 0; padding: 18px; overflow: auto; font-size: 12px; line-height: 1.5;
      background: #0b1324; color: #e2e8f0; white-space: pre-wrap; word-break: break-word;
    }}
  </style>
</head>
<body>
  <header>
    <h1>OPES HealthCore SDK v{html.escape(catalog["version"])}</h1>
    <p>{html.escape(catalog["runtime"])} &middot; Reusable Clinical Workflows, 8 Isolated Capability Packs &amp; 28 Versioned Workflow Blueprints</p>
    <div class="stats">
      <div class="stat"><div class="val">{catalog["moduleCount"]}</div><div class="lbl">Maven Reactor Modules</div></div>
      <div class="stat"><div class="val">{catalog["packCount"]}</div><div class="lbl">Isolated Industry Packs</div></div>
      <div class="stat"><div class="val">{catalog["coreWorkflowCount"]} + {catalog["moduleWorkflowCount"]}</div><div class="lbl">Versioned Workflow JSONs</div></div>
      <div class="stat"><div class="val">Tests A–J</div><div class="lbl">Patient Journey Acceptance</div></div>
    </div>
  </header>

  <main>
    <section>
      <h2>8 Composable Industry Capability Packs</h2>
      <div class="grid">{"".join(pack_cards)}</div>
    </section>

    <section>
      <h2>Master Workflow Specifications &amp; YAML Registry</h2>
      <div class="docs-grid">{"".join(doc_links)}</div>
    </section>

    <section>
      <h2>28 Machine-Readable Versioned Workflow Definitions (6 Core + 22 Modules)</h2>
      <div class="table-wrap">
        <table>
          <thead>
            <tr><th>Scope</th><th>Workflow ID &amp; Version</th><th>Starting Event</th><th>Applicable Domain</th><th>Definition</th></tr>
          </thead>
          <tbody>{"".join(wf_rows)}</tbody>
        </table>
      </div>
    </section>

    <section>
      <h2>All {catalog["moduleCount"]} Reactor Modules</h2>
      <div class="mod-grid">{mod_items}</div>
    </section>
  </main>

  <div id="viewerBackdrop" class="modal-backdrop" onclick="if (event.target === this) closeViewer()">
    <div class="modal">
      <div class="modal-head">
        <strong id="viewerTitle" class="mono"></strong>
        <button class="btn-link" onclick="closeViewer()">Close</button>
      </div>
      <pre id="viewerContent" class="modal-body"></pre>
    </div>
  </div>

  <script>
    async function openViewer(url, title) {{
      document.getElementById('viewerTitle').textContent = title;
      document.getElementById('viewerContent').textContent = 'Loading...';
      document.getElementById('viewerBackdrop').classList.add('open');
      try {{
        const res = await fetch(url);
        const text = await res.text();
        document.getElementById('viewerContent').textContent = text;
      }} catch (err) {{
        document.getElementById('viewerContent').textContent = 'Failed to load: ' + err;
      }}
    }}
    function closeViewer() {{
      document.getElementById('viewerBackdrop').classList.remove('open');
    }}
  </script>
</body>
</html>"""


class HealthCorePreviewHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args) -> None:
        pass

    def _send(self, status: int, content_type: str, payload: bytes) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path == "/health":
            catalog = load_catalog()
            body = json.dumps({
                "status": "UP",
                "port": PORT,
                "modules": catalog["moduleCount"],
                "packs": catalog["packCount"],
                "workflowDefinitions": catalog["coreWorkflowCount"] + catalog["moduleWorkflowCount"],
            }).encode("utf-8")
            self._send(200, "application/json; charset=utf-8", body)
            return

        if path == "/api/catalog":
            body = json.dumps(load_catalog(), indent=2).encode("utf-8")
            self._send(200, "application/json; charset=utf-8", body)
            return

        if path.startswith("/api/workflows/"):
            rel = path[len("/api/workflows/"):]
            parts = rel.split("/", 1)
            if len(parts) == 2 and re.fullmatch(r"[A-Za-z0-9._-]+", parts[1]):
                scope, fname = parts
                base = (
                    ROOT / "healthcore-starter-workflow-engine/src/main/resources/workflows/definitions"
                    if scope == "core"
                    else ROOT / "docs/workflows/definitions"
                )
                target = (base / fname).resolve()
                if target.is_file() and target.parent == base.resolve():
                    ctype = "application/json; charset=utf-8" if fname.endswith(".json") else "text/yaml; charset=utf-8"
                    self._send(200, ctype, target.read_bytes())
                    return
            self._send(404, "application/json; charset=utf-8", b'{"error":"workflow_not_found"}')
            return

        if path.startswith("/api/docs/workflows/"):
            fname = path[len("/api/docs/workflows/"):]
            if re.fullmatch(r"[A-Za-z0-9._-]+\.md", fname):
                base = (ROOT / "docs/workflows").resolve()
                target = (base / fname).resolve()
                if target.is_file() and target.parent == base:
                    self._send(200, "text/markdown; charset=utf-8", target.read_bytes())
                    return
            self._send(404, "application/json; charset=utf-8", b'{"error":"doc_not_found"}')
            return

        if path in ("/", "/index.html"):
            html_doc = render_dashboard(load_catalog()).encode("utf-8")
            self._send(200, "text/html; charset=utf-8", html_doc)
            return

        self._send(404, "text/plain; charset=utf-8", b"Not Found")


class ReusableThreadingHTTPServer(ThreadingHTTPServer):
    allow_reuse_address = True


if __name__ == "__main__":
    server = ReusableThreadingHTTPServer(("0.0.0.0", PORT), HealthCorePreviewHandler)
    print(f"OPES HealthCore preview server listening on http://0.0.0.0:{PORT}", flush=True)
    server.serve_forever()
