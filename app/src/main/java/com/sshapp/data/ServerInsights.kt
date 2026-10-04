package com.sshapp.data

enum class Severity { INFO, WARN, CRITICAL }

data class Recommendation(
    val title: String,
    val detail: String,
    val command: String,
    val severity: Severity,
)

data class ServerInfo(
    val os: String?,
    val kernel: String?,
    val uptime: String?,
    val recommendations: List<Recommendation>,
)

/**
 * Probes the server with one read-only script and turns what it finds into suggested commands,
 * e.g. pending updates, failed services, a nearly full disk.
 */
object ServerInsights {
    val PROBE_SCRIPT = """
        . /etc/os-release 2>/dev/null; echo "OS=${'$'}PRETTY_NAME"
        echo "ID=${'$'}ID"
        echo "KERNEL=$(uname -r)"
        echo "UPTIME=$(uptime -p 2>/dev/null)"
        if [ -x /usr/lib/update-notifier/apt-check ]; then echo "APT=$(/usr/lib/update-notifier/apt-check 2>&1)"; fi
        [ -f /var/run/reboot-required ] && echo "REBOOT=1"
        echo "FAILED=$(systemctl --failed --no-legend --plain 2>/dev/null | awk '{print ${'$'}1}' | paste -sd, -)"
        echo "DISK=$(df -P / | awk 'NR==2{gsub("%","",${'$'}5); print ${'$'}5}')"
        echo "MEM=$(free -m | awk '/^Mem:/{print ${'$'}2" "${'$'}7}')"
        echo "LOAD=$(cut -d' ' -f1 /proc/loadavg) $(nproc)"
        command -v docker >/dev/null 2>&1 && echo "DOCKER=1"
        command -v ufw >/dev/null 2>&1 && echo "UFW=1"
        echo "JOURNAL=$(journalctl --disk-usage 2>/dev/null | grep -o '[0-9.]*[KMGT]' | head -1)"
    """.trimIndent()

    fun parse(output: String): ServerInfo {
        val kv = output.lines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1).trim()
        }.toMap()
        val recs = mutableListOf<Recommendation>()

        kv["APT"]?.split(';')?.mapNotNull { it.trim().toIntOrNull() }?.let { counts ->
            val total = counts.getOrElse(0) { 0 }
            val security = counts.getOrElse(1) { 0 }
            if (total > 0) recs += Recommendation(
                "$total package update${if (total == 1) "" else "s"} available",
                if (security > 0) "$security of them are security updates." else "Keep the system patched.",
                "sudo apt update && sudo apt upgrade -y",
                if (security > 0) Severity.WARN else Severity.INFO,
            )
        }
        if (kv["REBOOT"] == "1") recs += Recommendation(
            "Reboot required", "Installed updates need a restart to take effect.",
            "cat /var/run/reboot-required.pkgs; sudo reboot", Severity.WARN,
        )
        kv["FAILED"]?.takeIf { it.isNotBlank() }?.split(',')?.let { failed ->
            recs += Recommendation(
                "${failed.size} failed service${if (failed.size == 1) "" else "s"}",
                failed.joinToString(", "),
                "systemctl status ${failed.first()} --no-pager",
                Severity.CRITICAL,
            )
        }
        kv["DISK"]?.toIntOrNull()?.let { pct ->
            if (pct >= 80) recs += Recommendation(
                "Root disk $pct% full", "Find what is using space before it fills up.",
                "sudo du -h --max-depth=1 / 2>/dev/null | sort -h | tail -15",
                if (pct >= 90) Severity.CRITICAL else Severity.WARN,
            )
        }
        kv["MEM"]?.split(' ')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }?.let { (total, avail) ->
            if (total > 0 && avail * 100 / total < 10) recs += Recommendation(
                "Low memory: ${avail} MB free of $total MB", "See which processes use the most memory.",
                "ps aux --sort=-%mem | head -15", Severity.WARN,
            )
        }
        kv["LOAD"]?.split(' ')?.let { parts ->
            val load = parts.getOrNull(0)?.toDoubleOrNull()
            val cpus = parts.getOrNull(1)?.toIntOrNull()
            if (load != null && cpus != null && load > cpus) recs += Recommendation(
                "High load: $load on $cpus CPU${if (cpus == 1) "" else "s"}", "Check what is consuming CPU.",
                "ps aux --sort=-%cpu | head -15", Severity.WARN,
            )
        }
        kv["JOURNAL"]?.let { size ->
            if (size.endsWith("G")) recs += Recommendation(
                "Journal uses $size", "Old logs can be trimmed safely.",
                "sudo journalctl --vacuum-time=14d", Severity.INFO,
            )
        }
        if (kv["DOCKER"] == "1") recs += Recommendation(
            "Docker installed", "See running containers and their status.", "docker ps", Severity.INFO,
        )
        if (kv["UFW"] == "1") recs += Recommendation(
            "Firewall (ufw)", "Review which ports are open.", "sudo ufw status verbose", Severity.INFO,
        )

        return ServerInfo(
            os = kv["OS"]?.takeIf { it.isNotBlank() },
            kernel = kv["KERNEL"],
            uptime = kv["UPTIME"]?.takeIf { it.isNotBlank() },
            recommendations = recs.sortedByDescending { it.severity.ordinal },
        )
    }
}
