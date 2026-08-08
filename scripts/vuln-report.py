#!/usr/bin/env python3
"""Dependency vulnerability report generator.

Reads raw JSON output from ecosystem-native vulnerability scanners,
compares findings against a YAML baseline of known/accepted vulnerabilities,
and generates a Markdown report for PR comments and step summaries.

Supported scanners:
    - govulncheck (Go)      — govulncheck -format json ./...
    - npm audit (Node)       — npm audit --json
    - cargo audit (Rust)     — cargo audit --json
    - pip-audit (Python)     — pip-audit --format json
    - trivy (Java/Maven)     — trivy fs --format json --scanners vuln .

The report uses a hidden HTML comment (<!-- vuln-scan-report -->) so the
workflow can find and update an existing comment on subsequent pushes.
"""

import argparse
import json
import os
import sys
from datetime import date, datetime
from pathlib import Path

import yaml


# ── Scanner output parsers ────────────────────────────────────────────────────
# Each parser reads the raw JSON from its scanner and returns a list of
# normalized finding dicts with these keys:
#   id, alt_ids, package, version, fixed_in, severity, ecosystem, title


def parse_go_vulns(path: str) -> list[dict]:
    """Parse govulncheck -format json output."""
    data = _load_json(path)
    if data is None:
        return []

    findings = []
    for vuln in data.get("vulns", []):
        osv = vuln.get("osv", {})
        vuln_id = osv.get("id", "unknown")
        aliases = osv.get("aliases", [])
        display_id = _prefer_cve(vuln_id, aliases)

        for module in vuln.get("modules", []):
            findings.append({
                "id": display_id,
                "alt_ids": [vuln_id] + aliases,
                "package": module.get("path", "unknown"),
                "version": module.get("found_version", "unknown"),
                "fixed_in": module.get("fixed_version", ""),
                "severity": _extract_osv_severity(osv),
                "ecosystem": "go",
                "title": osv.get("summary", ""),
            })
    return findings


def parse_node_vulns(path: str) -> list[dict]:
    """Parse npm audit --json output."""
    data = _load_json(path)
    if data is None:
        return []

    findings = []
    for pkg_name, info in data.get("vulnerabilities", {}).items():
        severity = (info.get("severity") or "unknown").upper()
        for via in info.get("via", []):
            if not isinstance(via, dict):
                continue
            url = via.get("url", "")
            ghsa_id = url.split("/")[-1] if "advisories" in url else ""
            vuln_id = ghsa_id or f"npm-{via.get('source', 'unknown')}"

            fix_available = info.get("fixAvailable", False)
            fixed_in = ""
            if isinstance(fix_available, dict):
                fixed_in = fix_available.get("version", "")
            elif fix_available:
                fixed_in = "upgrade available"

            findings.append({
                "id": vuln_id,
                "alt_ids": [],
                "package": pkg_name,
                "version": info.get("range", "unknown"),
                "fixed_in": fixed_in,
                "severity": severity,
                "ecosystem": "node",
                "title": via.get("title", ""),
            })
    return findings


def parse_rust_vulns(path: str) -> list[dict]:
    """Parse cargo audit --json output."""
    data = _load_json(path)
    if data is None:
        return []

    findings = []
    for vuln in data.get("vulnerabilities", {}).get("found", []):
        advisory = vuln.get("advisory", {})
        package = vuln.get("package", {})
        patched = vuln.get("versions", {}).get("patched", [])

        vuln_id = advisory.get("id", "unknown")
        aliases = advisory.get("aliases", [])
        display_id = _prefer_cve(vuln_id, aliases)

        findings.append({
            "id": display_id,
            "alt_ids": [vuln_id] + aliases,
            "package": package.get("name", "unknown"),
            "version": package.get("version", "unknown"),
            "fixed_in": patched[0] if patched else "",
            "severity": (advisory.get("severity") or "unknown").upper(),
            "ecosystem": "rust",
            "title": advisory.get("title", ""),
        })
    return findings


def parse_python_vulns(path: str) -> list[dict]:
    """Parse pip-audit --format json output."""
    data = _load_json(path)
    if data is None:
        return []

    findings = []
    packages = data if isinstance(data, list) else data.get("dependencies", [])
    for pkg in packages:
        for vuln in pkg.get("vulns", []):
            vuln_id = vuln.get("id", "unknown")
            aliases = vuln.get("aliases", [])
            display_id = _prefer_cve(vuln_id, aliases)
            fix_versions = vuln.get("fix_versions", [])

            findings.append({
                "id": display_id,
                "alt_ids": [vuln_id] + aliases,
                "package": pkg.get("name", "unknown"),
                "version": pkg.get("version", "unknown"),
                "fixed_in": fix_versions[0] if fix_versions else "",
                "severity": (vuln.get("severity") or "UNKNOWN").upper(),
                "ecosystem": "python",
                "title": (vuln.get("description") or "")[:120],
            })
    return findings


def parse_java_vulns(path: str) -> list[dict]:
    """Parse trivy fs --format json output for Java (pom.xml / build.gradle)."""
    data = _load_json(path)
    if data is None:
        return []

    findings = []
    results = data.get("Results", [])
    for result in results:
        for vuln in result.get("Vulnerabilities") or []:
            vuln_id = vuln.get("VulnerabilityID", "unknown")
            pkg_name = vuln.get("PkgName", "unknown")
            installed = vuln.get("InstalledVersion", "unknown")
            fixed = vuln.get("FixedVersion", "")
            severity = (vuln.get("Severity") or "UNKNOWN").upper()
            title = vuln.get("Title", "")

            findings.append({
                "id": vuln_id,
                "alt_ids": [],
                "package": pkg_name,
                "version": installed,
                "fixed_in": fixed,
                "severity": severity,
                "ecosystem": "java",
                "title": title[:120],
            })
    return findings


# ── Baseline comparison ───────────────────────────────────────────────────────


def load_baseline(path: str) -> dict:
    """Load the vulnerability baseline YAML file."""
    if not os.path.exists(path):
        return {"vulnerabilities": []}
    with open(path) as f:
        data = yaml.safe_load(f) or {}
    return data


def diff_findings(
    findings: list[dict], baseline: dict
) -> tuple[list[dict], list[dict], list[dict]]:
    """Compare scanner findings against the baseline.

    Returns:
        new_findings:   vulnerabilities not in baseline (action required)
        known_findings: vulnerabilities tracked in baseline
        stale_entries:  baseline entries past their review-by date
    """
    baseline_vulns = baseline.get("vulnerabilities", []) or []

    # Collect every ID the baseline recognizes
    baseline_ids: set[str] = set()
    for bv in baseline_vulns:
        baseline_ids.add(bv.get("id", ""))

    new_findings = []
    known_findings = []

    for finding in findings:
        all_ids = {finding["id"]} | set(finding.get("alt_ids", []))
        if all_ids & baseline_ids:
            known_findings.append(finding)
        else:
            new_findings.append(finding)

    # Flag baseline entries whose review-by date has passed
    today = date.today()
    stale_entries = []
    for bv in baseline_vulns:
        review_by_str = bv.get("review-by", "")
        if not review_by_str:
            continue
        try:
            review_by = datetime.strptime(str(review_by_str), "%Y-%m-%d").date()
            if review_by < today:
                stale_entries.append(bv)
        except ValueError:
            pass

    return new_findings, known_findings, stale_entries


# ── Markdown report generation ────────────────────────────────────────────────


def generate_report(
    new_findings: list[dict],
    known_findings: list[dict],
    stale_entries: list[dict],
    scan_summary: dict[str, int | None],
) -> str:
    """Generate the full Markdown vulnerability report."""
    lines: list[str] = []

    # Hidden marker so the workflow can find and update this comment
    lines.append("<!-- vuln-scan-report -->")
    lines.append("## Dependency Vulnerability Scan\n")

    total_new = len(new_findings)
    total_known = len(known_findings)
    total_stale = len(stale_entries)

    if total_new == 0 and total_known == 0 and total_stale == 0:
        lines.append("No dependency vulnerabilities found.\n")
        lines.append(_scan_summary(scan_summary))
        return "\n".join(lines)

    # ── New findings ──────────────────────────────────────────────────────
    if new_findings:
        lines.append(f"### New Findings ({total_new})\n")
        lines.append(
            "These vulnerabilities are **not in the baseline** and require action.\n"
        )
        lines.append("| ID | Package | Ecosystem | Severity | Fixed In |")
        lines.append("|---|---|---|---|---|")
        for f in sorted(new_findings, key=lambda x: _severity_rank(x["severity"])):
            fixed = f["fixed_in"] or "no fix available"
            lines.append(
                f"| {f['id']} | `{f['package']}` | {f['ecosystem']} "
                f"| {f['severity']} | {fixed} |"
            )
        lines.append("")
        lines.append(
            "**Action required:** Upgrade the dependency to the fixed version, "
            "or add to `security/vulnerability-baseline.yml` with justification "
            "and a review-by date.\n"
        )
    else:
        lines.append("### New Findings\n")
        lines.append(
            "None -- all detected vulnerabilities are tracked in the baseline.\n"
        )

    # ── Known / accepted ──────────────────────────────────────────────────
    if known_findings:
        lines.append(f"### Known/Accepted ({total_known})\n")
        lines.append(
            "These vulnerabilities are tracked in "
            "`security/vulnerability-baseline.yml`.\n"
        )
        lines.append("| ID | Package | Ecosystem | Severity |")
        lines.append("|---|---|---|---|")
        for f in known_findings:
            lines.append(
                f"| {f['id']} | `{f['package']}` | {f['ecosystem']} "
                f"| {f['severity']} |"
            )
        lines.append("")

    # ── Stale baseline entries ────────────────────────────────────────────
    if stale_entries:
        lines.append(f"### Stale Baseline Entries ({total_stale})\n")
        lines.append(
            "These baseline entries are **past their review-by date** "
            "and need re-evaluation.\n"
        )
        lines.append("| ID | Package | Review By |")
        lines.append("|---|---|---|")
        for e in stale_entries:
            lines.append(
                f"| {e.get('id', '?')} | `{e.get('package', '?')}` "
                f"| {e.get('review-by', '?')} |"
            )
        lines.append("")
        lines.append(
            "**Action required:** Update the review-by date or remove "
            "if the vulnerability is fixed.\n"
        )

    # ── Scan summary ──────────────────────────────────────────────────────
    lines.append(_scan_summary(scan_summary))

    # ── Footer ────────────────────────────────────────────────────────────
    lines.append("---")
    lines.append(
        "*Generated by `dependency-vulns` workflow. "
        "Edit `security/vulnerability-baseline.yml` to track accepted risks.*"
    )

    return "\n".join(lines)


# ── Helpers ───────────────────────────────────────────────────────────────────


def _load_json(path: str) -> dict | list | None:
    """Load a JSON file, returning None if missing or unparseable."""
    if not os.path.exists(path):
        return None
    try:
        with open(path) as f:
            return json.load(f)
    except (json.JSONDecodeError, IOError):
        return None


def _prefer_cve(default_id: str, aliases: list[str]) -> str:
    """Return the CVE alias if one exists, otherwise the original ID."""
    return next((a for a in aliases if a.startswith("CVE-")), default_id)


def _extract_osv_severity(osv: dict) -> str:
    """Extract a severity label from an OSV record."""
    severities = osv.get("severity", [])
    if isinstance(severities, list):
        for s in severities:
            if isinstance(s, dict) and "score" in s:
                return _cvss_to_label(s["score"])
    db_severity = osv.get("database_specific", {}).get("severity", "")
    if isinstance(db_severity, str) and db_severity:
        return db_severity.upper()
    return "UNKNOWN"


def _cvss_to_label(score_str: str) -> str:
    """Convert a numeric CVSS score to a severity label."""
    try:
        score = float(score_str)
    except (ValueError, TypeError):
        return "UNKNOWN"
    if score >= 9.0:
        return "CRITICAL"
    if score >= 7.0:
        return "HIGH"
    if score >= 4.0:
        return "MEDIUM"
    if score > 0:
        return "LOW"
    return "NONE"


def _severity_rank(severity: str) -> int:
    """Sort key — lower value = more severe."""
    return {
        "CRITICAL": 0,
        "HIGH": 1,
        "MEDIUM": 2,
        "LOW": 3,
        "NONE": 4,
        "UNKNOWN": 5,
    }.get(severity.upper(), 5)


def _scan_summary(scan_summary: dict[str, int | None]) -> str:
    """Render the scan summary section."""
    lines = ["### Scan Summary\n"]
    for eco, count in scan_summary.items():
        if count is None:
            lines.append(f"- **{eco}**: not scanned (no lock file detected)")
        elif count == 0:
            lines.append(f"- **{eco}**: clean")
        else:
            noun = "vulnerability" if count == 1 else "vulnerabilities"
            lines.append(f"- **{eco}**: {count} {noun} found")
    lines.append("")
    return "\n".join(lines)


# ── Main ──────────────────────────────────────────────────────────────────────


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate dependency vulnerability report"
    )
    parser.add_argument(
        "--baseline",
        default="security/vulnerability-baseline.yml",
        help="Path to vulnerability baseline file",
    )
    parser.add_argument(
        "--output",
        default="/tmp/vuln-report.md",
        help="Path to write the Markdown report",
    )
    parser.add_argument("--go", default="/tmp/go-vulns.json")
    parser.add_argument("--node", default="/tmp/node-vulns.json")
    parser.add_argument("--rust", default="/tmp/rust-vulns.json")
    parser.add_argument("--python", default="/tmp/python-vulns.json")
    parser.add_argument("--java", default="/tmp/java-vulns.json")
    args = parser.parse_args()

    # Parse findings from each ecosystem
    go_findings = parse_go_vulns(args.go)
    node_findings = parse_node_vulns(args.node)
    rust_findings = parse_rust_vulns(args.rust)
    python_findings = parse_python_vulns(args.python)
    java_findings = parse_java_vulns(args.java)

    all_findings = (
        go_findings + node_findings + rust_findings
        + python_findings + java_findings
    )

    # Build scan summary (None = not scanned, 0+ = finding count)
    scan_summary = {
        "Go": len(go_findings) if os.path.exists(args.go) else None,
        "Node": len(node_findings) if os.path.exists(args.node) else None,
        "Rust": len(rust_findings) if os.path.exists(args.rust) else None,
        "Python": len(python_findings) if os.path.exists(args.python) else None,
        "Java": len(java_findings) if os.path.exists(args.java) else None,
    }

    # Load baseline and diff
    baseline = load_baseline(args.baseline)
    new_findings, known_findings, stale_entries = diff_findings(
        all_findings, baseline
    )

    # Generate report
    report = generate_report(new_findings, known_findings, stale_entries, scan_summary)

    # Write report file
    os.makedirs(os.path.dirname(args.output) or ".", exist_ok=True)
    with open(args.output, "w") as f:
        f.write(report)

    # Set GitHub Actions outputs if running in CI
    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a") as f:
            f.write(f"new_count={len(new_findings)}\n")
            f.write(f"known_count={len(known_findings)}\n")
            f.write(f"stale_count={len(stale_entries)}\n")
            has_findings = "true" if new_findings or stale_entries else "false"
            f.write(f"has_findings={has_findings}\n")

    print(
        f"Vulnerability scan: {len(new_findings)} new, "
        f"{len(known_findings)} known, {len(stale_entries)} stale"
    )


if __name__ == "__main__":
    main()
